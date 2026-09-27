package com.Film1k

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * TurboVidHLS (Option 3) extractor — CORRECTED.
 *
 * Previous approach (static regex for direct MP4) was WRONG because:
 *  - The MP4 URL (e08.etvp.cc/uploads/<code>.mp4) returns an error when
 *    opened without a Referer header.
 *  - The real stream is an HLS manifest (.m3u8) served from external CDNs
 *    such as b-hls-*.sacdnssedge.com and cdn3.turboviplay.com.
 *  - These M3U8 URLs are constructed at runtime by the JW Player.
 *
 * WebViewResolver executes the player's JavaScript and intercepts the
 * actual M3U8 request — the only reliable method.
 */
class TurboVidHLSExtractor : ExtractorApi() {
    override var mainUrl = "https://turbovidhls.com"
    override var name = "TurboVidHLS"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex(
                    """\.m3u8|\.mp4|master\.m3u8|/hls/|sacdnssedge|turboviplay|etvp\.cc""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = emptyList(),
                userAgent = null,
                useOkhttp = false,
                script = null,
                scriptCallback = null,
                timeout = 60_000L
            )

            val (interceptedRequest, _) = resolver.resolveUsingWebView(
                url = url,
                referer = referer ?: "$mainUrl/"
            )

            val streamUrl = interceptedRequest?.url?.toString() ?: return
            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            // Fail silently
        }
    }
}
