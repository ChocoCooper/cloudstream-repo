package com.StreamHub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

object ZokoAnimeExtractor {

    private const val BASE_URL = "https://zokoanime.video"

    /**
     * Fetches streaming links from ZokoAnime using an AniList ID.
     *
     * @param anilistId AniList ID of the anime
     * @param episode   Episode number
     * @param callback  Cloudstream callback to emit ExtractorLink objects
     */
    suspend fun getStreams(
        anilistId: String,
        episode: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return trySource("anilist", anilistId, episode, callback)
    }

    private suspend fun trySource(
        source: String,
        id: String,
        episode: Int,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        for (track in listOf("sub", "dub")) {
            val url = "$BASE_URL/stream/$source/$id/$episode/$track"
            try {
                val response = app.get(
                    url,
                    timeout = 15L,
                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                )
                if (response.code == 200 && extractStreamFromHtml(response.text, url, track, callback)) {
                    return true
                }
            } catch (_: Exception) { }
        }
        return false
    }

    private suspend fun extractStreamFromHtml(
        html: String,
        referer: String,
        track: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val sourceRegex = Regex("""<(?:source|video)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        sourceRegex.findAll(html).forEach { match ->
            val src = match.groupValues[1]
            if (src.isNotBlank() && (src.startsWith("http") || src.startsWith("//"))) {
                emitLink(src, referer, track, callback)
                found = true
            }
        }

        val jsonRegex = Regex("""["'](?:file|src|url|source)["']\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
        jsonRegex.findAll(html).forEach { match ->
            val src = match.groupValues[1]
            if (src.isNotBlank()) {
                emitLink(src, referer, track, callback)
                found = true
            }
        }

        val b64Regex = Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""")
        b64Regex.findAll(html).forEach { match ->
            try {
                val decoded = String(android.util.Base64.decode(match.groupValues[1], android.util.Base64.DEFAULT))
                val innerRegex = Regex("""["'](?:file|src|url)["']\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                innerRegex.findAll(decoded).forEach { inner ->
                    emitLink(inner.groupValues[1], referer, track, callback)
                    found = true
                }
            } catch (_: Exception) {}
        }
        return found
    }

    private suspend fun emitLink(url: String, referer: String, track: String, callback: (ExtractorLink) -> Unit) {
        val linkType = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val qualityLabel = if (track == "dub") "Dub" else "Sub"

        val link = newExtractorLink(
            source = "ZokoAnime",
            name   = "ZokoAnime $qualityLabel",
            url    = url,
            type   = linkType
        ) { this.referer = referer }
        callback(link)
    }
}
