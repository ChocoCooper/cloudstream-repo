package com.dubstamil

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLDecoder
import java.net.URLEncoder

// ============================================================
// DATA CLASSES (TMDB)
// ============================================================

data class TmdbSearchResponse(@JsonProperty("results") val results: List<TmdbMovie>?)
data class TmdbMovie(
    @JsonProperty("title") val title: String?,
    @JsonProperty("release_date") val release_date: String?,
    @JsonProperty("poster_path") val poster_path: String?,
    @JsonProperty("overview") val overview: String?,
    @JsonProperty("vote_count") val vote_count: Int?
)
data class ScrapedMovie(val title: String, val link: String)

// ============================================================
// PROVIDER
// ============================================================

class IsaidubProvider : MainAPI() {

    override var mainUrl        = "https://isaidub.guru"
    override var name           = "DubsTamil"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang           = "ta"
    override val hasMainPage    = true

    // ── Semaphores ───────────────────────────────────────────
    private val requestSemaphore = Semaphore(3)
    private val tmdbApiKey = "fb7bb23f03b6994dafc674c074d01761"
    private val imageBase = "https://image.tmdb.org/t/p/w500"

    override val mainPage = mainPageOf(
        "/" to "Latest Updates",
        "/tamil-dubbed-movies.html" to "Tamil Dubbed Movies",
        "/tamil-movies.html" to "Tamil Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl${request.data}" else "$mainUrl${request.data}?page=$page"
        val document = app.get(url).document

        val items = document.select("div.f, div.f1").mapNotNull {
            val a = it.selectFirst("a") ?: return@mapNotNull null
            val title = a.text().trim()
            val href = fixUrl(a.attr("href"))
            if (title.isBlank() || href.isBlank()) return@mapNotNull null

            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = ""
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val tmdbUrl = "https://api.tmdb.org/3/search/movie?api_key=$tmdbApiKey&query=$encodedQuery&language=ta"

        // FIX: Manual JSON parsing to bypass Kotlin Compiler ICE!
        // This prevents the "Couldn't load KotlinClass" crash in GitHub actions.
        val responseText = app.get(tmdbUrl).text
        val parsed = try {
            AppUtils.mapper.readValue(responseText, TmdbSearchResponse::class.java)
        } catch (e: Exception) {
            null
        }

        val searchResults = mutableListOf<SearchResponse>()
        
        parsed?.results?.forEach { result ->
            val title = result.title ?: return@forEach
            val urlPath = "$mainUrl/search.php?q=${URLEncoder.encode(title, "UTF-8")}"
            val poster = result.poster_path?.let { "$imageBase$it" }

            searchResults.add(
                newMovieSearchResponse(title, urlPath, TvType.Movie) {
                    this.posterUrl = poster
                }
            )
        }

        // Fallback to website search if TMDB returns nothing
        if (searchResults.isEmpty()) {
            val fallbackUrl = "$mainUrl/search.php?q=$encodedQuery"
            val document = app.get(fallbackUrl).document
            document.select("div.f, div.f1").forEach {
                val a = it.selectFirst("a") ?: return@forEach
                val title = a.text().trim()
                val href = fixUrl(a.attr("href"))
                if (title.isNotBlank() && href.isNotBlank()) {
                    searchResults.add(newMovieSearchResponse(title, href, TvType.Movie))
                }
            }
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val title = if (url.contains("q=")) {
            url.substringAfter("q=").substringBefore("&").let { URLDecoder.decode(it, "UTF-8") }
        } else {
            app.get(url).document.selectFirst("title")?.text()?.replace("Download", "")?.trim() ?: "Tamil Dubbed Movie"
        }
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.plot = "Isaidub Movie: $title"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val links = extractDownloadLinks(document, mainUrl)

        coroutineScope {
            links.map { (text, link) ->
                async {
                    requestSemaphore.withPermit {
                        try {
                            val childDoc = app.get(link).document
                            val directLinks = extractDownloadLinks(childDoc, mainUrl)
                            
                            directLinks.forEach { (directText, directLink) ->
                                if (directLink.endsWith(".mp4") || directLink.endsWith(".mkv") || directLink.endsWith(".avi")) {
                                    // FIX: Uses clean ExtractorLink format to clear out Gradle warnings
                                    callback.invoke(
                                        ExtractorLink(
                                            source = "Isaidub",
                                            name = "Isaidub $directText",
                                            url = directLink,
                                            referer = mainUrl,
                                            quality = Qualities.P720.value,
                                            type = ExtractorLinkType.VIDEO
                                        )
                                    )
                                }
                            }
                        } catch (e: Exception) {}
                    }
                }
            }.awaitAll()
        }
        return true
    }

    private fun extractDownloadLinks(
        doc: org.jsoup.nodes.Document,
        baseUrl: String
    ): List<Pair<String, String>> =
        doc.select("a[href]").mapNotNull { a ->
            val text = a.text().trim()
            val href = a.attr("href")
            if (text.isBlank() || href.isBlank()) return@mapNotNull null
            val full = resolveUrl(baseUrl, href)
            val low  = full.lowercase()
            val lowT = text.lowercase()
            when {
                "download server" in lowT || "download" in lowT              -> Pair(text, full)
                low.endsWith(".mp4") || low.endsWith(".mkv")
                                     || low.endsWith(".avi")                 -> Pair(text, full)
                "download.php" in low || "dl.php" in low                     -> Pair(text, full)
                listOf("dubpage.xyz", "dubmv.xyz", "dub.uptodub.ch")
                    .any { it in low }                                        -> Pair(text, full)
                else -> null
            }
        }.distinctBy { it.second }

    private fun resolveUrl(base: String, href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//")   -> "https:$href"
        href.startsWith("/")    -> {
            val u = android.net.Uri.parse(base)
            "${u.scheme}://${u.host}$href"
        }
        else -> "${base.trimEnd('/')}/${href.trimStart('/')}"
    }
}
