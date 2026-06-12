package com.megastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import kotlin.random.Random

class MegaStreamProvider : MainAPI() {
    override var mainUrl = MegaStreamConstants.STREAMPLAY_URL
    override var name = "MegaStream"
    override val hasMainPage = true 
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "en"

    // Initializes extraction router securely with 'this' reference for newExtractorLink capability
    private val extractors by lazy { MegaStreamExtractors(this) }
    private var activeOmdbKeys = MegaStreamConstants.OMDB_KEYS.toMutableList()

    // OMDb JSON Data Models
    data class OmdbSearchResponse(val Search: List<OmdbSearchResult>?, val Response: String?)
    data class OmdbSearchResult(val Title: String?, val Year: String?, val imdbID: String?, val Type: String?, val Poster: String?)
    data class OmdbTitleResponse(val Title: String?, val Year: String?, val Plot: String?, val Poster: String?, val imdbID: String?)

    private fun getRandomApiKey(): String {
        if (activeOmdbKeys.isEmpty()) activeOmdbKeys.addAll(MegaStreamConstants.OMDB_KEYS)
        return activeOmdbKeys[Random.nextInt(activeOmdbKeys.size)]
    }

    // --- PHASE 0: HOMEPAGE (StreamPlay DOM Scraping) ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePageLists = mutableListOf<HomePageList>()
        
        val scrapedResults = mutableListOf<SearchResponse>()
        try {
            val doc = app.get(mainUrl).document
            doc.select(".items .item").forEach { element ->
                val title = element.selectFirst(".title")?.text() ?: return@forEach
                val link = element.selectFirst("a")?.attr("href") ?: return@forEach
                val poster = element.selectFirst("img")?.attr("src") ?: ""
                val fullLink = if (link.startsWith("/")) "$mainUrl$link" else link
                
                // Pack StreamPlay Scraped URL into payload
                val payload = "streamplay://$fullLink"
                scrapedResults.add(newMovieSearchResponse(title, payload) { this.posterUrl = poster })
            }
            
            if (scrapedResults.isNotEmpty()) {
                homePageLists.add(HomePageList("Latest Uploads", scrapedResults, isHorizontalImages = false))
            }
        } catch (e: Exception) {}

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    // --- PHASE 1: HYBRID SEARCH (OMDb + StreamPlay) ---
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val results = mutableListOf<SearchResponse>()

        coroutineScope {
            val omdbSearch = async {
                val apiKey = getRandomApiKey()
                val url = "${MegaStreamConstants.OMDB_BASE_URL}/?apikey=$apiKey&s=$encodedQuery"
                val res = app.get(url, timeout = 3)
                
                if (res.code == 401 || res.text.contains("Limit reached", ignoreCase = true)) {
                    activeOmdbKeys.remove(apiKey)
                    return@async emptyList<SearchResponse>()
                }

                // BYPASSES COMPILER DAEMON CRASH: Using explicit class mapping instead of inline reified generics
                val parsed = AppUtils.mapper.readValue(res.text, OmdbSearchResponse::class.java)
                
                parsed.Search?.filter { it.Poster != "N/A" }?.mapNotNull { item ->
                    val title = item.Title ?: return@mapNotNull null
                    val imdbId = item.imdbID ?: return@mapNotNull null
                    val type = if (item.Type == "series") TvType.TvSeries else TvType.Movie
                    
                    // Pack OMDb payload
                    newMovieSearchResponse(title, "omdb://$imdbId?type=${type.name}", type) {
                        this.posterUrl = item.Poster
                        this.year = item.Year?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                    }
                } ?: emptyList()
            }

            val streamPlaySearch = async {
                try {
                    val doc = app.get("$mainUrl/search?q=$encodedQuery").document
                    doc.select(".search-results .item").mapNotNull { element ->
                        val title = element.selectFirst(".title")?.text() ?: return@mapNotNull null
                        val link = element.selectFirst("a")?.attr("href") ?: return@mapNotNull null
                        val payload = "streamplay://${if (link.startsWith("/")) "$mainUrl$link" else link}"
                        
                        newMovieSearchResponse(title, payload) {
                            this.posterUrl = element.selectFirst("img")?.attr("src")
                            this.year = element.selectFirst(".year")?.text()?.toIntOrNull()
                        }
                    }
                } catch (e: Exception) {
                    emptyList<SearchResponse>()
                }
            }

            results.addAll(omdbSearch.await())
            results.addAll(streamPlaySearch.await())
        }
        
