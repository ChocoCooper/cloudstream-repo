package com.tamilmv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

class TamilMVProvider : MainAPI() {
    override var mainUrl = "https://www.1tamilmv.cards"
    override var name = "TamilMV"
    override val hasMainPage = true
    override var lang = "ta"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    data class StrmupResponse(
        @JsonProperty("streaming_url") val streamingUrl: String?
    )

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
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

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        return newMovieLoadResponse("TamilMV Stream", url, TvType.Movie, url) {
            this.posterUrl = null
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
            callback.invoke(newExtractorLink(
                source = this.name,
                name = "Strmup",
                url = directUrl,
                referer = embedUrl,
                quality = Qualities.Unknown.value,
                isM3u8 = directUrl.contains(".m3u8")
            ))
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
            Regex("[\"']hls[2-4][\"']\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
            Regex("sources\\s*:\\s*\\[\\s*\\{\\s*file\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
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
                
                callback.invoke(newExtractorLink(
                    source = this.name,
                    name = "TamilMV Embed",
                    url = videoUrl,
                    referer = mainUrl, 
                    quality = if (videoUrl.contains(".m3u8")) Qualities.Unknown.value else Qualities.P720.value, 
                    isM3u8 = videoUrl.contains(".m3u8")
                ))
                break
            }
        }
    }
}
