package com.Film1k

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * HgCloud (Option 4) extractor — CORRECTED.
 *
 * Uses CloudStream's WebViewResolver correctly:
 *  - The resolver is constructed with the confirmed constructor signature
 *    (useOkhttp, not useOkHttp).
 *  - resolveUsingWebView returns Pair<Request?, List<Request>>; the first
 *    element is the intercepted request whose URL matches interceptUrl.
 *  - We read the URL from interceptedRequest.url (okhttp3.HttpUrl) and
 *    convert it to String.
 *
 * The redirect chain (hgcloud.to -> vibuxer.com) and the P.A.C.K.E.R.-
 * obfuscated JW Player setup are both handled automatically by the WebView.
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
            val resolver = WebViewResolver(
                // Match the signed .m3u8, any .mp4, .ts segments, and the
                // common HLS endpoint patterns used by JW Player.
                interceptUrl = Regex(
                    """\.m3u8|\.mp4|\.ts|master\.m3u8|/hls/|/playlist/|/stream/""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = emptyList(),
                userAgent = null,      // use the default WebView user agent
                useOkhttp = false,     // IMPORTANT: lowercase 'h' (not useOkHttp)
                script = null,
                scriptCallback = null,
                timeout = 60_000L
            )

            // CORRECT usage: resolveUsingWebView returns a Pair.
            // The first element is the intercepted request (or null).
            // The second element is the list of additional matching requests.
            val (interceptedRequest, _) = resolver.resolveUsingWebView(
                url = url,
                referer = referer
            )

            // Convert okhttp3.HttpUrl to String
            val streamUrl = interceptedRequest?.url?.toString() ?: return

            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    // The final player is served from vibuxer.com
                    this.referer = "https://vibuxer.com/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            // Fail silently
        }
    }
}
