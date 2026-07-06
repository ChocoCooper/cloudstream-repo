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
    
    private val videoHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
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

        val responseText = app.get(vidlinkApiUrl, headers = videoHeaders).text
        
        var foundStream = false
        val foundUrls = mutableSetOf<String>()
        
        val m3u8Regex = Regex("""(https?://[^"]+\.m3u8[^"]*)""")
        m3u8Regex.findAll(responseText).forEach { match ->
            val link = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
            if (foundUrls.add(link)) {
                try {
                    // Let Cloudstream natively parse the Master Playlist to output "Vidlink - 1080p", "Vidlink - 720p", etc.
                    val extractedLinks = M3u8Helper.generateM3u8(
                        source = "Vidlink",
                        streamUrl = link,
                        referer = "https://vidlink.pro/",
                        headers = videoHeaders
                    )
                    if (extractedLinks.isNotEmpty()) {
                        extractedLinks.forEach { callback.invoke(it) }
                        foundStream = true
                    } else {
                        callback.invoke(
                            ExtractorLink(
                                source = "Vidlink",
                                name = "Vidlink",
                                url = link,
                                referer = "https://vidlink.pro/",
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8,
                                headers = videoHeaders,
                                extractorData = null
                            )
                        )
                        foundStream = true
                    }
                } catch (e: Exception) {
                    callback.invoke(
                        ExtractorLink(
                            source = "Vidlink",
                            name = "Vidlink",
                            url = link,
                            referer = "https://vidlink.pro/",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8,
                            headers = videoHeaders,
                            extractorData = null
                        )
                    )
                    foundStream = true
                }
            }
        }
        
        val mp4Regex = Regex("""(https?://[^"]+\.mp4[^"]*)""")
        mp4Regex.findAll(responseText).forEach { match ->
            val link = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
            if (foundUrls.add(link)) {
                callback.invoke(
                    ExtractorLink(
                        source = "Vidlink",
                        name = "Vidlink",
                        url = link,
                        referer = "https://vidlink.pro/",
                        quality = Qualities.Unknown.value,
                        type = ExtractorLinkType.VIDEO,
                        headers = videoHeaders,
                        extractorData = null
                    )
                )
                foundStream = true
            }
        }

        return foundStream
    }
}
