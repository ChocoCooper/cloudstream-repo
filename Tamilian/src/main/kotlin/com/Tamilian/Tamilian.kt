package com.Tamilian

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Locale

class Tamilian : MainAPI() {
    override var name = "Tamilian"
    override var mainUrl = "https://tamilian.io"
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(TvType.Movie)

    companion object {
        private const val TMDB_API = "https://api.tmdb.org/3"
        private val TMDB_KEYS = listOf(
            "fb7bb23f03b6994dafc674c074d01761",
            "e55425032d3d0f371fc776f302e7c09b",
            "8301a21598f8b45668d5711a814f01f6",
            "8cf43ad9c085135b9479ad5cf6bbcbda",
            "da63548086e399ffc910fbc08526df05"
        )
        
        // Remote Proxylist to bypass ISP Blocks
        private val proxies = listOf(
            "https://ancient-violet-1ee6.phisher12.workers.dev",
            "https://autumn-limit-1fea.phisher53.workers.dev",
            "https://wispy-bar-8fbe.phisher2.workers.dev",
            "https://orange-voice-abcf.phisher16.workers.dev",
            "https://icy-king-bff2.phisher40.workers.dev"
        )
    }

    private fun String.proxify(): String {
        if (this.contains("tmdb.org") || this.contains("workers.dev")) return this
        return "${proxies.random()}/$this"
    }

    private fun String.toAbsoluteUrl(): String {
        if (this.isBlank()) return ""
        if (this.startsWith("http")) return this
        if (this.startsWith("//")) return "https:$this"
        return if (this.startsWith("/")) "$mainUrl$this" else "$mainUrl/$this"
    }

