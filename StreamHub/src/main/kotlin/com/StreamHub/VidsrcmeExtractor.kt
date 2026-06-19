package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidsrcmeExtractor {
    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val vidsrcmeUrl = dataUrl.replace("https://streamhub.app/movie/", "https://vidsrcme.su/embed/movie/")
                .replace("https://streamhub.app/tv/", "https://vidsrcme.su/embed/tv/")
                
            val targetRegex = Regex(""".*tropeandtriptych\.website.*\.m3u8.*""")
            val interceptor = WebViewResolver(targetRegex)

            val response = app.get(vidsrcmeUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            if (interceptedUrl.contains("tropeandtriptych") || interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink("Vidsrcme", "Vidsrcme", interceptedUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://vidsrcme.su/" 
                    }
                )
                return true
            }
        } catch (e: Exception) {}
        return false
    }
}
