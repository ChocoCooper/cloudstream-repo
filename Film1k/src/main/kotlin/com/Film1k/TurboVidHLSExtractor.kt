package com.Film1k

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * TurboVidHLS (Option 3) extractor.
 *
 * Confirmed behavior from live testing:
 *   - https://turbovidhls.com/t/<code> serves a JW Player 7 page.
 *   - The page contains a header-gated MP4 URL (e08.etvp.cc/uploads/...mp4)
 *     that returns an error when opened without a Referer header.
 *   - The actual playable stream is an HLS manifest (.m3u8) on a separate
 *     CDN (cdn4.turboviplay.com/data3/<code>/<code>.m3u8) that requires
 *     a Referer matching the player origin.
 *   - The M3U8 URL is only constructed at runtime by the JW Player.
 *
 * WebViewResolver executes the player's JavaScript, clicks play, and
 * intercepts the actual .m3u8 request. The header-gated MP4 is captured
 * as a fallback.
 *
 * IMPORTANT: Some titles only expose the MP4 as the intercepted request
 * because the WebView captures it before JW Player upgrades to HLS. We
 * therefore collect every candidate and prefer .m3u8 over .mp4.
 */
class TurboVidHLSExtractor : ExtractorApi() {
    override var mainUrl = "https://turbovidhls.com"
    override var name = "TurboVidHLS"
    override val requiresReferer = true

    private val TAG = "Film1kDebug"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        android.util.Log.e(TAG, "TurboVidHLS.getUrl: $url")
        try {
            val resolver = WebViewResolver(
                // Strict media pattern. Do NOT include bare "turboviplay"
                // or "etvp.cc" — those match the JW Player library JS file,
                // which caused an incorrect early interception previously.
                interceptUrl = Regex(
                    """\.m3u8(\?|$)|\.mp4(\?|$)|\.ts(\?|$)|master\.m3u8|/hls/""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = emptyList(),
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/127.0.0.0 Safari/537.36",
                useOkhttp = false,
                // Auto-click the JW Player play button so it requests the
                // .m3u8 stream. JW Player only fetches the manifest after
                // playback begins.
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
                            if (tries > 30) clearInterval(iv);
                        }, 1000);
                    })();
                """.trimIndent(),
                scriptCallback = null,
                // TurboVidHLS usually resolves in ~18s (JW Player init +
                // play click + HLS request). 60s gives enough margin for
                // slower devices.
                timeout = 60_000L
            )

            val (interceptedRequest, extraRequests) = resolver.resolveUsingWebView(
                url = url,
                referer = referer ?: "$mainUrl/"
            )

            android.util.Log.e(
                TAG,
                "TurboVidHLS intercepted=${interceptedRequest?.url} extras=${extraRequests.size}"
            )
            extraRequests.forEachIndexed { i, r ->
                android.util.Log.e(TAG, "TurboVidHLS extra[$i]=${r.url}")
            }

            // Build the full candidate list — the intercepted request first,
            // then any additional matches.
            val candidates = buildList {
                interceptedRequest?.url?.toString()?.let { add(it) }
                extraRequests.forEach { add(it.url.toString()) }
            }.distinct()

            android.util.Log.e(TAG, "TurboVidHLS candidates: $candidates")

            // STRICT PREFERENCE: M3U8 > MP4.
            // The header-gated MP4 on etvp.cc returns an error without the
            // correct Referer and is not directly playable. Always prefer
            // the HLS manifest when available.
            val streamUrl = candidates.firstOrNull { it.contains(".m3u8", ignoreCase = true) }
                ?: candidates.firstOrNull { it.contains(".mp4", ignoreCase = true) }
                ?: run {
                    android.util.Log.e(TAG, "TurboVidHLS: NO stream URL")
                    return
                }

            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
            android.util.Log.e(TAG, "TurboVidHLS EMITTING: $streamUrl")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    // The CDN requires a Referer matching the player origin.
                    // Different subdomains (turboviplay.com, sacdnssedge.com,
                    // etvp.cc) accept the root player referer.
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            android.util.Log.e(TAG, "TurboVidHLS CANCELLED (timeout): ${e.message}")
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "TurboVidHLS FAILED", e)
        }
    }
}
