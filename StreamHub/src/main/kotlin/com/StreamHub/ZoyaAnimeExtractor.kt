package com.StreamHub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

object ZokoAnimeExtractor {

    private const val BASE_URL = "https://zokoanime.video"

    /**
     * Fetches streaming links from ZokoAnime for a given anime episode.
     *
     * @param malId      MyAnimeList ID (preferred)
     * @param anilistId  AniList ID (fallback)
     * @param episode    Episode number
     * @param callback   Cloudstream callback to emit ExtractorLink objects
     */
    suspend fun getStreams(
        malId: String?,
        anilistId: String?,
        episode: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (malId != null && trySource("mal", malId, episode, callback)) return true
        if (anilistId != null && trySource("anilist", anilistId, episode, callback)) return true
        return false
    }

    private suspend fun trySource(
        source: String,
        id: String,
        episode: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Try sub first, then dub
        for (track in listOf("sub", "dub")) {
            val url = "$BASE_URL/stream/$source/$id/$episode/$track"
            try {
                val response = app.get(
                    url,
                    timeout = 15L,
                    headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                    )
                )
                if (response.code == 200 && extractStreamFromHtml(response.text, url, track)) {
                    return true
                }
            } catch (_: Exception) {
                // Try next track
            }
        }
        return false
    }

    /**
     * Parses the ZokoAnime embed HTML page to find the actual video source URL.
     */
    private fun extractStreamFromHtml(html: String, referer: String, track: String): Boolean {
        var found = false

        // 1. Direct <source> or <video> tags
        val sourceRegex = Regex(
            """<(?:source|video)[^>]+src=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        sourceRegex.findAll(html).forEach { match ->
            val src = match.groupValues[1]
            if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                emitLink(src, referer, track)
                found = true
            }
        }

        // 2. JSON config blocks (file, src, url keys)
        val jsonRegex = Regex(
            """["'](?:file|src|url|source)["']\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        jsonRegex.findAll(html).forEach { match ->
            val src = match.groupValues[1]
            if (src.isNotBlank()) {
                emitLink(src, referer, track)
                found = true
            }
        }

        // 3. Base64-encoded embed data
        val b64Regex = Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""")
        b64Regex.findAll(html).forEach { match ->
            try {
                val decoded = String(
                    android.util.Base64.decode(match.groupValues[1], android.util.Base64.DEFAULT)
                )
                val innerRegex = Regex(
                    """["'](?:file|src|url)["']\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']"""
                )
                innerRegex.findAll(decoded).forEach { inner ->
                    emitLink(inner.groupValues[1], referer, track)
                    found = true
                }
            } catch (_: Exception) {}
        }

        return found
    }

    private fun emitLink(url: String, referer: String, track: String) {
        val linkType = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val qualityLabel = if (track == "dub") "Dub" else "Sub"

        newExtractorLink(
            source = "ZokoAnime",
            name   = "ZokoAnime $qualityLabel",
            url    = url,
            type   = linkType
        ) {
            this.referer = referer
        }.also { callback(it) }
    }
}
