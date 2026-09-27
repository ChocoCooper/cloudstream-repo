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
 *   1. https://hgcloud.to/e/<code> is an 819-byte page that loads main.js.
 *   2. main.js is a 71 KB obfuscator.io-protected redirector with an
 *      anti-tamper array-rotation loop. It computes window.location.href
 *      dynamically from an obfuscated domain list.
 *   3. The redirect lands on https://vibuxer.com/e/<code>.
 *   4. vibuxer.com serves a JW Player 8.36.3 page with the player
 *      configuration embedded in a P.A.C.K.E.R.-obfuscated inline script.
 *   5. The signed .m3u8 URL is only available after JW Player initializes
 *      AND the user clicks play (JW Player defers the HLS request until
 *      playback starts).
 *
 * WebViewResolver executes everything and intercepts the .m3u8 request.
 * The injected script auto-clicks the JW Player play button repeatedly.
 *
 * NOTE: In observed logs, this extractor has a low success rate against
 * vibuxer's JW Player because the WebView's TLS handshake to some CDN
 * endpoints fails (SSL error -202 = ERR_CERT_AUTHORITY_INVALID inside
 * Chromium). The 30s timeout makes it fail fast so it does not block
 * the user from receiving other extractors' links.
 */
class HgCloudExtractor : ExtractorApi() {
    override var mainUrl = "https://hgcloud.to"
    override var name = "HgCloud"
    override val requiresReferer = true

    private val TAG = "Film1kDebug"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        android.util.Log.e(TAG, "HgCloud.getUrl: $url")
        try {
            val resolver = WebViewResolver(
                // Strict media pattern. Do NOT include ".mp4" without a
                // trailing boundary because vibuxer serves poster images.
                interceptUrl = Regex(
                    """\.m3u8(\?|$)|\.mp4(\?|$)|\.ts(\?|$)|master\.m3u8|/hls/""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = emptyList(),
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/127.0.0.0 Safari/537.36",
                useOkhttp = false,
                // Auto-click JW Player's play button repeatedly. JW Player
                // only requests the .m3u8 after playback starts, so without
                // this script the WebView would sit on the poster forever.
                script = """
                    (function(){
                        var tries = 0;
                        var iv = setInterval(function(){
                            tries++;
                            try {
                                var display = document.querySelector(
                                    '.jw-display-icon-container, ' +
                                    '.jw-display-icon-display, ' +
                                    '.jw-icon-display'
                                );
                                if (display) display.click();
                                var v = document.querySelector('video');
                                if (v) {
                                    v.muted = true;
                                    var p = v.play();
                                    if (p && p.catch) p.catch(function(){});
                                }
                            } catch(e) {}
                            if (tries > 20) clearInterval(iv);
                        }, 1500);
                    })();
                """.trimIndent(),
                scriptCallback = null,
                // 30 seconds — fail fast. HgCloud is unreliable on mobile
                // WebView due to TLS handshake failures against its CDNs.
                timeout = 30_000L
            )

            val (interceptedRequest, extraRequests) = resolver.resolveUsingWebView(
                url = url,
                referer = referer
            )

            android.util.Log.e(
                TAG,
                "HgCloud intercepted=${interceptedRequest?.url} extras=${extraRequests.size}"
            )
            extraRequests.forEachIndexed { i, r ->
                android.util.Log.e(TAG, "HgCloud extra[$i]=${r.url}")
            }

            // Build candidate list from intercepted + extras
            val candidates = buildList {
                interceptedRequest?.url?.toString()?.let { add(it) }
                extraRequests.forEach { add(it.url.toString()) }
            }.distinct()

            // Prefer M3U8, then any MP4
            val streamUrl = candidates.firstOrNull { it.contains(".m3u8", ignoreCase = true) }
                ?: candidates.firstOrNull { it.contains(".mp4", ignoreCase = true) }
                ?: run {
                    android.util.Log.e(TAG, "HgCloud: NO stream URL")
                    return
                }

            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
            android.util.Log.e(TAG, "HgCloud EMITTING: $streamUrl")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "https://vibuxer.com/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Timeout from WebViewResolver — log and exit cleanly.
            // This is a CancellationException, so we must NOT re-throw it
            // in a way that would cancel the parent supervisor scope.
            android.util.Log.e(TAG, "HgCloud CANCELLED (timeout): ${e.message}")
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "HgCloud FAILED", e)
        }
    }
}
