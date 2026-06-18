package com.StreamHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

object PeachifyExtractor {

    suspend fun getStream(dataUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        var foundStream = false
        val tmdbId = dataUrl.substringAfterLast("/")
        val isMovie = dataUrl.contains("/movie/")
        
        val type = if (isMovie) "movie" else "tv" // Used if they use standard API paths

        // 1. First, we try hitting their backend APIs directly to see if they return the multi-audio JSON
        val apiHosts = listOf("https://uwu.eat-peach.sbs", "https://usa.eat-peach.sbs")
        val serverPaths = listOf("moviebox", "holly", "air", "multi", "net")
        
        for (host in apiHosts) {
            for (path in serverPaths) {
                // These are the most common URL structures for this specific Next.js template
                val potentialEndpoints = listOf(
                    "$host/api/source/$path?id=$tmdbId&type=$type",
                    "$host/$path/$tmdbId",
                    "$host/api/embed/$path/$tmdbId"
                )

                for (endpoint in potentialEndpoints) {
                    try {
                        val response = app.get(endpoint)
                        // If it returns a 200 OK and contains m3u8, we found their hidden API!
                        if (response.code == 200 && response.text.contains(".m3u8")) {
                            println("🔥 Found direct API endpoint: $endpoint")
                            // TODO: If this hits, we will parse the JSON for the audio tracks here!
                            // For now, we are just looking for the M3U8 string.
                            
                            val rawText = response.text
                            // Quick regex to snatch the m3u8 out of the raw response text
                            val m3u8Link = Regex("""(https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*)""").find(rawText)?.value
                            
                            if (m3u8Link != null) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "Peachify",
                                        name = path.replaceFirstChar { it.uppercase() } + " API",
                                        url = m3u8Link,
                                        type = ExtractorLinkType.M3U8
                                    )
                                )
                                foundStream = true
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore 404/403 errors and keep trying
                    }
                }
            }
        }

        // 2. If the API shotgun fails (or we just want a backup), we fall back to the trusty WebView
        if (!foundStream) {
            try {
                val peachifyUrl = dataUrl.replace("111movies.net/movie", "peachify.top/embed/movie")
                                         .replace("111movies.net/tv", "peachify.top/embed/tv")

                val targetRegex = Regex(""".*\.m3u8.*""")
                val interceptor = WebViewResolver(targetRegex)

                val response = app.get(peachifyUrl, interceptor = interceptor)
                val interceptedUrl = response.url

                if (interceptedUrl.contains(".m3u8")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "Peachify",
                            name = "Auto Server (WebView)",
                            url = interceptedUrl,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    foundStream = true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return foundStream
    }
}
