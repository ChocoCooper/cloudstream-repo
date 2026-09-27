package com.Film1k

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Generic fallback extractor for any embed host we don't have a specific
 * handler for. Uses WebViewResolver to execute the player JavaScript and
 * intercept the .m3u8 / .mp4 request.
 *
 * Handles: myvidplay.com, hqq.ac, sbrapid.com, callistanise.com, and any
 * future host the site starts using.
 */
class GenericEmbedExtractor : ExtractorApi() {
    override var mainUrl = "https://www.film1k.com"
    override var name = "Embed"
    override val requiresReferer = true

    private val TAG = "Film1kDebug"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val start = System.currentTimeMillis()
        android.util.Log.e(TAG, "GenericEmbed.getUrl → url=$url referer=$referer")

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
                        var tries = 0;
                        var iv = setInterval(function(){
                            tries++;
                            try {
                                var display = document.querySelector(
                                    '.jw-display-icon-container, ' +
                                    '.jw-display-icon-display, ' +
                                    '.jw-icon-display, ' +
                                    '.play-button, [class*="play"]'
                                );
                                if (display) display.click();
                                var v = document.querySelector('video');
                                if (v) {
                                    v.muted = true;
                                    var p = v.play();
                                    if (p && p.catch) p.catch(function(){});
                                }
                            } catch(e) {}
                            if (tries > 25) clearInterval(iv);
                        }, 1000);
                    })();
                """.trimIndent(),
                scriptCallback = { msg ->
                    android.util.Log.e(TAG, "GenericEmbed.WebView → $msg")
                },
                timeout = 45_000L
            )

            val (interceptedRequest, extraRequests) = resolver.resolveUsingWebView(
                url = url,
                referer = referer
            )

            android.util.Log.e(
                TAG,
                "GenericEmbed intercepted=${interceptedRequest?.url} extras=${extraRequests.size}"
            )

            val candidates = buildList {
                interceptedRequest?.url?.toString()?.let { add(it) }
                extraRequests.forEach { add(it.url.toString()) }
            }.distinct()

            android.util.Log.e(TAG, "GenericEmbed candidates (${candidates.size}): $candidates")

            val streamUrl = candidates.firstOrNull { it.contains(".m3u8", ignoreCase = true) }
                ?: candidates.firstOrNull { it.contains(".mp4", ignoreCase = true) }
                ?: run {
                    android.util.Log.e(TAG, "GenericEmbed NO STREAM for $url")
                    return
                }

            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
            android.util.Log.e(
                TAG,
                "GenericEmbed EMITTING [${if (isM3u8) "M3U8" else "MP4"}] $streamUrl " +
                "(total ${System.currentTimeMillis() - start}ms)"
            )

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            android.util.Log.e(
                TAG,
                "GenericEmbed CANCELLED after ${System.currentTimeMillis() - start}ms: ${e.message}"
            )
        } catch (e: Throwable) {
            android.util.Log.e(
                TAG,
                "GenericEmbed FAILED after ${System.currentTimeMillis() - start}ms",
                e
            )
        }
    }
}
