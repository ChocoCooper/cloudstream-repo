package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

object VidcoreExtractor : ExtractorApi() {
    override val name = "Vidcore"
    override val mainUrl = "https://vidcore.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? = null

    private const val ENC_API = "https://enc-dec.app/api"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    
    // API Request Headers
    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://vidcore.net/",
        "X-Requested-With" to "XMLHttpRequest"
    )
    
    // Strict ExoPlayer headers to perfectly mimic Chrome and bypass Shadowlemon's 403 WAF
    private val videoHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://vidcore.net/",
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    private data class EncVidcoreResult(
        @JsonProperty("servers") val servers: String,
        @JsonProperty("stream") val stream: String,
        @JsonProperty("token") val token: String
    )

    private data class EncVidcoreResponse(
        @JsonProperty("status") val status: Int?,
        @JsonProperty("result") val result: EncVidcoreResult?,
        @JsonProperty("error") val error: String?
    )

    private data class DecServerInfo(
        @JsonProperty("data") val data: String?,
        @JsonProperty("name") val name: String?
    )

    private data class DecServersResponse(
        @JsonProperty("status") val status: Int?,
        @JsonProperty("result") val result: List<DecServerInfo>?,
        @JsonProperty("error") val error: String?
    )

    @Suppress("DEPRECATION")
    suspend fun getStream(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        val isMovie = url.contains("/movie/")
        val parts = url.split("/")
        
        val tmdbId = if (isMovie) parts.lastOrNull() else { if (parts.size >= 3) parts[parts.size - 3] else null } ?: return false
        val season = if (!isMovie) { if (parts.size >= 2) parts[parts.size - 2] else null } else null
        val episode = if (!isMovie) parts.lastOrNull() else null

        val baseVidcoreUrl = if (isMovie) {
            "https://vidcore.net/movie/$tmdbId"
        } else {
            "https://vidcore.net/tv/$tmdbId/$season/$episode/"
        }

        val pageText = app.get(baseVidcoreUrl, headers = baseHeaders).text
        val textMatch = Regex("""\\"en\\":\\"(.*?)\\"""").find(pageText) ?: return false
        val text = textMatch.groupValues[1]

        val encUrl = "$ENC_API/enc-vidcore?text=$text"
        val encResponseText = app.get(encUrl).text
        val encResponse = AppUtils.tryParseJson<EncVidcoreResponse>(encResponseText)
        
        if (encResponse?.status != 200 || encResponse.result == null) return false

        val serversUrl = encResponse.result.servers
        val streamBaseUrl = encResponse.result.stream
        val token = encResponse.result.token

        val authHeaders = baseHeaders.toMutableMap().apply {
            put("X-CSRF-Token", token)
        }

        val serversEncryptedText = app.post(serversUrl, headers = authHeaders).text
        
        val decServersUrl = "$ENC_API/dec-vidcore"
        val decServersResponseText = app.post(
            decServersUrl, 
            json = mapOf("text" to serversEncryptedText)
        ).text
        val decServersResponse = AppUtils.tryParseJson<DecServersResponse>(decServersResponseText)

        val servers = decServersResponse?.result ?: emptyList()
        var foundStream = false
        val foundUrls = mutableSetOf<String>()

        servers.forEach { server ->
            val serverData = server.data ?: return@forEach
            val serverName = server.name ?: "Unknown Server"
            val baseSourceName = "Vidcore - $serverName"
            
            val streamUrl = "$streamBaseUrl/$serverData"
            val streamEncryptedText = app.post(streamUrl, headers = authHeaders).text
            
            val decryptedJsonStr = app.post(
                decServersUrl,
                json = mapOf("text" to streamEncryptedText)
            ).text
            
            val jsonNode = AppUtils.tryParseJson<JsonNode>(decryptedJsonStr)
            val initialSize = foundUrls.size

            if (jsonNode != null) {
                // Recursively parses the JSON to accurately extract resolutions (labels) for each stream
                extractLinksFromJson(jsonNode, baseSourceName, callback, videoHeaders, foundUrls)
                if (foundUrls.size > initialSize) foundStream = true
            } else {
                // Fallback Regex if the payload isn't clean JSON
                val m3u8Regex = Regex("""(https?://[^"]+\.m3u8[^"]*)""")
                m3u8Regex.findAll(decryptedJsonStr).forEach { match ->
                    val link = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                    if (foundUrls.add(link)) {
                        val qualityInfo = getQualityFromName(link, baseSourceName)
                        callback.invoke(
                            ExtractorLink(
                                source = "Vidcore",
                                name = qualityInfo.second,
                                url = link,
                                referer = videoHeaders["Referer"] ?: "",
                                quality = qualityInfo.first,
                                type = ExtractorLinkType.M3U8,
                                headers = videoHeaders,
                                audioTracks = emptyList()
                            )
                        )
                        foundStream = true
                    }
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
                            source = "Vidcore",
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
