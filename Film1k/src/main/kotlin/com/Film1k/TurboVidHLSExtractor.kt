package com.Film1k

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
                // STRICT: only match media extensions. Do NOT include domain
                // fragments like "turboviplay" — they appear in JS URLs too.
                interceptUrl = Regex(
                    """\.m3u8(\?|$)|\.mp4(\?|$)|\.ts(\?|$)|master\.m3u8|/hls/""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = emptyList(),
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
                useOkhttp = false,
                // Auto-click the JW Player play button after it initializes.
                // JW Player only requests the .m3u8 after playback starts.
                script = """
                    (function(){
                        var tries = 0;
                        var iv = setInterval(function(){
                            tries++;
                            var v = document.querySelector('video');
                            var display = document.querySelector('.jw-display-icon-container, .jw-display-icon-display, .jw-icon-display');
                            try {
                                if (display) display.click();
                                if (v) { v.muted = true; var p = v.play(); if (p && p.catch) p.catch(function(){}); }
                            } catch(e) {}
                            if (tries > 30) clearInterval(iv);
                        }, 1000);
                    })();
                """.trimIndent(),
                scriptCallback = null,
                timeout = 90_000L
            )

            val (interceptedRequest, extraRequests) = resolver.resolveUsingWebView(
                url = url,
                referer = referer ?: "$mainUrl/"
            )

            android.util.Log.e(TAG, "TurboVidHLS intercepted=${interceptedRequest?.url} extras=${extraRequests.size}")

            // Prefer the intercepted request; fall back to extras.
            val streamUrl = interceptedRequest?.url?.toString()
                ?: extraRequests.firstOrNull { it.url.toString().contains(".m3u8", ignoreCase = true) }?.url?.toString()
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
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "TurboVidHLS FAILED", e)
        }
    }
}
