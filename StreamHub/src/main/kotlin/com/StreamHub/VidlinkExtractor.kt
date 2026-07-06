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
    
    // Maintain strict headers for both app requests AND ExoPlayer to guarantee playback without 403 Errors
    private val videoHeaders = mapOf(
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
            // HashSet ensures exact duplicate links are dropped, keeping only unique resolutions
            if (foundUrls.add(link)) {
                val qualityInfo = getQualityFromName(link)

                callback.invoke(
                    ExtractorLink(
                        source = "Vidlink",
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
            mp4Regex.findAll(responseText).forEach { match ->
                val link = match.groupValues[1].replace("\\/", "/").replace("\\u0026", "&")
                if (foundUrls.add(link)) {
                    val qualityInfo = getQualityFromName(link)

                    callback.invoke(
                        ExtractorLink(
                            source = "Vidlink",
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

        return foundStream
    }

    private fun getQualityFromName(url: String): Pair<Int, String> {
        val baseName = "Vidlink"
        return when {
            url.contains("2160") || url.contains("4k", ignoreCase = true) -> Pair(Qualities.P2160.value, "$baseName - 4K")
            url.contains("1080") -> Pair(Qualities.P1080.value, "$baseName - 1080p")
            url.contains("720") -> Pair(Qualities.P720.value, "$baseName - 720p")
            url.contains("480") -> Pair(Qualities.P480.value, "$baseName - 480p")
            url.contains("360") -> Pair(Qualities.P360.value, "$baseName - 360p")
            else -> Pair(Qualities.Unknown.value, baseName)
        }
    }
}
