package com.JavHub

import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.parser.Parser

/**
 * Common interface for all JAV extractors.
 * Each extractor receives a lowercased code (e.g. "dic-094") and reports
 * any streams it finds through the [callback].
 */
interface JavExtractor {
    val name: String
    suspend fun extract(code: String, callback: (ExtractorLink) -> Unit): Boolean
}

/**
 * Shared browser headers used by every extractor.
 */
internal val BROWSER_HEADERS: Map<String, String> = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"
)

/**
 * Converts direct video streams to master playlists if needed.
 */
internal fun String.toPlaylistM3u8(): String {
    val perResolutionSegment = Regex("""/(?:\d{3,5}x\d{3,5}|\d{3,4}p)/[^/]+\.m3u8""")
    return when {
        perResolutionSegment.containsMatchIn(this) ->
            perResolutionSegment.replace(this, "/playlist.m3u8")
        this.contains("/video.m3u8") ->
            this.replace(Regex("""/video\.m3u8(\?.*)?$"""), "/playlist.m3u8$1")
        else -> this
    }
}

/**
 * Unescape common JavaScript string escapes.
 */
internal fun jsUnescape(s: String): String {
    var result = s.replace("\\/", "/")
    result = Regex("""\\u([0-9a-fA-F]{4})""").replace(result) { m ->
        m.groupValues[1].toInt(16).toChar().toString()
    }
    result = Regex("""\\x([0-9a-fA-F]{2})""").replace(result) { m ->
        m.groupValues[1].toInt(16).toChar().toString()
    }
    return result
}

/**
 * Decode HTML entities in a string.
 */
internal fun String.decodeHtmlEntities(): String = Parser.unescapeEntities(this, false)
