package com.tamilmv

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
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
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

class TamilMVProvider : MainAPI() {
    override var mainUrl = "https://www.1tamilmv.cards" // Domain Restored
    override var name = "TamilMV"
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class StrmupResponse(
        @JsonProperty("streaming_url") val streamingUrl: String?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl, headers = mapOf("User-Agent" to userAgent)).document
        val scraped = mutableListOf<SearchResponse>()

        val elements = doc.select("a").filter { it.text().contains("[WATCH]") }.take(20)
        coroutineScope {
            elements.map { el ->
                async {
                    val watchUrl = el.attr("href")
                    if (watchUrl.isNotBlank()) {
                        val parsedTitle = el.text().substringBefore("[WATCH]").trim()
                        val cleanTitle = cleanTitleForSearch(parsedTitle)
                        val poster = fetchTmdbPoster(cleanTitle, null)
                        scraped.add(newMovieSearchResponse(parsedTitle, watchUrl, TvType.Movie) {
                            this.posterUrl = poster
                        })
                    }
                }
            }.awaitAll()
        }
        return newHomePageResponse(listOf(HomePageList("Latest Streams", scraped.distinctBy { it.url })))
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        
        val searchUrl = "$mainUrl/index.php?/search/&q=${java.net.URLEncoder.encode(query, "UTF-8")}&type=forums_topic&search_in=titles&sortby=relevancy"
        val response = app.get(searchUrl, headers = mapOf("User-Agent" to userAgent))
        
        if (response.text.isNotBlank()) {
            val doc = response.document
            val streamItems = doc.select(".ipsStreamItem_title a, a[data-searchable]")
            if (streamItems.isNotEmpty()) {
                coroutineScope {
                    streamItems.map { el ->
                        async {
                            val url = el.attr("href")
                            val text = el.text().trim()
                            if (url.isNotBlank() && text.isNotBlank() && !url.contains("/profile/")) {
                                val cleanTitle = cleanTitleForSearch(text)
                                val poster = fetchTmdbPoster(cleanTitle, null)
                                results.add(newMovieSearchResponse(cleanTitle, url, TvType.Movie) {
                                    this.posterUrl = poster
                                })
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        if (results.isEmpty()) {
            val cleanQuery = query.lowercase().replace(Regex("[^a-z0-9]"), "")
            val doc = app.get(mainUrl, headers = mapOf("User-Agent" to userAgent)).document

            doc.select("a").filter { it.text().contains("[WATCH]") }.forEach { el ->
                val watchUrl = el.attr("href")
                if (watchUrl.isBlank()) return@forEach

                var titleText = ""
                var curr: org.jsoup.nodes.Node? = el.previousSibling()
                
                if (curr == null && el.parent() != null) {
                    curr = el.parent()?.previousSibling()
                }

                while (curr != null) {
                    val nodeName = curr.nodeName().lowercase()
                    if (nodeName == "br" || nodeName == "p" || nodeName == "hr" || nodeName == "div") break
                    
                    if (curr is TextNode) {
                        if (curr.text().contains("[WATCH]")) break
                        titleText = curr.text() + titleText
                    } else if (curr is Element) {
                        if (curr.text().contains("[WATCH]")) break
                        titleText = curr.text() + titleText
                    }
                    curr = curr.previousSibling()
                }

                val cleanTitle = titleText.replace(Regex("^[- \t\n\r|\\[\\], \u00A0]+"), "")
                                          .replace(Regex("[- \t\n\r|\\[\\], \u00A0]+$"), "").trim()

                val normalizedTitle = cleanTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
                
                if (cleanTitle.isNotBlank() && (normalizedTitle.contains(cleanQuery) || cleanQuery.contains(normalizedTitle))) {
                    results.add(newMovieSearchResponse(
                        name = cleanTitle.split(" - ").first().trim(), 
                        url = watchUrl, 
                        type = TvType.Movie
                    ))
                }
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val titleMatch = url.trimEnd('/').split("/").lastOrNull()?.replace("-", " ") ?: "TamilMV Stream"
        val year = Regex("\\b(19|20)\\d{2}\\b").find(titleMatch)?.value?.toIntOrNull()
        val poster = fetchTmdbPoster(titleMatch, year)

        return newMovieLoadResponse(titleMatch, url, TvType.Movie, url) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        extractDirectStream(data, callback)
        return true
    }

    private suspend fun extractDirectStream(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        val hostname = embedUrl.lowercase()
        
        if (hostname.contains("strmup")) {
            extractFromStrmup(embedUrl, callback)
        } else {
            extractFromGenericEmbed(embedUrl, callback)
        }
    }

    private suspend fun extractFromStrmup(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        val filecode = embedUrl.trimEnd('/').split("/").lastOrNull { it.isNotBlank() } ?: return
        
        val uri = java.net.URI(embedUrl)
        val host = "${uri.scheme}://${uri.host}"
        val ajaxUrl = "$host/ajax/stream?filecode=$filecode"

        val response = app.get(
            ajaxUrl, 
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to embedUrl,
                "User-Agent" to userAgent
            )
        ).parsedSafe<StrmupResponse>()

        response?.streamingUrl?.let { directUrl ->
            val isM3u8 = directUrl.contains(".m3u8")
            callback.invoke(newExtractorLink(
                source = this.name,
                name = "Strmup",
                url = directUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
            })
        }
    }

    private suspend fun extractFromGenericEmbed(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        val uri = java.net.URI(embedUrl)
        val embedBase = "${uri.scheme}://${uri.host}"
        
        var responseRes = app.get(embedUrl, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent))
        var html = responseRes.text

        if (html.contains("<title>Loading...</title>") || html.contains("Page is loading")) {
            val mirrors = listOf("yuguaab.com", "cavanhabg.com")
            for (mirror in mirrors) {
                if (uri.host.contains(mirror)) continue
                
                val mirrorUrl = embedUrl.replace(uri.host, mirror)
                val mirrorRes = app.get(mirrorUrl, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent))
                val mirrorHtml = mirrorRes.text
                
                if (mirrorHtml.contains("jwplayer") || mirrorHtml.contains("sources") || mirrorHtml.contains("eval(function(p,a,c,k,e,d)")) {
                    html = mirrorHtml
                    break
                }
            }
        }

        val unpackedHtml = getAndUnpack(html).ifBlank { html }

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
                
                if (videoUrl.startsWith("/") && !videoUrl.startsWith("//")) {
                    videoUrl = embedBase + videoUrl
                }
                
                val isM3u8 = videoUrl.contains(".m3u8")
                callback.invoke(newExtractorLink(
                    source = this.name,
                    name = "TamilMV Embed",
                    url = videoUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P720.value
                })
                break 
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbSearchResponse(
        @JsonProperty("results") val results: List<TmdbMovie>?
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class TmdbMovie(
        @JsonProperty("poster_path") val poster_path: String?
    )
}
