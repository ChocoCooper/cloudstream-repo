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
 * Previous approach (regex for direct MP4) was WRONG:
 *  - The MP4 URL (e08.etvp.cc/uploads/<code>.mp4) returns an error when opened
 *    without a Referer header. It is not a directly playable stream.
 *
 * Confirmed behavior from live evidence:
 *  - TurboVid hosts serve HLS manifests on external CDNs such as:
 *      https://b-hls-20.sacdnssedge.com/hls/<id>/<id>_480p.m3u8
 *      https://cdn3.turboviplay.com/data1/<hash>/<hash>480.m3u8
 *  - These M3U8 URLs require a Referer header matching the player origin
 *    (e.g., https://cdn3.turboviplay.com).
 *  - The OCE project classifies EmTurbovid (same host family) as
 *    "API extraction", confirming the stream URL is resolved at runtime.
 *
 * WebViewResolver executes the player's JavaScript and intercepts the
 * actual M3U8 request, which is the only reliable method.
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
            WebViewResolver(
                // Match the HLS manifest and any MP4 fallback.
                // The M3U8 is the real stream; MP4 is header-gated.
                interceptUrl = Regex(
                    """\.m3u8|\.mp4|master\.m3u8|/hls/|sacdnssedge|turboviplay""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = emptyList(),
                useOkHttp = false
            ).resolveUsingWebView(url) { link ->
                val streamUrl = link.url
                val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        // The M3U8 CDNs require a Referer header. Use the
                        // player origin as the referer, which matches what
                        // a real browser sends when JW Player requests the stream.
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            // Fail silently
        }
    }
}
