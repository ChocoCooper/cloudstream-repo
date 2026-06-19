package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidcoreExtractor {
    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val vidcoreUrl = dataUrl.replace("https://streamhub.app", "https://vidcore.net")
            val targetRegex = Regex(""".*digitalsun\.app.*\.m3u8.*""")
            val interceptor = WebViewResolver(targetRegex)

            val response = app.get(vidcoreUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            if (interceptedUrl.contains("digitalsun.app") || interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink("Vidcore", "Vidcore", interceptedUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://vidcore.net/" 
                    }
                )
                return true
            }
        } catch (e: Exception) {}
        return false
    }
}
