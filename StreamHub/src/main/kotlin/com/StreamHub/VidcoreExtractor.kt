package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

object VidcoreExtractor : ExtractorApi() {
    override val name = "Vidcore"
    override val mainUrl = "https://vidcore.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? = null

    private const val ENC_API = "https://enc-dec.app/api"
    
    // Headers mapped directly from vidcore.py for API requests
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "https://vidcore.net/",
        "X-Requested-With" to "XMLHttpRequest"
    )
    
    // CRITICAL: We MUST explicitly pass the Chrome User-Agent into the ExoPlayer video request. 
    // Without this, ExoPlayer uses its default UA, causing the CDN WAF to instantly return a 403 (Error 2004).
    private val videoHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "https://vidcore.net/"
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
            
            val streamUrl = "$streamBaseUrl/$serverData"
            val streamEncryptedText = app.post(streamUrl, headers = authHeaders).text
            
            val decryptedJsonStr = app.post(
                decServersUrl,
                json = mapOf("text" to streamEncryptedText)
            ).text
            
            val m3u8Regex = Regex("""(https?://[^"]+\.m3u8[^"]*)""")
            m3u8Regex.findAll(decryptedJsonStr).forEach { match ->
                val link = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                if (foundUrls.add(link)) {
                    val qualityInfo = getQualityFromName(link, "Vidcore - $serverName")
                    
                    callback.invoke(
                        ExtractorLink(
                            source = "Vidcore",
                            name = qualityInfo.second,
                            url = link,
                            referer = videoHeaders["Referer"] ?: "",
                            quality = qualityInfo.first,
                            headers = videoHeaders,
                            extractorData = null,
                            type = ExtractorLinkType.M3U8,
                            audioTracks = emptyList()
                        )
                    )
                    foundStream = true
                }
            }

            if (!foundStream) {
                val mp4Regex = Regex("""(https?://[^"]+\.mp4[^"]*)""")
                mp4Regex.findAll(decryptedJsonStr).forEach { match ->
                    val link = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                    if (foundUrls.add(link)) {
                        val qualityInfo = getQualityFromName(link, "Vidcore - $serverName")

                        callback.invoke(
                            ExtractorLink(
                                source = "Vidcore",
                                name = qualityInfo.second,
                                url = link,
                                referer = videoHeaders["Referer"] ?: "",
                                quality = qualityInfo.first,
                                headers = videoHeaders,
                                extractorData = null,
                                type = ExtractorLinkType.VIDEO,
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

    private fun getQualityFromName(url: String, baseName: String): Pair<Int, String> {
        return when {
            url.contains("1080") -> Pair(Qualities.P1080.value, "$baseName - 1080p")
            url.contains("720") -> Pair(Qualities.P720.value, "$baseName - 720p")
            url.contains("480") -> Pair(Qualities.P480.value, "$baseName - 480p")
            url.contains("360") -> Pair(Qualities.P360.value, "$baseName - 360p")
            else -> Pair(Qualities.Unknown.value, baseName)
        }
    }
}
