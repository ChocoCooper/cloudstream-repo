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

                // Verify it's a media file and not blacklisted
                val isMediaFile = interceptedUrl.contains(".m3u8") || interceptedUrl.contains(".mp4")
                val isClean = blacklist.none { interceptedUrl.contains(it) }

                if (isMediaFile && isClean) {
                    val linkType = if (interceptedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    val sourceName = if (domain.contains("vidcore")) "Vidcore" else "Vidup"
                    
                    callback.invoke(
                        ExtractorLink(
                            source = sourceName, 
                            name = sourceName, 
                            url = interceptedUrl, 
                            referer = "$domain/",
                            quality = Qualities.Unknown.value,
                            type = linkType
                        )
                    )
                    return true 
                }
            } catch (e: Exception) {
                continue // Try the next domain in the list
            }
        }
        return false 
    }
}
