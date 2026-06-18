package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object Movies111Extractor {

    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Replace the generic Hub URL with the 111movies domain
            val embedUrl = dataUrl.replace("https://streamhub.app", "https://111movies.net")
            
            val targetRegex = Regex("""cfw69\.workers\.dev.*\.m3u8""")
            val interceptor = WebViewResolver(targetRegex)
            
            val response = app.get(embedUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            if (interceptedUrl.contains("cfw69") || interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = "111movies",
                        name = "111movies",
                        url = interceptedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        // FIX: Reverted to the simple, working referer. Removed the strict Origin headers.
                        this.referer = "https://111movies.net/"
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
