package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidcoreExtractor {

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
        val domains = listOf("https://vidcore.net", "https://vidup.to")

        // Catch any m3u8 or mp4 network request made by the page
        val catchAllRegex = Regex("""(?i).*\.(m3u8|mp4).*""")

        for (domain in domains) {
            try {
                val targetUrl     = embedData.replace("https://streamhub.app", domain)
                val interceptor   = WebViewResolver(catchAllRegex)
                val response      = app.get(targetUrl, interceptor = interceptor)
                val interceptedUrl = response.url

                val isClean = blacklist.none { interceptedUrl.lowercase().contains(it) }
                if (!isClean) continue

                val linkType   = if (interceptedUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                                 else ExtractorLinkType.VIDEO
                val sourceName = if (domain.contains("vidcore")) "Vidcore" else "Vidup"

                callback.invoke(
                    ExtractorLink(
                        source  = sourceName,
                        name    = sourceName,
                        url     = interceptedUrl,
                        referer = "$domain/",
                        quality = Qualities.Unknown.value,
                        type    = linkType
                    )
                )
                return true          // stop after first successful domain
            } catch (e: Exception) {
                continue             // try the fallback domain
            }
        }
        return false
    }
}
