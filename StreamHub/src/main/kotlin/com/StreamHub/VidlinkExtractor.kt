package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

object VidlinkExtractor : ExtractorApi() {
    override val name = "Vidlink"
    override val mainUrl = "https://vidlink.pro"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? = null

    private const val ENC_API = "https://enc-dec.app/api"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    
    // Base API Request Headers
    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Origin" to "https://vidlink.pro",
        "Referer" to "https://vidlink.pro/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // Strict ExoPlayer headers to perfectly mimic Chrome and bypass 403 WAF
    private val videoHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Origin" to "https://vidlink.pro",
        "Referer" to "https://vidlink.pro/",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    private data class EncDecResponse(
        @JsonProperty("status") val status: Int?,
        @JsonProperty("result") val result: String?,
        @JsonProperty("error") val error: String?
    )

    @Suppress("DEPRECATION")
    suspend fun getStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val isMovie = url.contains("/movie/")
        val parts = url.split("/")
        
        val tmdbId = if (isMovie) parts.lastOrNull() else { if (parts.size >= 3) parts[parts.size - 3] else null } ?: return false
        val season = if (!isMovie) { if (parts.size >= 2) parts[parts.size - 2] else null } else null
        val episode = if (!isMovie) parts.lastOrNull() else null

        val encUrl = "$ENC_API/enc-vidlink?text=$tmdbId"
        val encResponseText = app.get(encUrl).text
        val encResponse = AppUtils.tryParseJson<EncDecResponse>(encResponseText)
        
        if (encResponse?.status != 200 || encResponse.result.isNullOrEmpty()) {
            return false
        }
        
        val encryptedId = encResponse.result

        val vidlinkApiUrl = if (isMovie) {
            "https://vidlink.pro/api/b/movie/$encryptedId"
        } else {
            "https://vidlink.pro/api/b/tv/$encryptedId/$season/$episode"
        }

        val responseText = app.get(vidlinkApiUrl, headers = baseHeaders).text
        
        var foundStream = false
        val foundUrls = mutableSetOf<String>()
        val jsonNode = AppUtils.tryParseJson<JsonNode>(responseText)
        
        if (jsonNode != null) {
            val initialSize = foundUrls.size
            // Recursively parses the JSON to accurately extract resolutions (labels) for each stream
            extractLinksFromJson(jsonNode, "Vidlink", callback, videoHeaders, foundUrls)
            if (foundUrls.size > initialSize) foundStream = true
        } else {
            // Fallback Regex if the payload isn't clean JSON
            val m3u8Regex = Regex("""(https?://[^"]+\.m3u8[^"]*)""")
            m3u8Regex.findAll(responseText).forEach { match ->
                val link = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                if (foundUrls.add(link)) {
                    val qualityInfo = getQualityFromName(link, "Vidlink")
                    callback.invoke(
                        ExtractorLink(
                            source = "Vidlink",
                            name = qualityInfo.second,
                            url = link,
                            referer = videoHeaders["Referer"] ?: "",
                            quality = qualityInfo.first,
                            headers = videoHeaders,
                            type = ExtractorLinkType.M3U8,
                            audioTracks = emptyList()
                        )
                    )
                    foundStream = true
                }
            }
        }

        return foundStream
    }

    // Safely walks through complex JSON trees looking for "file" (URLs) and "label" (1080p, 720p)
    private fun extractLinksFromJson(node: JsonNode, sourceName: String, callback: (ExtractorLink) -> Unit, headers: Map<String, String>, foundUrls: MutableSet<String>) {
        if (node.isArray) {
            node.forEach { extractLinksFromJson(it, sourceName, callback, headers, foundUrls) }
        } else if (node.isObject) {
            var file = ""
            var label = ""
            node.fields().forEach { (key, value) ->
                if (value.isTextual) {
                    val text = value.asText()
                    if (text.contains(".m3u8") || text.contains(".mp4")) {
                        file = text
                    } else if (key.equals("label", true) || key.equals("quality", true)) {
                        label = text
                    }
                } else if (value.isArray || value.isObject) {
                    extractLinksFromJson(value, sourceName, callback, headers, foundUrls)
                }
            }
            if (file.isNotEmpty()) {
                val cleanUrl = file.replace("\\/", "/").replace("\\u0026", "&")
                if (foundUrls.add(cleanUrl)) {
                    val isM3u8 = cleanUrl.contains(".m3u8")
                    val qualityPair = getQualityFromName(if (label.isNotEmpty()) label else cleanUrl, sourceName)
                    callback.invoke(
                        ExtractorLink(
                            source = "Vidlink",
                            name = qualityPair.second,
                            url = cleanUrl,
                            referer = headers["Referer"] ?: "",
                            quality = qualityPair.first,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                            headers = headers,
                            audioTracks = emptyList()
                        )
                    )
                }
            }
        }
    }

    private fun getQualityFromName(text: String, baseName: String): Pair<Int, String> {
        val lowerText = text.lowercase()
        return when {
            lowerText.contains("2160") || lowerText.contains("4k") -> Pair(Qualities.P2160.value, "$baseName - 4K")
            lowerText.contains("1080") -> Pair(Qualities.P1080.value, "$baseName - 1080p")
            lowerText.contains("720") -> Pair(Qualities.P720.value, "$baseName - 720p")
            lowerText.contains("480") -> Pair(Qualities.P480.value, "$baseName - 480p")
            lowerText.contains("360") -> Pair(Qualities.P360.value, "$baseName - 360p")
            else -> {
                val cleanLabel = text.trim()
                // Append the label explicitly if it's short and clean (e.g. "1080p")
                if (cleanLabel.isNotEmpty() && cleanLabel.length < 15 && !cleanLabel.startsWith("http")) {
                    Pair(Qualities.Unknown.value, "$baseName - $cleanLabel")
                } else {
                    Pair(Qualities.Unknown.value, baseName)
                }
            }
        }
    }
}
