package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidsrcmeExtractor {
    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Forcefully inject autoplay parameters to try and bypass the "Click to Play" overlay
            val vidsrcmeUrl = dataUrl.replace("https://streamhub.app/movie/", "https://vidsrcme.su/embed/movie/")
                .replace("https://streamhub.app/tv/", "https://vidsrcme.su/embed/tv/") + "?autoplay=1&auto=1"
                
            // Catch-All Regex: Matches ANY .m3u8 file in case the 'tropeandtriptych' CDN changed domains
            val targetRegex = Regex(""".*\.m3u8.*""")
            val interceptor = WebViewResolver(targetRegex)

            val response = app.get(vidsrcmeUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            if (interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink("Vidsrcme", "Vidsrcme", interceptedUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://vidsrcme.su/" 
                        this.headers = mapOf("Origin" to "https://vidsrcme.su")
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
