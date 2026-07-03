package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object Movies111Extractor {

    private val blacklist = listOf(
        "youtube", "google", "doubleclick", "analytics",
        "blank.mp4", "googletagmanager", "cloudflare"
    )

    /**
     * @param embedData  Clean URL in the form "https://streamhub.app/movie/<id>"
     *                   or "https://streamhub.app/tv/<id>/<season>/<episode>".
     *                   Must NOT contain ?imdb= or any extra query parameters,
     *                   because 111movies doesn't expect them.
     */
    suspend fun getStream(embedData: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val embedUrl = embedData.replace("https://streamhub.app", "https://111movies.net")

            // Catch any m3u8 or mp4 network request made by the page
            val catchAllRegex = Regex("""(?i).*\.(m3u8|mp4).*""")
            val interceptor   = WebViewResolver(catchAllRegex)

            val response      = app.get(embedUrl, interceptor = interceptor)
            val interceptedUrl = response.url

            // WebViewResolver falls back to returning the last-loaded page URL when it
            // never actually caught a network request matching catchAllRegex (player
            // failed to init, site changed its streaming mechanism, request was too
            // slow, etc). The blacklist below only screens out known junk domains — it
            // does NOT guarantee the URL is a real media file — so without this check
            // the raw embed page HTML can slip through as a "stream" and get handed to
            // ExoPlayer, which then fails to demux it (CloudStream error 3003 /
            // UnrecognizedInputFormatException, exactly as seen in the logs).
            if (interceptedUrl.isBlank() ||
                interceptedUrl == embedUrl ||
                !catchAllRegex.matches(interceptedUrl)
            ) {
                return false
            }

            val isClean = blacklist.none { interceptedUrl.lowercase().contains(it) }
            if (!isClean) return false

            val linkType = if (interceptedUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                           else ExtractorLinkType.VIDEO

            callback.invoke(
                ExtractorLink(
                    source   = "111movies",
                    name     = "111movies",
                    url      = interceptedUrl,
                    referer  = "https://111movies.net/",
                    quality  = Qualities.Unknown.value,
                    type     = linkType
                )
            )
            return true
        } catch (e: Exception) {
            // Silently fail on timeout or WebView error
        }
        return false
    }
}
