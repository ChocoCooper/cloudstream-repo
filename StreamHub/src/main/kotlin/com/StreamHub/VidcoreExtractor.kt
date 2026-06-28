package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidcoreExtractor {
    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val domains = listOf("https://vidcore.net", "https://vidup.to")
        
        // DYNAMIC: Catch any m3u8 or mp4 network request
        val catchAllRegex = Regex("""(?i).*\.(m3u8|mp4).*""")
        
        // BLACKLIST: Ignore common ad trackers and blank placeholders
        val blacklist = listOf("youtube", "google", "doubleclick", "analytics", "blank.mp4", "googletagmanager", "cloudflare")
        
        for (domain in domains) {
            try {
                val targetUrl = dataUrl.replace("https://streamhub.app", domain)
                val interceptor = WebViewResolver(catchAllRegex)

                val response = app.get(targetUrl, interceptor = interceptor)
                val interceptedUrl = response.url

                // Verify it's not blacklisted
                val isClean = blacklist.none { interceptedUrl.lowercase().contains(it) }

                if (isClean) {
                    val linkType = if (interceptedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val sourceName = if (domain.contains("vidcore")) "Vidcore" else "Vidup"
                    
                    callback.invoke(
                        ExtractorLink(
                            source = sourceName, 
                            name = sourceName, 
                            url = interceptedUrl, 
                            referer = "$domain/",
                            quality = Qualities.P1080.value, // Added 1080p flag to trigger immediate auto-play priority
                            type = linkType
                        )
                    )
                    return true 
                }
            } catch (e: Exception) {
                continue // Try the fallback domain
            }
        }
        return false 
    }
}
