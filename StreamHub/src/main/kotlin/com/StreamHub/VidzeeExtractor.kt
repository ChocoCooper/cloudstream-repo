package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidzeeExtractor {
    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Forcefully inject autoplay parameters
            val vidzeeUrl = dataUrl.replace("https://streamhub.app/movie/", "https://player.vidzee.wtf/embed/movie/")
                .replace("https://streamhub.app/tv/", "https://player.vidzee.wtf/embed/tv/") + "?autoplay=1&auto=1"
                
            // Catch-All Regex for any CDN rotation
            val targetRegex = Regex(""".*\.m3u8.*""")
            val interceptor = WebViewResolver(targetRegex)

            val response = app.get(vidzeeUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            if (interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink("Vidzee", "Vidzee", interceptedUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.vidzee.wtf/" 
                        this.headers = mapOf("Origin" to "https://player.vidzee.wtf")
                    }
                )
                return true
            }
        } catch (e: Exception) {
            // Silently fail
        }
        return false
    }
}
