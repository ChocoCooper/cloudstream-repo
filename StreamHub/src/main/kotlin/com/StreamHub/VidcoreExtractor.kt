package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidcoreExtractor {

    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Convert 111movies master URL to Vidcore embed URL
            val vidcoreUrl = dataUrl.replace("111movies.net", "vidcore.net")

            // Regex targeting the exact domain you found in your Network tab
            val targetRegex = Regex(""".*digitalsun\.app.*\.m3u8.*""")
            val interceptor = WebViewResolver(targetRegex)

            // Execute the headless browser
            val response = app.get(vidcoreUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            // If we successfully caught the m3u8 link
            if (interceptedUrl.contains("digitalsun.app") || interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = "Vidcore",
                        name = "DigitalSun Server",
                        url = interceptedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        // Crucial: Found in your Request Headers screenshot
                        this.referer = "https://vidcore.net/" 
                    }
                )
                return true
            }
        } catch (e: Exception) {
            println("Vidcore Extraction Failed: ${e.message}")
        }
        return false
    }
}
