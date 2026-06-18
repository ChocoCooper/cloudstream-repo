package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidnestExtractor {

    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Replace the generic Hub URL with the Vidnest domain
            val vidnestUrl = dataUrl.replace("https://streamhub.app", "https://vidnest.fun")

            // Regex targeting the animanga.fun or 1x2.space CDNs you found in catcatch
            val targetRegex = Regex(""".*(animanga\.fun|1x2\.space).*\.m3u8.*""")
            val interceptor = WebViewResolver(targetRegex)

            val response = app.get(vidnestUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            if (interceptedUrl.contains("animanga") || interceptedUrl.contains("1x2") || interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = "Vidnest",
                        name = "Vidnest",
                        url = interceptedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://vidnest.fun/" 
                    }
                )
                return true
            }
        } catch (e: Exception) {
            // Silently fail to allow other extractors to run
        }
        return false
    }
}