        return results.distinctBy { it.name }
    }

    // --- PHASE 2: LOAD METADATA AND GENERATE EMBED LINKS ---
    override suspend fun load(url: String): LoadResponse? {
        val streamLinks = mutableListOf<String>()
        var resolvedTitle = ""
        var resolvedPoster = ""
        var resolvedPlot = ""
        var resolvedYear: Int? = null
        var tvType = TvType.Movie

        if (url.startsWith("omdb://")) {
            // It's an OMDb API Item. Extract the IMDb ID perfectly suited for hosters.
            val uri = URI(url)
            val imdbId = uri.host
            val typeQuery = uri.query?.split("=")?.get(1) ?: "Movie"
            tvType = if (typeQuery == "TvSeries") TvType.TvSeries else TvType.Movie
            val endpoint = if (tvType == TvType.TvSeries) "tv" else "movie"
            
            // Fetch detailed Plot/Metadata from OMDb
            val apiKey = getRandomApiKey()
            val metaUrl = "${MegaStreamConstants.OMDB_BASE_URL}/?apikey=$apiKey&i=$imdbId&plot=full"
            
            // BYPASSES COMPILER DAEMON CRASH: Using explicit class mapping
            val metaRes = AppUtils.mapper.readValue(app.get(metaUrl).text, OmdbTitleResponse::class.java)
            
            resolvedTitle = metaRes.Title ?: "Unknown"
            resolvedPoster = metaRes.Poster.takeIf { it != "N/A" } ?: ""
            resolvedPlot = metaRes.Plot.takeIf { it != "N/A" } ?: ""
            resolvedYear = metaRes.Year?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

            // Pass the exact IMDb ID into the provider endpoints
            MegaStreamConstants.ID_PROVIDERS.forEach { provider ->
                streamLinks.add("$provider/embed/$endpoint/$imdbId")
            }

        } else if (url.startsWith("streamplay://")) {
            // It's a Scraped StreamPlay DOM Item
            val targetUrl = url.removePrefix("streamplay://")
            val doc = app.get(targetUrl).document
            
            resolvedTitle = doc.selectFirst("h1.title")?.text() ?: return null
            resolvedPoster = doc.selectFirst(".poster img")?.attr("src") ?: ""
            resolvedPlot = doc.selectFirst(".synopsis")?.text() ?: ""
            resolvedYear = doc.selectFirst(".release-year")?.text()?.toIntOrNull()

            // Extract embed iframes natively from StreamPlay
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (MegaStreamConstants.EXTRACTOR_DOMAINS.any { src.contains(it) }) {
                    streamLinks.add(if (src.startsWith("//")) "https:$src" else src)
                }
            }
        }

        val dataPayload = streamLinks.distinct().joinToString(",")

        return newMovieLoadResponse(resolvedTitle, dataPayload, tvType, dataPayload) {
            this.posterUrl = resolvedPoster
            this.plot = resolvedPlot
            this.year = resolvedYear
        }
    }

    // --- PHASE 3: DISTRIBUTED EXTRACTION ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = data.split(",")
        var foundAny = false

        coroutineScope {
            urls.map { targetUrl ->
                async {
                    if (targetUrl.isNotBlank()) {
                        extractors.invokeExtractor(targetUrl, name, subtitleCallback, callback)
                        foundAny = true
                    }
                }
            }.awaitAll()
        }

        return foundAny
    }
}
