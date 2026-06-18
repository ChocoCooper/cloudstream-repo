package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidlinkExtractor {

    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Replace the generic Hub URL with the Vidlink domain
            val vidlinkUrl = dataUrl.replace("https://streamhub.app", "https://vidlink.pro")

            val targetRegex = Regex(""".*vodvidl\.site.*\.m3u8.*""")
            val interceptor = WebViewResolver(targetRegex)

            val response = app.get(vidlinkUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            if (interceptedUrl.contains("vodvidl") || interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = "Vidlink",
                        name = "Vidlink",
                        url = interceptedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        // FIX: Point the referer to Vidlink, NOT Megacloud. 
                        // The proxy server handles Megacloud headers internally.
                        this.referer = "https://vidlink.pro/" 
                    }
                )
                return true
            }
        } catch (e: Exception) {
            // Silently fail to allow the next extractor to run
        }
        return false
    }
}
