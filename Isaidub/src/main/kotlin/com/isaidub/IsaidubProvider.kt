package com.isaidub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.nodes.Document

class IsaidubProvider : MainAPI() {
    override var mainUrl = "https://isaidub.guru"
    override var name = "Isaidub"
    override val hasMainPage = true
    override var lang = "ta" // Tamil
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.replace(Regex("\\b(19|20)\\d{2}\\b"), "").trim()
        val slug = cleanQuery.lowercase().replace(Regex("[^a-z0-9]+"), "-").removeSuffix("-")

        // 1. Slug Guessing
        val suffixes = listOf("-tamil-dubbed-movie", "-tamil-dubbed-web-series")
        suffixes.forEach { suffix ->
            val guessUrl = "$mainUrl/movie/$slug$suffix/"
            val response = app.get(guessUrl, headers = mapOf("User-Agent" to userAgent))
            if (response.isSuccessful) {
                results.add(newMovieSearchResponse(
                    name = cleanQuery, 
                    url = guessUrl, 
                    type = TvType.Movie
                ))
            }
        }

        // 2. Fallback: Search AtoZ or Year categories if slug fails
        if (results.isEmpty()) {
            val firstChar = cleanQuery.firstOrNull()?.lowercaseChar()
            if (firstChar != null && firstChar in 'a'..'z') {
                val catUrl = "$mainUrl/tamil-atoz-dubbed-movies/$firstChar/"
                val doc = app.get(catUrl, headers = mapOf("User-Agent" to userAgent)).document
                
                doc.select("a").forEach { el ->
                    val href = el.attr("href")
                    val text = el.text().trim()
                    if (href.contains("/movie/") && text.contains(cleanQuery, ignoreCase = true)) {
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                        results.add(newMovieSearchResponse(
                            name = text, 
                            url = fullUrl, 
                            type = TvType.Movie
                        ))
                    }
                }
            }
        }
        
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val title = doc.selectFirst("title")?.text()?.substringBefore("-")?.trim() ?: "Unknown"
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = null
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
}
