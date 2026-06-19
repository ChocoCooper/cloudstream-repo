package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidcoreExtractor {
    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        // Primary server first, fallback server second
        val domains = listOf("https://vidcore.net", "https://vidup.to")
        val targetRegex = Regex(""".*digitalsun\.app.*\.m3u8.*""")
        
        for (domain in domains) {
            try {
                // Swap the dummy URL for the current domain in the loop
                val targetUrl = dataUrl.replace("https://streamhub.app", domain)
                val interceptor = WebViewResolver(targetRegex)

                val response = app.get(targetUrl, interceptor = interceptor)
                val interceptedUrl = response.url

                if (interceptedUrl.contains("digitalsun.app") || interceptedUrl.contains(".m3u8")) {
                    // Dynamically name the source based on which domain succeeded
                    val sourceName = if (domain.contains("vidcore")) "Vidcore" else "Vidup"
                    
                    callback.invoke(
                        newExtractorLink(
                            source = sourceName, 
                            name = sourceName, 
                            url = interceptedUrl, 
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$domain/" 
                        }
                    )
                    // Success! Return true to break the loop so it doesn't run the fallback
                    return true 
                }
            } catch (e: Exception) {
                // If it crashes or times out, silently continue to the next domain (Fallback)
                continue
            }
        }
        // Returns false only if ALL domains in the list failed
        return false 
    }
}
