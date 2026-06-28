package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object Movies111Extractor {
    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val embedUrl = dataUrl.replace("https://streamhub.app", "https://111movies.net")
            
            // DYNAMIC: Catch any m3u8 or mp4 network request
            val catchAllRegex = Regex("""(?i).*\.(m3u8|mp4).*""")
            
            // BLACKLIST: Ignore common ad trackers and blank placeholders
            val blacklist = listOf("youtube", "google", "doubleclick", "analytics", "blank.mp4", "googletagmanager", "cloudflare")
            
            val interceptor = WebViewResolver(catchAllRegex)
            val response = app.get(embedUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            // Verify it's not blacklisted
            val isClean = blacklist.none { interceptedUrl.lowercase().contains(it) }

            if (isClean) {
                val linkType = if (interceptedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                
                callback.invoke(
                    ExtractorLink(
                        source = "111movies", 
                        name = "111movies", 
                        url = interceptedUrl, 
                        referer = "https://111movies.net/",
                        quality = Qualities.Unknown.value,
                        type = linkType
                    )
                )
                return true
            }
        } catch (e: Exception) {
            // Silently fail on timeout
        }
        return false
    }
}
