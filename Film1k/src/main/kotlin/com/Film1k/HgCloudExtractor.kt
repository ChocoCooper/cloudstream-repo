package com.Film1k

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
                // Strict media pattern. Do not include ".mp4" without boundary
                // because vibuxer serves poster images on some CDNs.
                interceptUrl = Regex(
                    """\.m3u8(\?|$)|\.mp4(\?|$)|\.ts(\?|$)|master\.m3u8|/hls/""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = emptyList(),
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
                useOkhttp = false,
                // 1. The redirect chain (hgcloud.to -> vibuxer.com) takes a few
                //    seconds to complete via main.js.
                // 2. Then the vibuxer JW Player loads and needs a user gesture
                //    to start playback (which triggers the .m3u8 request).
                // This script waits 10s for the redirect, then continuously
                // tries to click/play until the resolver intercepts the stream.
                script = """
                    (function(){
                        var tries = 0;
                        var iv = setInterval(function(){
                            tries++;
                            try {
                                var display = document.querySelector('.jw-display-icon-container, .jw-display-icon-display, .jw-icon-display');
                                if (display) display.click();
                                var v = document.querySelector('video');
                                if (v) {
                                    v.muted = true;
                                    var p = v.play();
                                    if (p && p.catch) p.catch(function(){});
                                }
                            } catch(e) {}
                            if (tries > 60) clearInterval(iv);
                        }, 1500);
                    })();
                """.trimIndent(),
                scriptCallback = null,
                timeout = 120_000L
            )

            val (interceptedRequest, extraRequests) = resolver.resolveUsingWebView(
                url = url,
                referer = referer
            )

            android.util.Log.e(TAG, "HgCloud intercepted=${interceptedRequest?.url} extras=${extraRequests.size}")
            extraRequests.forEachIndexed { i, r ->
                android.util.Log.e(TAG, "HgCloud extra[$i]=${r.url}")
            }

            val streamUrl = interceptedRequest?.url?.toString()
                ?: extraRequests.firstOrNull { it.url.toString().contains(".m3u8", ignoreCase = true) }?.url?.toString()
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
        } catch (e: Exception) {
            android.util.Log.e(TAG, "HgCloud FAILED", e)
        }
    }
}
