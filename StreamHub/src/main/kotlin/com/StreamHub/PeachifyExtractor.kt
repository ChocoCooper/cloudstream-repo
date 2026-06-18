package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object PeachifyExtractor {

    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Convert the base StreamHub URL to the Peachify embed route
            val peachifyUrl = dataUrl.replace("111movies.net/movie", "peachify.top/embed/movie")
                                     .replace("111movies.net/tv", "peachify.top/embed/tv")

            // Regex designed to catch Peachify's Cloudflare mp4-proxy OR the raw hakunaymatata host
            val targetRegex = Regex("""(mp4-proxy|hakunaymatata.*\.mp4)""")
            val interceptor = WebViewResolver(targetRegex)

            // Execute the headless browser
            val response = app.get(peachifyUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            // If we successfully caught the proxy or mp4 link
            if (interceptedUrl.contains("mp4") || interceptedUrl.contains("proxy")) {
                callback.invoke(
                    newExtractorLink(
                        source = "Peachify",
                        name = "VIP Server (English)",
                        url = interceptedUrl,
                        type = ExtractorLinkType.VIDEO // Set to VIDEO since it's a raw MP4, not an M3U8
                    ) {
                        // Crucial: Bypasses Cloudflare's hotlink protection (Fixes the 2004 Error)
                        this.referer = peachifyUrl 
                    }
                )
                return true
            }
        } catch (e: Exception) {
            println("Peachify Extraction Failed: ${e.message}")
        }
        return false
    }
}
