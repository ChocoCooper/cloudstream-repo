package com.Film1k

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * HgCloud (Option 4) extractor.
 *
 * Confirmed behavior from full reverse-engineering:
 *
 *  1. https://hgcloud.to/e/<code> is an 819-byte page that loads main.js.
 *  2. main.js is a 71 KB obfuscator.io-protected redirector with an
 *     anti-tamper array-rotation loop. It computes window.location.href
 *     dynamically from an obfuscated domain list (main[] or dmca[]).
 *  3. The redirect lands on https://vibuxer.com/e/<code>.
 *  4. vibuxer.com serves a JW Player 8.36.3 page with the player
 *     configuration embedded in a P.A.C.K.E.R.-obfuscated inline script.
 *  5. The signed .m3u8 URL is only available after JW Player initializes.
 *
 * Static extraction fails because the redirect target is randomized per
 * page load and the P.A.C.K.E.R. payload must be executed to construct
 * the JW Player setup.
 *
 * WebViewResolver is the only viable approach: it loads the embed URL,
 * executes main.js (which completes the anti-tamper loop and redirects),
 * loads the vibuxer player page, executes the JW Player setup, and
 * intercepts the .m3u8 request.
 */
class HgCloudExtractor : ExtractorApi() {
    override var mainUrl = "https://hgcloud.to"
    override var name = "HgCloud"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            WebViewResolver(
                // Match the signed .m3u8, any .mp4, .ts segments, and
                // the common HLS endpoint patterns used by JW Player.
                interceptUrl = Regex(
                    """\.m3u8|\.mp4|\.ts|master\.m3u8|/hls/|/playlist/|/stream/""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = emptyList(),
                useOkHttp = false
            ).resolveUsingWebView(url) { link ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link.url,
                        type = if (link.url.contains(".m3u8", ignoreCase = true))
                            ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        // The final player is served from vibuxer.com
                        this.referer = "https://vibuxer.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            // Fail silently
        }
    }
}
