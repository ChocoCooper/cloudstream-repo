package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

object VidcoreExtractor {
    private const val ENC_API = "https://enc-dec.app/api"
    
    // Headers mapped directly from vidcore.py
    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Referer" to "https://vidcore.net/",
        "X-Requested-With" to "XMLHttpRequest"
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
        // url format passed from StreamHubProvider:
        // Movie: https://streamhub.app/movie/{tmdbId}
        // TV: https://streamhub.app/tv/{tmdbId}/{season}/{episode}
        
        val isMovie = url.contains("/movie/")
        val parts = url.split("/")
        
        val tmdbId = if (isMovie) parts.lastOrNull() else { if (parts.size >= 3) parts[parts.size - 3] else null } ?: return false
        val season = if (!isMovie) { if (parts.size >= 2) parts[parts.size - 2] else null } else null
        val episode = if (!isMovie) parts.lastOrNull() else null

        // Build the base vidcore URL to scrape the initial hash
        val baseVidcoreUrl = if (isMovie) {
            "https://vidcore.net/movie/$tmdbId"
        } else {
            "https://vidcore.net/tv/$tmdbId/$season/$episode/"
        }

        // 1. Fetch page content
        val pageText = app.get(baseVidcoreUrl, headers = baseHeaders).text

        // 2. Extract encrypted base text using Regex match
        val textMatch = Regex("""\\"en\\":\\"(.*?)\\"""").find(pageText) ?: return false
        val text = textMatch.groupValues[1]

        // 3. Get API URLs and Token from enc-dec app
        val encUrl = "$ENC_API/enc-vidcore?text=$text"
        val encResponseText = app.get(encUrl).text
        val encResponse = AppUtils.tryParseJson<EncVidcoreResponse>(encResponseText)
        
        if (encResponse?.status != 200 || encResponse.result == null) {
            return false
        }

        val serversUrl = encResponse.result.servers
        val streamBaseUrl = encResponse.result.stream
        val token = encResponse.result.token

        // Update headers with CSRF Token
        val authHeaders = baseHeaders.toMutableMap().apply {
            put("X-CSRF-Token", token)
        }

        // 4. Get streaming servers (Encrypted POST)
        val serversEncryptedText = app.post(serversUrl, headers = authHeaders).text
        
        // 5. Decrypt servers
        val decServersUrl = "$ENC_API/dec-vidcore"
        val decServersResponseText = app.post(
            decServersUrl, 
            json = mapOf("text" to serversEncryptedText)
        ).text
        val decServersResponse = AppUtils.tryParseJson<DecServersResponse>(decServersResponseText)

        val servers = decServersResponse?.result ?: emptyList()
        var foundStream = false

        // 6. Iterate through available servers and fetch stream nodes
        servers.forEach { server ->
            val serverData = server.data ?: return@forEach
            val serverName = server.name ?: "Unknown Server"
            
            // Post to specific server node
            val streamUrl = "$streamBaseUrl/$serverData"
            val streamEncryptedText = app.post(streamUrl, headers = authHeaders).text
            
            // Decrypt the node payload directly as a raw JSON string to bypass object mapping
            val decryptedJsonStr = app.post(
                decServersUrl,
                json = mapOf("text" to streamEncryptedText)
            ).text
            
            val m3u8Regex = Regex("""(https?://[^"]+\.m3u8[^"]*)""")
            m3u8Regex.findAll(decryptedJsonStr).forEach { match ->
                val link = match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    ExtractorLink(
                        source = "Vidcore",
                        name = "Vidcore - $serverName",
                        url = link,
                        referer = "https://vidcore.net/",
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                foundStream = true
            }

            // MP4 fallback loop inside that server if no HLS is available
            if (!foundStream) {
                val mp4Regex = Regex("""(https?://[^"]+\.mp4[^"]*)""")
                mp4Regex.findAll(decryptedJsonStr).forEach { match ->
                    val link = match.groupValues[1].replace("\\/", "/")
                    callback.invoke(
                        ExtractorLink(
                            source = "Vidcore",
                            name = "Vidcore - $serverName",
                            url = link,
                            referer = "https://vidcore.net/",
                            quality = Qualities.Unknown.value,
                            isM3u8 = false
                        )
                    )
                    foundStream = true
                }
            }
        }

        return foundStream
    }
}