    private fun cleanTitleFromUrl(url: String): String {
        val slug = url.trimEnd('/').split("/").last()
        val withoutId = slug.replace(Regex("-[a-f0-9]{7,}\$"), "")
        return withoutId.replace("-", " ").split(" ").joinToString(" ") { 
            it.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString() } 
        }
    }

    private fun cleanTitleForSearch(title: String): String {
        return title.substringBefore("(").replace(Regex("(?i)\\b(tamil|dubbed|movie|series|web|hdrip|bdrip|webrip|hd|720p|1080p|mp4|mkv|esub|tcrip|dvdrip|mux|x264|hevc|h264|1cd|2cd|dvd)\\b"), "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun fetchTmdbPoster(title: String, year: Int?, fallbackPoster: String?): String? {
        val cleanTitle = cleanTitleForSearch(title)
        for (key in TMDB_KEYS) {
            try {
                val baseUrl = "$TMDB_API/search/movie?api_key=$key&query=${java.net.URLEncoder.encode(cleanTitle, "UTF-8")}"
                var results = emptyList<TmdbMovie>()

                if (year != null) {
                    val yearRes = app.get("$baseUrl&primary_release_year=$year").parsedSafe<TmdbSearchResponse>()
                    if (yearRes?.results?.isNotEmpty() == true) {
                        results = yearRes.results
                    }
                }
                
                if (results.isEmpty()) {
                    val genericRes = app.get(baseUrl).parsedSafe<TmdbSearchResponse>()
                    results = genericRes?.results ?: emptyList()
                }

                if (results.isNotEmpty()) {
                    val bestMatch = results.firstOrNull { it.original_language == "ta" } ?: results.first()
                    val posterPath = bestMatch.poster_path
                    
                    if (!posterPath.isNullOrBlank()) {
                        return "https://image.tmdb.org/t/p/w500$posterPath"
                    }
                }
                break 
            } catch (e: Exception) {
                continue
            }
        }
        return fallbackPoster
    }

    private data class ScrapedMovie(val title: String, val url: String, val nativePoster: String?, val year: Int?)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get("$mainUrl/home/".proxify()).document
        
        val scrapedMovies = doc.select("a[href*='/movie/']").mapNotNull { element ->
            val href = element.attr("href")
            if (href.contains("watching.html") || href.isBlank()) return@mapNotNull null
            
            val url = href.toAbsoluteUrl()
            val title = cleanTitleFromUrl(url)
            
            val card = element.parents().firstOrNull { it.hasClass("ml-item") }
            val img = element.selectFirst("img") ?: card?.selectFirst("img")
            val nativePoster = img?.attr("data-original")?.takeIf { it.isNotBlank() } 
                ?: img?.attr("src")?.takeIf { !it.startsWith("data:image") }

            val year = card?.selectFirst(".mi-meta span")?.text()?.toIntOrNull()

            ScrapedMovie(title, url, nativePoster?.toAbsoluteUrl(), year)
        }.distinctBy { it.title }

        val movies = coroutineScope {
            scrapedMovies.map { movie ->
                async {
                    val finalPoster = fetchTmdbPoster(movie.title, movie.year, movie.nativePoster)
                    newMovieSearchResponse(
                        name = movie.title,
                        url = movie.url,
                        type = TvType.Movie
                    ) {
                        this.posterUrl = finalPoster
                        this.year = movie.year
                    }
                }
            }.awaitAll()
        }.distinctBy { 
            if (it.posterUrl?.contains("tmdb.org") == true) it.posterUrl else it.url 
        }

        return newHomePageResponse(listOf(HomePageList("Latest Movies", movies)))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search/$query".proxify()).document
        
        val scrapedMovies = doc.select("a[href*='/movie/']").mapNotNull { element ->
            val href = element.attr("href")
            if (href.contains("watching.html") || href.isBlank()) return@mapNotNull null
            
            val url = href.toAbsoluteUrl()
            val title = cleanTitleFromUrl(url)
            
            val card = element.parents().firstOrNull { it.hasClass("ml-item") }
            val img = element.selectFirst("img") ?: card?.selectFirst("img")
            val nativePoster = img?.attr("data-original")?.takeIf { it.isNotBlank() } 
                ?: img?.attr("src")?.takeIf { !it.startsWith("data:image") }

            val year = card?.selectFirst(".mi-meta span")?.text()?.toIntOrNull()

            ScrapedMovie(title, url, nativePoster?.toAbsoluteUrl(), year)
        }.distinctBy { it.title }

        val movies = coroutineScope {
            scrapedMovies.map { movie ->
                async {
                    val finalPoster = fetchTmdbPoster(movie.title, movie.year, movie.nativePoster)
                    newMovieSearchResponse(
                        name = movie.title,
                        url = movie.url,
                        type = TvType.Movie
                    ) {
                        this.posterUrl = finalPoster
                        this.year = movie.year
                    }
                }
            }.awaitAll()
        }.distinctBy { 
            if (it.posterUrl?.contains("tmdb.org") == true) it.posterUrl else it.url 
        }

        return movies
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url.proxify()).document
        
        val title = doc.selectFirst(".mvic-desc h3")?.text() ?: cleanTitleFromUrl(url)
        val plot = doc.selectFirst(".desc")?.text()
        val yearText = doc.selectFirst(".mvici-right p:contains(Release:) a")?.text()
        val year = yearText?.split("-")?.firstOrNull()?.toIntOrNull()
        
        val styleAttr = doc.selectFirst(".mvic-thumb")?.attr("style")
        val nativePoster = styleAttr?.let { Regex("""url\((.*?)\)""").find(it)?.groupValues?.get(1) }?.toAbsoluteUrl()

        val finalPoster = fetchTmdbPoster(title, year, nativePoster)

        return newMovieLoadResponse(
            name = title,
            url = url,
            type = TvType.Movie,
            dataUrl = url 
        ) {
            this.posterUrl = finalPoster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = if (data.endsWith("/")) "${data}watching.html" else "$data/watching.html"
        val baseHeaders = mapOf("Referer" to data)
        
        val watchRes = app.get(watchUrl.proxify(), headers = baseHeaders).text
        val movieIdMatch = Regex("""movie\s*=\s*\{[^}]*id:\s*"(\d+)"""").find(watchRes)
        val movieId = movieIdMatch?.groupValues?.get(1) ?: return false

        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to watchUrl
        )

        val serverApiUrl = "$mainUrl/ajax/movie/episode/servers/${movieId}_1_full"
        val servRes = app.get(serverApiUrl.proxify(), headers = ajaxHeaders)
        if (servRes.text.isBlank()) return false

        val serverBtns = servRes.document.select("a[data-id]")
        if (serverBtns.isEmpty()) return false

        val targetBtn = serverBtns.firstOrNull { it.text().contains("MegaCloud", true) } 
            ?: serverBtns.firstOrNull() 
            ?: return false
        
        val cleanDataId = targetBtn.attr("data-id").replace("\"", "").replace("\\", "").replace("'", "")
        val cleanDataName = targetBtn.attr("data-name").replace("\"", "").replace("\\", "").replace("'", "")

        val fullDataId = if (cleanDataName.isNotBlank() && !cleanDataId.endsWith(cleanDataName)) {
            "${cleanDataId}_${cleanDataName}"
        } else {
            cleanDataId
        }

        val sourcesUrl = "$mainUrl/ajax/movie/episode/server/sources/$fullDataId"
        val sourceRes = app.get(sourcesUrl.proxify(), headers = ajaxHeaders)
        if (sourceRes.text.isBlank()) return false

        var finalLink: String? = null
        try { finalLink = sourceRes.parsedSafe<LinkResponse>()?.link } catch (e: Exception) {}

        if (finalLink == null) {
            val match = Regex("""(https?://(?:embedojo\.net|megacloud\.[a-z]+)/[^\s"\'<>\\]+)""").find(sourceRes.text)
            finalLink = match?.groupValues?.get(1)?.replace("\\", "")
        }

        if (finalLink != null) {
            val token = finalLink.substringAfterLast("/")
            val embedHost = if (finalLink.contains("megacloud")) "https://megacloud.tv" else "https://embedojo.net"
            
            val postHeaders = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to finalLink,
                "Origin" to embedHost,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )

            val videoDataRes = app.post(
                "$embedHost/player/index.php?data=$token&do=getVideo".proxify(),
                headers = postHeaders
            )

            val parsedData = videoDataRes.parsedSafe<VideoData>()
            var sourceUrl = parsedData?.videoSource

            if (sourceUrl.isNullOrBlank()) {
                val match = Regex(""""videoSource"\s*:\s*"([^"]+)"""").find(videoDataRes.text)
                sourceUrl = match?.groupValues?.get(1)?.replace("\\", "")
            }

            parsedData?.tracks?.forEach { track ->
                if (track.kind == "captions" && !track.file.isNullOrBlank()) {
                    subtitleCallback.invoke(
                        newSubtitleFile(track.label ?: "Subtitles", track.file)
                    )
                }
            }

            val playerHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                "Referer" to finalLink,
                "Origin" to embedHost,
                "Accept" to "*/*"
            )

            if (!sourceUrl.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name = "Tamilian HD",
                        source = "Tamilian",
                        url = sourceUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = finalLink
                        this.headers = playerHeaders 
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }
        }

        return false
    }

    data class LinkResponse(
        @JsonProperty("link") val link: String?
    )

    data class Track(
        @JsonProperty("file") val file: String?,
        @JsonProperty("label") val label: String?,
        @JsonProperty("kind") val kind: String?
    )

    data class VideoData(
        @JsonProperty("videoSource") val videoSource: String?,
        @JsonProperty("tracks") val tracks: List<Track>?
    )

    data class TmdbSearchResponse(
        @JsonProperty("results") val results: List<TmdbMovie>?
    )

    data class TmdbMovie(
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("original_language") val original_language: String?
    )
}
