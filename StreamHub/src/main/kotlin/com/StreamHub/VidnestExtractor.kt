package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object VidnestExtractor {

    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            // Force the URL into the /embed/ path to try and trigger auto-play
            // Bypasses the main UI "Click to Play" requirement
            val vidnestUrl = dataUrl
                .replace("https://streamhub.app/movie/", "https://vidnest.fun/embed/movie/")
                .replace("https://streamhub.app/tv/", "https://vidnest.fun/embed/tv/")

            // Broadened Regex targeting the CDNs or just raw m3u8 playlists
            val targetRegex = Regex(""".*(animanga\.fun|1x2\.space|m3u8).*""")
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
