package com.megastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.net.URLEncoder

class MegaStreamProvider : MainAPI() {
    override var mainUrl = "https://www.omdbapi.com"
    override var name = "MegaStream"
    override val hasMainPage = true 
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "en"

    private val omdbKeys = listOf(
        "4b447405", "eb0c0475", "7776cbde", "ff28f90b",
        "6c3a2d45", "b07b58c8", "ad04b643", "a95b5205",
        "777d9323", "2c2c3314", "b5cff164", "89a9f57d",
        "73a9858a", "efbd8357"
    )

    // OMDb JSON Data Models
    data class OmdbSearchResponse(val Search: List<OmdbSearchResult>?, val Response: String?)
    data class OmdbSearchResult(val Title: String?, val Year: String?, val imdbID: String?, val Type: String?, val Poster: String?)
    data class OmdbTitleResponse(val Title: String?, val Year: String?, val Plot: String?, val Poster: String?, val imdbID: String?)

    private fun getRandomApiKey(): String {
        return omdbKeys.random()
    }

    // --- PHASE 0: HOMEPAGE (Pure OMDb Generation) ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        val queries = listOf(
            Pair("Trending Action 2024", "Action"),
            Pair("Popular Sci-Fi", "Sci-Fi"),
            Pair("Comedy Hits", "Comedy"),
            Pair("Latest Thrillers", "Thriller")
        )

        coroutineScope {
            queries.forEach { (title, query) ->
                launch {
                    try {
                        val apiKey = getRandomApiKey()
                        val encodedQuery = URLEncoder.encode(query, "UTF-8")
                        val url = "$mainUrl/?apikey=$apiKey&s=$encodedQuery&type=movie&y=2024"
                        
                        val res = app.get(url, timeout = 5).text
                        // FIXED: Reverted to native tryParseJson
                        val parsed = AppUtils.tryParseJson<OmdbSearchResponse>(res)
                        
                        val items = parsed?.Search?.filter { it.Poster != "N/A" }?.mapNotNull { item ->
                            val imdbId = item.imdbID ?: return@mapNotNull null
                            newMovieSearchResponse(item.Title ?: "Unknown", "omdb://$imdbId", TvType.Movie) {
                                this.posterUrl = item.Poster
                                this.year = item.Year?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                            }
                        } ?: emptyList()

                        if (items.isNotEmpty()) {
                            lists.add(HomePageList(title, items, isHorizontalImages = false))
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    // --- PHASE 1: SEARCH (100% OMDb Powered) ---
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val apiKey = getRandomApiKey()
        val url = "$mainUrl/?apikey=$apiKey&s=$encodedQuery&type=movie"
        
        return try {
            val res = app.get(url, timeout = 5).text
            // FIXED: Reverted to native tryParseJson
            val parsed = AppUtils.tryParseJson<OmdbSearchResponse>(res)
            
            parsed?.Search?.filter { it.Poster != "N/A" }?.mapNotNull { item ->
                val title = item.Title ?: return@mapNotNull null
                val imdbId = item.imdbID ?: return@mapNotNull null
                
                newMovieSearchResponse(title, "omdb://$imdbId", TvType.Movie) {
                    this.posterUrl = item.Poster
                    this.year = item.Year?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- PHASE 2: LOAD METADATA AND GENERATE EMBED LINKS ---
    override suspend fun load(url: String): LoadResponse? {
        if (!url.startsWith("omdb://")) return null

        val uri = URI(url)
        val imdbId = uri.host ?: return null
        
        val apiKey = getRandomApiKey()
        val metaUrl = "$mainUrl/?apikey=$apiKey&i=$imdbId&plot=full"
        
        // FIXED: Reverted to native tryParseJson
        val metaRes = AppUtils.tryParseJson<OmdbTitleResponse>(app.get(metaUrl).text) ?: return null
        
        val resolvedTitle = metaRes.Title ?: "Unknown"
        val resolvedPoster = metaRes.Poster?.takeIf { it != "N/A" } ?: ""
        val resolvedPlot = metaRes.Plot?.takeIf { it != "N/A" } ?: ""
        val resolvedYear = metaRes.Year?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        // Generate links for highly reliable, anti-block hoster aggregators
        val embedUrls = listOf(
            "https://autoembed.co/movie/imdb/$imdbId",
            "https://multiembed.mov/directstream.php?video_id=$imdbId",
            "https://vidsrc.net/embed/movie/$imdbId",
            "https://vidsrc.cc/v2/embed/movie/$imdbId",
            "https://2embed.cc/embed/$imdbId"
        )

        val dataPayload = embedUrls.joinToString(",")

        return newMovieLoadResponse(resolvedTitle, url, TvType.Movie, dataPayload) {
            this.posterUrl = resolvedPoster
            this.plot = resolvedPlot
            this.year = resolvedYear
        }
    }

    // --- PHASE 3: DELEGATED EXTRACTION ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = data.split(",")
        var foundAny = false

        coroutineScope {
            urls.forEach { embedUrl ->
                launch {
                    try {
                        val doc = app.get(embedUrl, timeout = 15).document
                        
                        doc.select("iframe").forEach { iframe ->
                            val src = iframe.attr("src")
                            val finalSrc = if (src.startsWith("//")) "https:$src" else src
                            
                            if (finalSrc.isNotBlank() && finalSrc.startsWith("http")) {
                                // Delegate decryption entirely to Cloudstream's native core extractors
                                val success = loadExtractor(finalSrc, embedUrl, subtitleCallback, callback)
                                if (success) {
                                    foundAny = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Silently ignore blocked domains
                    }
                }
            }
        }

        return foundAny
    }
}
