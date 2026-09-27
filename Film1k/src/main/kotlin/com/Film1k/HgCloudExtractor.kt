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
 * Chains: hgcloud.to (JS redirector) → vibuxer.com (JW Player 8) → m3u8.
 *
 * Uses WebViewResolver + auto-click script. Enhanced analytics capture
 * every stage: redirect, player init, play click, video element state,
 * SSL errors, and the final intercepted request.
 *
 * Confirmed from prior logs:
 *  - Chromium inside the WebView reports `SSL error -202`
 *    (ERR_CERT_AUTHORITY_INVALID) for some CDN endpoints. This is a
 *    device WebView CA-bundle limitation, not a code issue.
 *  - The 30s timeout fails fast so it doesn't block the user.
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
        val start = System.currentTimeMillis()
        android.util.Log.e(TAG, "HgCloud.getUrl → url=$url referer=$referer")

        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex(
                    """\.m3u8(\?|$)|\.mp4(\?|$)|\.ts(\?|$)|master\.m3u8|/hls/""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = emptyList(),
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/127.0.0.0 Safari/537.36",
                useOkhttp = false,
                script = """
                    (function(){
                        function report(msg) {
                            try {
                                if (window.CloudstreamCallback && window.CloudstreamCallback.log) {
                                    window.CloudstreamCallback.log('[HgCloud] ' + msg);
                                }
                            } catch(e) {}
                        }
                        report('script injected, url=' + window.location.href);
                        report('readyState=' + document.readyState + ' title=' + document.title);

                        window.addEventListener('error', function(ev){
                            report('JS_ERROR: ' + (ev.message || ev) + ' @ ' +
                                   (ev.filename||'?') + ':' + (ev.lineno||'?'));
                        }, true);

                        var lastUrl = window.location.href;
                        var redirectChecks = 0;
                        setInterval(function(){
                            redirectChecks++;
                            if (window.location.href !== lastUrl) {
                                report('REDIRECT → ' + window.location.href);
                                lastUrl = window.location.href;
                            }
                            if (redirectChecks === 1 || redirectChecks % 5 === 0) {
                                report('url_check_' + redirectChecks + ': ' +
                                       window.location.href +
                                       ' (title=' + document.title + ')');
                            }
                        }, 500);

                        var reported = {};
                        setInterval(function(){
                            var v = document.querySelector('video');
                            if (v) {
                                var key = 'v_' + (v.src || 'nosrc');
                                if (!reported[key]) {
                                    reported[key] = true;
                                    report('VIDEO_FOUND src=' + (v.src || '(none)') +
                                           ' readyState=' + v.readyState +
                                           ' networkState=' + v.networkState +
                                           ' duration=' + v.duration);
                                }
                                if (v.error && !reported['verr_' + v.error.code]) {
                                    reported['verr_' + v.error.code] = true;
                                    report('VIDEO_ERROR code=' + v.error.code +
                                           ' msg=' + (v.error.message || '?'));
                                }
                            }
                            var iframe = document.querySelector('iframe');
                            if (iframe && iframe.src && !reported['if_' + iframe.src]) {
                                reported['if_' + iframe.src] = true;
                                report('IFRAME src=' + iframe.src);
                            }
                            var playerEl = document.querySelector('.jwplayer, #vplayer');
                            if (playerEl && !reported['player_el']) {
                                reported['player_el'] = true;
                                report('PLAYER_ELEMENT found: ' + playerEl.id + ' / ' + playerEl.className);
                            }
                        }, 1000);

                        var tries = 0;
                        var iv = setInterval(function(){
                            tries++;
                            try {
                                var display = document.querySelector(
                                    '.jw-display-icon-container, ' +
                                    '.jw-display-icon-display, ' +
                                    '.jw-icon-display'
                                );
                                if (display) {
                                    display.click();
                                    if (!reported['click_' + tries]) {
                                        reported['click_' + tries] = true;
                                        report('CLICK display try ' + tries);
                                    }
                                }
                                var v = document.querySelector('video');
                                if (v) {
                                    v.muted = true;
                                    var p = v.play();
                                    if (p && p.catch) {
                                        p.catch(function(e){
                                            report('PLAY_REJECT try ' + tries + ': ' + (e.message || e));
                                        });
                                    }
                                }
                            } catch(e) {
                                report('PLAY_ERR try ' + tries + ': ' + (e.message || e));
                            }
                            if (tries > 20) {
                                clearInterval(iv);
                                report('STOPPED clicking after 20 tries');
                            }
                        }, 1500);
                    })();
                """.trimIndent(),
                scriptCallback = { msg ->
                    android.util.Log.e(TAG, "HgCloud.WebView → $msg")
                },
                timeout = 30_000L
            )

            android.util.Log.e(TAG, "HgCloud resolver built, calling resolveUsingWebView...")
            val resolveStart = System.currentTimeMillis()
            val (interceptedRequest, extraRequests) = resolver.resolveUsingWebView(
                url = url,
                referer = referer
            )
            android.util.Log.e(
                TAG,
                "HgCloud resolveUsingWebView returned in ${System.currentTimeMillis() - resolveStart}ms"
            )

            android.util.Log.e(
                TAG,
                "HgCloud intercepted=${interceptedRequest?.url} extras=${extraRequests.size}"
            )
            extraRequests.forEachIndexed { i, r ->
                android.util.Log.e(TAG, "HgCloud extra[$i]=${r.url}")
            }

            val candidates = buildList {
                interceptedRequest?.url?.toString()?.let { add(it) }
                extraRequests.forEach { add(it.url.toString()) }
            }.distinct()

            android.util.Log.e(TAG, "HgCloud candidates (${candidates.size}): $candidates")

            val streamUrl = candidates.firstOrNull { it.contains(".m3u8", ignoreCase = true) }
                ?: candidates.firstOrNull { it.contains(".mp4", ignoreCase = true) }
                ?: run {
                    android.util.Log.e(
                        TAG,
                        "HgCloud NO STREAM — resolve complete but no candidate. " +
                        "Check WebView log above for redirect/video/SSL status."
                    )
                    return
                }

            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
            android.util.Log.e(
                TAG,
                "HgCloud EMITTING [${if (isM3u8) "M3U8" else "MP4"}] $streamUrl " +
                "(total ${System.currentTimeMillis() - start}ms)"
            )

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
            android.util.Log.e(
                TAG,
                "HgCloud CANCELLED after ${System.currentTimeMillis() - start}ms: ${e.message}"
            )
        } catch (e: Throwable) {
            android.util.Log.e(
                TAG,
                "HgCloud FAILED after ${System.currentTimeMillis() - start}ms",
                e
            )
        }
    }
}
