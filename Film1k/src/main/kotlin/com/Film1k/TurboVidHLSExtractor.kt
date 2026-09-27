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
                interceptUrl = Regex(
                    """\.m3u8|\.mp4|master\.m3u8|/hls/|sacdnssedge|turboviplay|etvp\.cc""",
                    RegexOption.IGNORE_CASE
                ),
                additionalUrls = emptyList(),
                userAgent = null,
                useOkhttp = false,
                script = null,
                scriptCallback = null,
                timeout = 60_000L
            )

            val (interceptedRequest, extraRequests) = resolver.resolveUsingWebView(
                url = url,
                referer = referer ?: "$mainUrl/"
            )

            android.util.Log.e(TAG, "TurboVidHLS intercepted=${interceptedRequest?.url} extras=${extraRequests.size}")

            val streamUrl = interceptedRequest?.url?.toString() ?: run {
                android.util.Log.e(TAG, "TurboVidHLS: NO intercepted request")
                return
            }
            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
            android.util.Log.e(TAG, "TurboVidHLS emitting: $streamUrl")

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
