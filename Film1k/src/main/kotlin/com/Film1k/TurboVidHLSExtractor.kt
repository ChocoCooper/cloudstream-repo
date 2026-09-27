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
 * Uses CloudStream's WebViewResolver to load the embed page, execute the
 * JW Player, and intercept the .m3u8 request. Enhanced with full analytics:
 *  - WebView console capture via scriptCallback
 *  - Per-stage timing
 *  - Every intercepted URL logged
 *  - SSL/network/JS errors surfaced
 */
class TurboVidHLSExtractor : ExtractorApi() {
    override var mainUrl = "https://turbovidhls.com"
    override var name = "TurboVid"
    override val requiresReferer = true

    private val TAG = "Film1kDebug"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val start = System.currentTimeMillis()
        android.util.Log.e(TAG, "TurboVid.getUrl → url=$url referer=$referer")

        try {
            val effectiveReferer = referer ?: "$mainUrl/"

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
                                    window.CloudstreamCallback.log('[TurboVid] ' + msg);
                                }
                            } catch(e) {}
                        }
                        report('script injected, url=' + window.location.href);
                        report('readyState=' + document.readyState);

                        window.addEventListener('error', function(ev){
                            report('JS_ERROR: ' + (ev.message || ev) + ' @ ' +
                                   (ev.filename||'?') + ':' + (ev.lineno||'?'));
                        }, true);

                        var lastUrl = window.location.href;
                        setInterval(function(){
                            if (window.location.href !== lastUrl) {
                                report('REDIRECT → ' + window.location.href);
                                lastUrl = window.location.href;
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
                                           ' networkState=' + v.networkState);
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
                            if (tries > 30) {
                                clearInterval(iv);
                                report('STOPPED clicking after 30 tries');
                            }
                        }, 1000);
                    })();
                """.trimIndent(),
                scriptCallback = { msg ->
                    android.util.Log.e(TAG, "TurboVid.WebView → $msg")
                },
                timeout = 60_000L
            )

            android.util.Log.e(TAG, "TurboVid resolver built, calling resolveUsingWebView...")
            val resolveStart = System.currentTimeMillis()
            val (interceptedRequest, extraRequests) = resolver.resolveUsingWebView(
                url = url,
                referer = effectiveReferer
            )
            android.util.Log.e(
                TAG,
                "TurboVid resolveUsingWebView returned in ${System.currentTimeMillis() - resolveStart}ms"
            )

            android.util.Log.e(
                TAG,
                "TurboVid intercepted=${interceptedRequest?.url} extras=${extraRequests.size}"
            )
            extraRequests.forEachIndexed { i, r ->
                android.util.Log.e(TAG, "TurboVid extra[$i]=${r.url}")
            }

            val candidates = buildList {
                interceptedRequest?.url?.toString()?.let { add(it) }
                extraRequests.forEach { add(it.url.toString()) }
            }.distinct()

            android.util.Log.e(TAG, "TurboVid candidates (${candidates.size}): $candidates")

            val streamUrl = candidates.firstOrNull { it.contains(".m3u8", ignoreCase = true) }
                ?: candidates.firstOrNull { it.contains(".mp4", ignoreCase = true) }
                ?: run {
                    android.util.Log.e(
                        TAG,
                        "TurboVid NO STREAM — resolve complete but no candidate matched. " +
                        "This usually means the player never requested the manifest " +
                        "(play button never fired, or SSL block)."
                    )
                    return
                }

            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
            android.util.Log.e(
                TAG,
                "TurboVid EMITTING [${if (isM3u8) "M3U8" else "MP4"}] $streamUrl " +
                "(total ${System.currentTimeMillis() - start}ms)"
            )

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
        } catch (e: kotlinx.coroutines.CancellationException) {
            android.util.Log.e(
                TAG,
                "TurboVid CANCELLED after ${System.currentTimeMillis() - start}ms: ${e.message}"
            )
        } catch (e: Throwable) {
            android.util.Log.e(
                TAG,
                "TurboVid FAILED after ${System.currentTimeMillis() - start}ms",
                e
            )
        }
    }
}
