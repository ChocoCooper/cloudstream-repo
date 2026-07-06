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
    
    // Omit User-Agent here as well to inherit the exact UA ExoPlayer will use
    private val reqHeaders = mapOf(
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

        val responseText = app.get(vidlinkApiUrl, headers = reqHeaders).text
        
        val m3u8Regex = Regex("""(https?://[^"]+\.m3u8[^"]*)""")
        m3u8Regex.find(responseText)?.let { match ->
            val link = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
            callback.invoke(
                ExtractorLink(
                    source = "Vidlink",
                    name = "Vidlink",
                    url = link,
                    referer = "https://vidlink.pro/",
                    quality = Qualities.Unknown.value,
                    headers = reqHeaders,
                    extractorData = null,
                    type = ExtractorLinkType.M3U8,
                    audioTracks = emptyList()
                )
            )
            // Immediately return after finding the master playlist to prevent duplicate logs
            return true
        }
        
        val mp4Regex = Regex("""(https?://[^"]+\.mp4[^"]*)""")
        mp4Regex.find(responseText)?.let { match ->
            val link = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
            callback.invoke(
                ExtractorLink(
                    source = "Vidlink",
                    name = "Vidlink",
                    url = link,
                    referer = "https://vidlink.pro/",
                    quality = Qualities.Unknown.value,
                    headers = reqHeaders,
                    extractorData = null,
                    type = ExtractorLinkType.VIDEO,
                    audioTracks = emptyList()
                )
            )
            return true
        }

        return false
    }
}
