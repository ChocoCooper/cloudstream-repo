package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidlinkExtractor {
    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val vidlinkUrl = dataUrl.replace("https://streamhub.app", "https://vidlink.pro")
            val targetRegex = Regex(""".*vodvidl\.site.*\.m3u8.*""")
            val interceptor = WebViewResolver(targetRegex)

            val response = app.get(vidlinkUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            if (interceptedUrl.contains("vodvidl") || interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink("Vidlink", "Vidlink", interceptedUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://vidlink.pro/" 
                    }
                )
                return true
            }
        } catch (e: Exception) {}
        return false
    }
}
