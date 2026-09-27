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
        android.util.Log.d(TAG, "HgCloud getUrl: $url")
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex(
                    """\.m3u8|\.mp4|\.ts|master\.m3u8|/hls/|/playlist/|/stream/""",
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
                referer = referer
            )

            android.util.Log.d(TAG, "HgCloud: intercepted=${interceptedRequest?.url} extras=${extraRequests.size}")

            val streamUrl = interceptedRequest?.url?.toString() ?: run {
                android.util.Log.w(TAG, "HgCloud: no intercepted request")
                return
            }
            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)

            android.util.Log.d(TAG, "HgCloud: emitting $streamUrl")

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
            android.util.Log.e(TAG, "HgCloud failed", e)
        }
    }
}
