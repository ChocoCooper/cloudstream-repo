package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object Movies111Extractor {

    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val targetRegex = Regex("""cfw69\.workers\.dev.*\.m3u8""")
            val interceptor = WebViewResolver(targetRegex)
            
            val response = app.get(dataUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            if (interceptedUrl.contains("cfw69") || interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = "111movies",
                        name = "111movies",
                        url = interceptedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://111movies.net"
                    }
                )
                return true
            }
        } catch (e: Exception) {
            // Silently fail and allow the next extractor to run
        }
        return false
    }
}
