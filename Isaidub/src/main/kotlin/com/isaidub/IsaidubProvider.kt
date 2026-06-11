package com.isaidub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document

class IsaidubProvider : MainAPI() {
    override var mainUrl = "https://isaidub.guru"
    override var name = "Isaidub"
    override val hasMainPage = true 
    override var lang = "ta"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

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
    }

    private fun cleanTitleForSearch(title: String): String {
        return title.replace(Regex("(?i)\\b(tamil|dubbed|movie|series|web|hdrip|bdrip|webrip|hd|720p|1080p|mp4|mkv|esub|tcrip|dvdrip|mux|x264|hevc|h264|1cd|2cd|dvd)\\b"), "")
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
        val doc = app.get(mainUrl, headers = mapOf("User-Agent" to userAgent)).document
        val scraped = mutableListOf<SearchResponse>()

        val elements = doc.select("a[href*='/movie/']").take(24)
        coroutineScope {
            elements.map { el ->
                async {
                    val href = el.attr("href")
                    val rawTitle = el.text().trim().substringBefore("(").trim()
                    if (href.isNotBlank() && rawTitle.isNotBlank()) {
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                        val poster = fetchTmdbPoster(rawTitle, null)
                        scraped.add(newMovieSearchResponse(rawTitle, fullUrl, TvType.Movie) {
                            this.posterUrl = poster
                        })
                    }
                }
            }.awaitAll()
        }
        return newHomePageResponse(listOf(HomePageList("Latest Updates", scraped.distinctBy { it.url })))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        // 1. Native Search Form Query
        val searchUrl = "$mainUrl/search.php?find=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val response = app.get(searchUrl, headers = mapOf("User-Agent" to userAgent))
        
        if (response.text.isNotBlank()) {
            val doc = response.document
            val foundElements = doc.select("a[href*='/movie/']")
            if (foundElements.isNotEmpty()) {
                coroutineScope {
                    foundElements.map { el ->
                        async {
                            val href = el.attr("href")
                            val text = el.text().trim()
                            if (href.isNotBlank() && text.isNotBlank() && !href.contains("search.php")) {
                                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                                val cleanTitle = text.substringBefore("(").trim()
                                val poster = fetchTmdbPoster(cleanTitle, null)
                                results.add(newMovieSearchResponse(text, fullUrl, TvType.Movie) {
                                    this.posterUrl = poster
                                })
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        // 2. Fallback: Slug Guessing & Parsing Categories
        if (results.isEmpty()) {
            val cleanQuery = query.replace(Regex("\\b(19|20)\\d{2}\\b"), "").trim()
            val slug = cleanQuery.lowercase().replace(Regex("[^a-z0-9]+"), "-").removeSuffix("-")
            val suffixes = listOf("-tamil-dubbed-movie", "-tamil-dubbed-web-series")
            
            suffixes.forEach { suffix ->
                val guessUrl = "$mainUrl/movie/$slug$suffix/"
                val guessResponse = app.get(guessUrl, headers = mapOf("User-Agent" to userAgent))
                if (guessResponse.text.isNotBlank()) {
                    results.add(newMovieSearchResponse(cleanQuery, guessUrl, TvType.Movie))
                }
            }
        }
        
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val title = doc.selectFirst("title")?.text()?.substringBefore("-")?.trim() ?: "Unknown"
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
        val doc = app.get(data, headers = mapOf("User-Agent" to userAgent)).document
        
        doc.select("a").forEach { el ->
            val href = el.attr("href")
            if (href.contains("/download/page/")) {
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                extractFromDownloadPage(fullUrl, callback)
            } else if (href.contains("/movie/") && !href.endsWith(data)) {
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                val subDoc = app.get(fullUrl).document
                subDoc.select("a[href*=/download/page/]").forEach { subEl ->
                    val subHref = subEl.attr("href")
                    val dlUrl = if (subHref.startsWith("http")) subHref else "$mainUrl$subHref"
                    extractFromDownloadPage(dlUrl, callback)
                }
            }
        }
        return true
    }

    private suspend fun extractFromDownloadPage(url: String, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        
        doc.select("a").forEach { el ->
            val href = el.attr("href")
            if (href.contains("dubmv.top") || href.contains("onestream.watch") || href.contains("uptodub.ch")) {
                val embedUrl = if (href.startsWith("http")) href else "https:$href"
                extractFromEmbed(embedUrl, callback)
            }
        }
    }

    private suspend fun extractFromEmbed(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        val response = app.get(embedUrl, headers = mapOf("Referer" to mainUrl))
        val html = response.text
        
        val doc = org.jsoup.Jsoup.parse(html)
        doc.select("video source, video").firstOrNull()?.attr("src")?.let { src ->
            val isM3u8 = src.contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "Isaidub Direct",
                    url = src,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P720.value
                }
            )
            return
        }

        val unpackedHtml = getAndUnpack(html)
        val searchHtml = unpackedHtml.ifBlank { html }

        val patterns = listOf(
            Regex("[\"']hls[2-4][\"']\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
            Regex("sources\\s*:\\s*\\[\\s*\\{\\s*file\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
            Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*", RegexOption.IGNORE_CASE),
            Regex("https?://[^\\s\"']+\\.mp4[^\\s\"']*", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(searchHtml)
            if (match != null) {
                var videoUrl = match.groupValues.lastOrNull() ?: match.value
                videoUrl = videoUrl.replace("\\", "")
                
                if (videoUrl.contains("google.com") || videoUrl.contains("youtube.com")) continue
                
                val isM3u8 = videoUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Isaidub Embed",
                        url = videoUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P720.value
                    }
                )
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
