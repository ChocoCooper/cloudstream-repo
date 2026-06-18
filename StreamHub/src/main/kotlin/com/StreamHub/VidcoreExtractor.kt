package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidcoreExtractor {

    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Replace the generic Hub URL with the Vidcore domain
            val vidcoreUrl = dataUrl.replace("https://streamhub.app", "https://vidcore.net")

            val targetRegex = Regex(""".*digitalsun\.app.*\.m3u8.*""")
            val interceptor = WebViewResolver(targetRegex)

            val response = app.get(vidcoreUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            if (interceptedUrl.contains("digitalsun.app") || interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = "Vidcore",
                        name = "Vidcore",
                        url = interceptedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://vidcore.net/" 
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
