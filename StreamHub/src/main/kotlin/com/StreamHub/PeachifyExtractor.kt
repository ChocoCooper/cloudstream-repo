package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object PeachifyExtractor {

    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Convert 111movies URL to Peachify embed URL
            val peachifyUrl = dataUrl.replace("111movies.net/movie", "peachify.top/embed/movie")
                                     .replace("111movies.net/tv", "peachify.top/embed/tv")

            val targetRegex = Regex(""".*\.m3u8.*""")
            val interceptor = WebViewResolver(targetRegex)

            // Catch the stream using the headless browser
            val response = app.get(peachifyUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            if (interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = "Peachify",
                        name = "VIP Server",
                        url = interceptedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        // CRITICAL: Optional parameters like referer must go inside these curly braces!
                        this.referer = peachifyUrl 
                    }
                )
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
