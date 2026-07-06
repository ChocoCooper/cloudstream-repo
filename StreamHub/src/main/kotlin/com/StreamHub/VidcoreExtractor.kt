package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

object VidcoreExtractor : ExtractorApi() {
    override val name = "Vidcore"
    override val mainUrl = "https://vidcore.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? = null

    private const val ENC_API = "https://enc-dec.app/api"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    
    // Base API headers
    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://vidcore.net/",
        "X-Requested-With" to "XMLHttpRequest"
    )
    
    // CRITICAL: We removed "Origin" and "Accept" here. 
    // CDNs block M3U8/TS requests with 403 Forbidden if Origin is present!
    private val videoHeaders = mapOf(
        "User-Agent" to USER_AGENT,
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
            val m3u8Match = m3u8Regex.find(decryptedJsonStr)
            
            if (m3u8Match != null) {
                val link = m3u8Match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                
                try {
                    // Let Cloudstream natively parse the Master Playlist to output "Vidcore - 1080p" etc.
                    val extractedLinks = M3u8Helper.generateM3u8(
                        source = "Vidcore - $serverName",
                        streamUrl = link,
                        referer = "https://vidcore.net/",
                        headers = videoHeaders
                    )
                    if (extractedLinks.isNotEmpty()) {
                        extractedLinks.forEach { callback.invoke(it) }
                        foundStream = true
                    }
                } catch (e: Exception) {}

                if (!foundStream) {
                    callback.invoke(
                        ExtractorLink(
                            source = "Vidcore",
                            name = "Vidcore - $serverName",
                            url = link,
                            referer = "https://vidcore.net/",
                            quality = Qualities.Unknown.value,
                            headers = videoHeaders,
                            extractorData = null,
                            type = ExtractorLinkType.M3U8,
                            audioTracks = emptyList() // Forces compile bypass
                        )
                    )
                    foundStream = true
                }
            } else {
                val mp4Regex = Regex("""(https?://[^"]+\.mp4[^"]*)""")
                mp4Regex.findAll(decryptedJsonStr).forEach { match ->
                    val link = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                    callback.invoke(
                        ExtractorLink(
                            source = "Vidcore",
                            name = "Vidcore - $serverName",
                            url = link,
                            referer = "https://vidcore.net/",
                            quality = Qualities.Unknown.value,
                            headers = videoHeaders,
                            extractorData = null,
                            type = ExtractorLinkType.VIDEO,
                            audioTracks = emptyList() // Forces compile bypass
                        )
                    )
                    foundStream = true
                }
            }
        }

        return foundStream
    }
}
