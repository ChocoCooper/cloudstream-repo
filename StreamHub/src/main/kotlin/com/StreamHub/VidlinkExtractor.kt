package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

object VidlinkExtractor : ExtractorApi() {
    override val name = "Vidlink"
    override val mainUrl = "https://vidlink.pro"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? = null

    private const val ENC_API = "https://enc-dec.app/api"
    
    // Headers mapped directly from vidlink.py
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
        "Origin" to "https://vidlink.pro",
        "Referer" to "https://vidlink.pro/"
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
        
        // Safely extract TMDB ID, Season, and Episode from the provider's embedData
        val tmdbId = if (isMovie) {
            parts.lastOrNull()
        } else {
            if (parts.size >= 3) parts[parts.size - 3] else null
        } ?: return false

        val season = if (!isMovie) {
            if (parts.size >= 2) parts[parts.size - 2] else null
        } else null

        val episode = if (!isMovie) {
            parts.lastOrNull()
        } else null

        // 1. Get encrypted TMDB ID using the decryption API
        val encUrl = "$ENC_API/enc-vidlink?text=$tmdbId"
        val encResponseText = app.get(encUrl).text
        val encResponse = AppUtils.tryParseJson<EncDecResponse>(encResponseText)
        
        if (encResponse?.status != 200 || encResponse.result.isNullOrEmpty()) {
            return false
        }
        
        val encryptedId = encResponse.result

        // 2. Build the vidlink.pro API request
        val vidlinkApiUrl = if (isMovie) {
            "https://vidlink.pro/api/b/movie/$encryptedId"
        } else {
            "https://vidlink.pro/api/b/tv/$encryptedId/$season/$episode"
        }

        // 3. Fetch the data with required headers
        val responseText = app.get(vidlinkApiUrl, headers = headers).text
        
        // 4. Extract stream links using Regex to safely handle unknown JSON payloads
        val m3u8Regex = Regex("""(https?://[^"]+\.m3u8[^"]*)""")
        var foundStream = false
        
        m3u8Regex.findAll(responseText).forEach { match ->
            // Unescape backward slashes commonly found in JSON encoded URLs
            val link = match.groupValues[1].replace("\\/", "/")
            callback.invoke(
                ExtractorLink(
                    source = "Vidlink",
                    name = "Vidlink",
                    url = link,
                    referer = "https://vidlink.pro/",
                    quality = Qualities.Unknown.value,
                    headers = mapOf("Referer" to "https://vidlink.pro/"),
                    extractorData = null,
                    type = ExtractorLinkType.M3U8,
                    audioTracks = emptyList() // Forces compiler to use the warning-level constructor safely
                )
            )
            foundStream = true
        }
        
        // Fallback check for standard .mp4 files if no HLS manifest was found
        if (!foundStream) {
            val mp4Regex = Regex("""(https?://[^"]+\.mp4[^"]*)""")
            mp4Regex.findAll(responseText).forEach { match ->
                val link = match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    ExtractorLink(
                        source = "Vidlink",
                        name = "Vidlink",
                        url = link,
                        referer = "https://vidlink.pro/",
                        quality = Qualities.Unknown.value,
                        headers = mapOf("Referer" to "https://vidlink.pro/"),
                        extractorData = null,
                        type = ExtractorLinkType.VIDEO,
                        audioTracks = emptyList() // Forces compiler to use the warning-level constructor safely
                    )
                )
                foundStream = true
            }
        }

        return foundStream
    }
}
