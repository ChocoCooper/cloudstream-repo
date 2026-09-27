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
        val start = System.currentTimeMillis()
        android.util.Log.e(TAG, "HgCloud.getUrl → url=$url referer=$referer")

        try {
            val resolver = WebViewResolver(
                // Broader pattern to catch tokenized HLS URLs
                interceptUrl = Regex(
                    """\.m3u8(\?|$)|\.mp4(\?|$)|\.ts(\?|$)|master\.m3u8|/hls/|/playlist/|/manifest""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = emptyList(),
                userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/127.0.0.0 Safari/537.36",
                useOkhttp = false,
                script = """
                    (function(){
                        function tryClick(sel) {
                            try {
                                var el = document.querySelector(sel);
                                if (el) { el.click(); return true; }
                            } catch(e) {}
                            return false;
                        }
                        function tryPlay() {
                            try {
                                var v = document.querySelector('video');
                                if (v) {
                                    v.muted = true;
                                    var p = v.play();
                                    if (p && p.catch) p.catch(function(){});
                                    return true;
                                }
                            } catch(e) {}
                            return false;
                        }
                        var selectors = [
                            '.jw-display-icon-container',
                            '.jw-display-icon-display',
                            '.jw-icon-display',
                            '.jw-media',
                            '.jwplayer',
                            '.play-button',
                            '[class*="play"]',
                            '[class*="Play"]'
                        ];
                        var tries = 0;
                        var iv = setInterval(function(){
                            tries++;
                            for (var i = 0; i < selectors.length; i++) {
                                tryClick(selectors[i]);
                            }
                            tryPlay();
                            if (tries > 40) clearInterval(iv);
                        }, 1000);
                    })();
                """.trimIndent(),
                scriptCallback = { msg ->
                    android.util.Log.e(TAG, "HgCloud.WebView → $msg")
                },
                timeout = 45_000L
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

            val candidates = buildList {
                interceptedRequest?.url?.toString()?.let { add(it) }
                extraRequests.forEach { add(it.url.toString()) }
            }.distinct()

            android.util.Log.e(TAG, "HgCloud candidates (${candidates.size}): $candidates")

            val streamUrl = candidates.firstOrNull { it.contains(".m3u8", ignoreCase = true) }
                ?: candidates.firstOrNull { it.contains(".mp4", ignoreCase = true) }
                ?: run {
                    android.util.Log.e(TAG, "HgCloud NO STREAM")
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
