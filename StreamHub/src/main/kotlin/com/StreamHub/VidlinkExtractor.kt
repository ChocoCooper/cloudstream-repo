package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

object VidlinkExtractor : ExtractorApi() {
    override val name = "Vidlink"
    override val mainUrl = "https://vidlink.pro"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? = null

    private const val ENC_API = "https://enc-dec.app/api"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    
    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Origin" to "https://vidlink.pro",
        "Referer" to "https://vidlink.pro/",
        "X-Requested-With" to "XMLHttpRequest"
    )

    // NO ORIGIN HEADER! This prevents the 403 WAF blocks on ExoPlayer
    private val videoHeaders = mapOf(
        "User-Agent" to USER_AGENT,
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

        val responseText = app.get(vidlinkApiUrl, headers = baseHeaders).text
        
        var foundStream = false
        val m3u8Regex = Regex("""(https?://[^"]+\.m3u8[^"]*)""")
        val m3u8Match = m3u8Regex.find(responseText)
        
        if (m3u8Match != null) {
            val link = m3u8Match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
            
            try {
                // Cloudstream's native M3u8Helper unpacks the master list to display: "Vidlink - 1080p", "Vidlink - 720p"
                val extractedLinks = M3u8Helper.generateM3u8(
                    source = "Vidlink",
                    streamUrl = link,
                    referer = "https://vidlink.pro/",
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
                        source = "Vidlink",
                        name = "Vidlink",
                        url = link,
                        referer = "https://vidlink.pro/",
                        quality = Qualities.Unknown.value,
                        headers = videoHeaders,
                        extractorData = null,
                        type = ExtractorLinkType.M3U8,
                        audioTracks = emptyList() // Forces compile bypass
                    )
                )
                foundStream = true
            }
            return foundStream
        }
        
        val mp4Regex = Regex("""(https?://[^"]+\.mp4[^"]*)""")
        mp4Regex.findAll(responseText).forEach { match ->
            val link = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
            callback.invoke(
                ExtractorLink(
                    source = "Vidlink",
                    name = "Vidlink",
                    url = link,
                    referer = "https://vidlink.pro/",
                    quality = Qualities.Unknown.value,
                    headers = videoHeaders,
                    extractorData = null,
                    type = ExtractorLinkType.VIDEO,
                    audioTracks = emptyList() // Forces compile bypass
                )
            )
            foundStream = true
        }

        return foundStream
    }
}
