package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidlinkExtractor {

    private val blacklist = listOf(
        "youtube", "google", "doubleclick", "analytics",
        "blank.mp4", "googletagmanager", "cloudflare"
    )

    /**
     * @param embedData  Clean URL in the form "https://streamhub.app/movie/<id>"
     *                   or "https://streamhub.app/tv/<id>/<season>/<episode>".
     *                   Must NOT contain ?imdb= or any extra query parameters.
     */
    suspend fun getStream(embedData: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val vidlinkUrl = embedData.replace("https://streamhub.app", "https://vidlink.pro")

            // Catch any m3u8 or mp4 network request made by the page
            val catchAllRegex = Regex("""(?i).*\.(m3u8|mp4).*""")
            val interceptor   = WebViewResolver(catchAllRegex)

            val response      = app.get(vidlinkUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            val isClean = blacklist.none { interceptedUrl.lowercase().contains(it) }
            if (!isClean) return false

            val linkType = if (interceptedUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                           else ExtractorLinkType.VIDEO

            callback.invoke(
                ExtractorLink(
                    source  = "Vidlink",
                    name    = "Vidlink",
                    url     = interceptedUrl,
                    referer = "https://vidlink.pro/",
                    quality = Qualities.Unknown.value,
                    type    = linkType
                )
            )
            return true
        } catch (e: Exception) {
            // Silently fail on timeout or WebView error
        }
        return false
    }
}
