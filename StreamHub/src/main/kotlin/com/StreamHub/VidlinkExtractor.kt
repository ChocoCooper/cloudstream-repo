package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidlinkExtractor {

    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Convert the master StreamHub URL to the Vidlink embed URL
            val vidlinkUrl = dataUrl.replace("111movies.net", "vidlink.pro")

            // Regex targeting the specific proxy domain you caught in catcatch
            val targetRegex = Regex(""".*vodvidl\.site.*\.m3u8.*""")
            val interceptor = WebViewResolver(targetRegex)

            // Execute the headless browser
            val response = app.get(vidlinkUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            // If we successfully caught the proxied megacloud link
            if (interceptedUrl.contains("vodvidl") || interceptedUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        source = "Vidlink",
                        name = "Vidlink",
                        url = interceptedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        // Crucial: Your catcatch log showed Megacloud demands this exact referer
                        this.referer = "https://megacloud.live/" 
                    }
                )
                return true
            }
        } catch (e: Exception) {
            // Silently fail to allow the next extractor to run
        }
        return false
    }
}
