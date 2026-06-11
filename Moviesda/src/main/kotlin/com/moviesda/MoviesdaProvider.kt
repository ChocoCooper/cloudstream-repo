package com.moviesda

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Calendar

class MoviesdaProvider : MainAPI() {
    override var mainUrl = "https://moviesda31.com"
    override var name = "Moviesda"
    override val hasMainPage = true 
    override var lang = "ta"
    override val supportedTypes = setOf(TvType.Movie)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

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

    private fun cleanTitleForSearch(title: String): String {
        return title.substringBefore("(").replace(Regex("(?i)\\b(tamil|dubbed|movie|series|web|hdrip|bdrip|webrip|hd|720p|1080p|mp4|mkv|esub|tcrip|dvdrip|mux|x264|hevc|h264|1cd|2cd|dvd)\\b"), "")
            .replace(Regex("[^a-zA-Z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private suspend fun fetchTmdbPoster(title: String, year: Int?): String? {
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
                    val posterPath = results.first().poster_path
                    if (!posterPath.isNullOrBlank()) {
                        return "https://image.tmdb.org/t/p/w500$posterPath"
                    }
                }
                break 
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl.proxify(), headers = mapOf("User-Agent" to userAgent)).document
        val scraped = mutableListOf<SearchResponse>()
        val elements = doc.select("a[href*=-tamil-movie], a[href*=-movie/]").take(24)

        coroutineScope {
            elements.map { el ->
                async {
                    val href = el.attr("href")
                    val rawTitle = el.text().trim()
                    if (href.isNotBlank() && rawTitle.isNotBlank() && !href.contains("/tamil-movies/")) {
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                        val cleanTitle = cleanTitleForSearch(rawTitle)
                        val poster = fetchTmdbPoster(cleanTitle, null)
                        scraped.add(newMovieSearchResponse(rawTitle, fullUrl, TvType.Movie) {
                            this.posterUrl = poster
                        })
                    }
                }
            }.awaitAll()
        }
        return newHomePageResponse(listOf(HomePageList("Latest Uploads", scraped.distinctBy { it.url })))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.replace(Regex("\\b(19|20)\\d{2}\\b"), "").trim()
        val slug = cleanQuery.lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), "-")
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        
        // 1. Direct URL Guessing
        val yearsToTry = listOf(currentYear, currentYear - 1, currentYear + 1, currentYear - 2)
        for (year in yearsToTry) {
            val directUrl = "$mainUrl/$slug-$year-tamil-movie/"
            val response = app.get(directUrl.proxify(), headers = mapOf("User-Agent" to userAgent))
            if (response.text.contains("movie")) {
                val poster = fetchTmdbPoster(cleanQuery, year)
                results.add(newMovieSearchResponse("$cleanQuery ($year)", directUrl, TvType.Movie) {
                    this.posterUrl = poster
                })
                break 
            }
        }

        // 2. Category Browsing Fallback
        if (results.isEmpty()) {
            val categoriesToCheck = listOf(
                "$mainUrl/tamil-$currentYear-movies/",
                "$mainUrl/tamil-${currentYear - 1}-movies/"
            )

            for (categoryUrl in categoriesToCheck) {
                val response = app.get(categoryUrl.proxify(), headers = mapOf("User-Agent" to userAgent))
                if(response.text.isNotBlank()) {
                    val doc = response.document
                    val items = doc.select("a[href*=-tamil-movie], a[href*=-movie/]")
                    coroutineScope {
                        items.map { el ->
                            async {
                                val href = el.attr("href")
                                val text = el.text().trim()
                                if (href.isNotBlank() && text.contains(cleanQuery, ignoreCase = true) && !href.contains("/tamil-movies/")) {
                                    val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                                    val poster = fetchTmdbPoster(text, null)
                                    results.add(newMovieSearchResponse(text, fullUrl, TvType.Movie) {
                                        this.posterUrl = poster
                                    })
                                }
                            }
                        }.awaitAll()
                    }
                }
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url.proxify(), headers = mapOf("User-Agent" to userAgent)).document
        val title = doc.selectFirst("title")?.text()?.substringBefore("-")?.trim() ?: "Unknown Movie"
        val year = Regex("\\b(19|20)\\d{2}\\b").find(title)?.value?.toIntOrNull()
        val poster = fetchTmdbPoster(title, year)
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        drillDownForLinks(data, callback)
        return true
    }

    private suspend fun drillDownForLinks(url: String, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url.proxify(), headers = mapOf("User-Agent" to userAgent)).document

        val originalLink = doc.selectFirst("a[href*=-original-movie]")?.attr("href")
        if (originalLink != null) {
            val fullOriginalUrl = if (originalLink.startsWith("http")) originalLink else "$mainUrl$originalLink"
            drillDownForLinks(fullOriginalUrl, callback)
            return
        }

        val qualityLinks = doc.select("a").filter { 
            it.text().contains(Regex("\\b(360p|480p|720p|1080p|4K)\\s*HD\\b", RegexOption.IGNORE_CASE)) 
        }
        if (qualityLinks.isNotEmpty()) {
            qualityLinks.forEach { el ->
                val href = el.attr("href")
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                drillDownForLinks(fullUrl, callback)
            }
            return
        }

        val downloadLinks = doc.select("a[href*=/download/]")
        if (downloadLinks.isNotEmpty()) {
            downloadLinks.forEach { el ->
                val href = el.attr("href")
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                extractFinalDownloadUrl(fullUrl, callback)
            }
        }
    }

    private suspend fun extractFinalDownloadUrl(url: String, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url.proxify(), headers = mapOf("User-Agent" to userAgent)).document
      
        doc.select("a").forEach { el ->
            val href = el.attr("href")
            val text = el.text().lowercase()

            if (href.isNotBlank() && !href.contains("moviesda15.com") && !href.startsWith("#") && 
                (text.contains("download") || text.contains("server"))) {
                
                val fullUrl = if (href.startsWith("http")) href else "https:$href"
                var targetUrl = fullUrl

                val fileIdMatch = Regex("/file/(\\d+)").find(targetUrl)
                if (targetUrl.contains("download.moviespage.xyz") && fileIdMatch != null) {
                    targetUrl = "https://play.onestream.watch/stream/page/${fileIdMatch.groupValues[1]}"
                }

                extractFromEmbed(targetUrl, callback)
            }
        }
    }

    private suspend fun extractFromEmbed(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        val response = app.get(embedUrl.proxify(), headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent))
        val html = response.text

        // 1. Check for standard video tags
        val doc = org.jsoup.Jsoup.parse(html)
        doc.select("video source").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank()) {
                val isM3u8 = src.contains(".m3u8")
                callback.invoke(newExtractorLink(
                    source = this.name,
                    name = "Moviesda Direct",
                    url = src,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P720.value
                })
                return
            }
        }

        // 2. Unpack Packer obfuscation if present
        val unpackedHtml = getAndUnpack(html).ifBlank { html }

        // 3. Regex fallback matching extra JS patterns
        val patterns = listOf(
            Regex("[\"']hls[2-4]?[\"']\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
            Regex("sources\\s*:\\s*\\[\\s*\\{\\s*file\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
            Regex("file[\"']?\\s*:\\s*[\"'](https?://[^\"']+\\.m3u8[^\"']*)[\"']", RegexOption.IGNORE_CASE),
            Regex("file[\"']?\\s*:\\s*[\"'](https?://[^\"']+\\.mp4[^\"']*)[\"']", RegexOption.IGNORE_CASE),
            Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*", RegexOption.IGNORE_CASE),
            Regex("https?://[^\\s\"']+\\.mp4[^\\s\"']*", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(unpackedHtml)
            if (match != null) {
                var videoUrl = match.groupValues.lastOrNull() ?: match.value
                videoUrl = videoUrl.replace("\\", "")
                
                if (videoUrl.contains("google.com") || videoUrl.contains("youtube.com")) continue
                
                val isM3u8 = videoUrl.contains(".m3u8")
                callback.invoke(newExtractorLink(
                    source = this.name,
                    name = "Moviesda Embed",
                    url = videoUrl, // Explicitly DO NOT proxify the raw video link for Exoplayer
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P720.value
                })
                break
            }
        }
    }

    data class TmdbSearchResponse(
        @JsonProperty("results") val results: List<TmdbMovie>?
    )

    data class TmdbMovie(
        @JsonProperty("poster_path") val poster_path: String?
    )
}
