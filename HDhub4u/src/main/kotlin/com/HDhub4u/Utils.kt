package com.hdhub4u

import android.annotation.SuppressLint
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.gson.annotations.SerializedName
import com.lagradost.api.Log
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

// ============================================================================
// FILE SIZE GUARD
// ----------------------------------------------------------------------------
// FIX: nothing in the original code ever used the scraped "size" text for
// anything other than a cosmetic label. That's why 10-20GB remuxes were
// being handed straight to the player. This block adds a real, reusable
// size parser + threshold check that every extractor now calls before
// emitting a link.
//
// Tune MAX_FILE_SIZE_GB to taste. 8GB comfortably covers 1080p WEB-DL/BluRay
// encodes while blocking most raw remux / 4K-remux dumps that choke mobile
// playback and buffering.
// ============================================================================
const val MAX_FILE_SIZE_GB = 8.0

/**
 * Parses strings like "1.4GB", "850 MB", "2.1 TB" into a GB double.
 * Returns null if no recognizable size token is found (caller should treat
 * "unknown size" as "allow" rather than silently dropping every link).
 */
fun parseSizeToGB(sizeStr: String?): Double? {
    if (sizeStr.isNullOrBlank()) return null
    val match = Regex("""([\d.]+)\s*(TB|GB|MB)""", RegexOption.IGNORE_CASE).find(sizeStr)
        ?: return null
    val value = match.groupValues[1].toDoubleOrNull() ?: return null
    return when (match.groupValues[2].uppercase()) {
        "TB" -> value * 1024
        "GB" -> value
        "MB" -> value / 1024
        else -> null
    }
}

/**
 * True only when we could confidently parse a size AND it exceeds the cap.
 * Unknown/unparseable sizes are allowed through rather than blocked, so a
 * markup change on the site doesn't silently kill every link.
 */
fun isFileTooLarge(sizeStr: String?, maxGB: Double = MAX_FILE_SIZE_GB): Boolean {
    val gb = parseSizeToGB(sizeStr) ?: return false
    return gb > maxGB
}

// ============================================================================
// REDIRECT / DECODE HELPERS (unchanged logic, hardened error handling)
// ============================================================================
suspend fun getRedirectLinks(url: String): String? {
    return try {
        val doc = app.get(url).text
        val regex = "s\\('o','([A-Za-z0-9+/=]+)'|ck\\('_wp_http_\\d+','([^']+)'".toRegex()
        val combinedString = buildString {
            regex.findAll(doc).forEach { matchResult ->
                val extractedValue = matchResult.groups[1]?.value ?: matchResult.groups[2]?.value
                if (!extractedValue.isNullOrEmpty()) append(extractedValue)
            }
        }
        if (combinedString.isBlank()) return url

        val decodedString = base64Decode(pen(base64Decode(base64Decode(combinedString))))
        val jsonObject = JSONObject(decodedString)
        val encodedurl = base64Decode(jsonObject.optString("o", "")).trim()
        val data = encode(jsonObject.optString("data", "")).trim()
        val wphttp1 = jsonObject.optString("blog_url", "").trim()

        val directlink = if (wphttp1.isNotBlank()) {
            runCatching {
                app.get("$wphttp1?re=$data".trim(), timeout = 15000L)
                    .document.select("body").text().trim()
            }.getOrDefault("")
        } else ""

        encodedurl.ifEmpty { directlink.ifEmpty { url } }
    } catch (e: Exception) {
        Log.e("HDhub4u-Redirect", "Error processing links for $url: ${e.message}")
        url // Fail safe: return original url instead of null so callers can still try it
    }
}

@SuppressLint("NewApi")
fun encode(value: String): String {
    return try {
        String(android.util.Base64.decode(value, android.util.Base64.DEFAULT))
    } catch (e: Exception) {
        Log.e("HDhub4u-Encode", "Base64 decode failed: ${e.message}")
        ""
    }
}

fun pen(value: String): String {
    return value.map {
        when (it) {
            in 'A'..'Z' -> ((it - 'A' + 13) % 26 + 'A'.code).toChar()
            in 'a'..'z' -> ((it - 'a' + 13) % 26 + 'a'.code).toChar()
            else -> it
        }
    }.joinToString("")
}

/**
 * FIX: previously launched a *new, untracked* CoroutineScope(Dispatchers.IO)
 * for every single link emitted by loadExtractor, with zero error handling.
 * A failure inside that scope would crash silently or leak. This version
 * runs inline (loadExtractor's callback context is already a coroutine) and
 * wraps in try/catch so one bad link can't take out the whole chain.
 */
suspend fun loadSourceNameExtractor(
    source: String,
    url: String,
    referer: String? = null,
    quality: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit,
) {
    if (url.isBlank()) return
    try {
        loadExtractor(url, referer, subtitleCallback) { link ->
            callback.invoke(
                newExtractorLink(
                    "${link.source} $source",
                    "${link.source} $source",
                    link.url,
                ) {
                    this.quality = quality ?: link.quality
                    this.type = link.type
                    this.referer = link.referer
                    this.headers = link.headers
                    this.extractorData = link.extractorData
                }
            )
        }
    } catch (e: Exception) {
        Log.e("HDhub4u-LoadExtractor", "Failed for $url via $source: ${e.message}")
    }
}

data class IMDB(
    @SerializedName("imdb_id")
    val imdbId: String? = null
)

fun cleanTitle(raw: String): String {
    val name = raw.substringBefore("(").trim()
        .replace(Regex("""\s+"""), " ") // collapse extra spaces
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

    val seasonRegex = Regex("""Season\s*\d+""", RegexOption.IGNORE_CASE)
    val yearRegex = Regex("""\b(19|20)\d{2}\b""")

    val season = seasonRegex.find(raw)?.value?.replaceFirstChar { it.uppercase() }
    val year = yearRegex.find(raw)?.value

    val parts = mutableListOf<String>()
    if (season != null) parts += season
    if (year != null) parts += year

    return if (parts.isEmpty()) {
        name
    } else {
        name + parts.joinToString("") { " ($it)" }
    }
}

data class ResponseDataLocal(val meta: MetaLocal?)

data class MetaLocal(
    val name: String? = null,
    val description: String? = null,
    val actorsData: List<ActorData>? = null,
    val year: String? = null,
    val background: String? = null,
    val genres: List<String>? = null,
    val videos: List<VideoLocal>? = null,
    val rating: Score?,
    val logo: String?
)

data class VideoLocal(
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
    val released: String? = null,
    val rating: Score?
)

data class Search(
    @param:JsonProperty("facet_counts")
    val facetCounts: List<Any?>? = null,
    val found: Long? = null,
    val hits: List<Hit>? = null,
    @param:JsonProperty("out_of")
    val outOf: Long? = null,
    val page: Long? = null,
    @param:JsonProperty("request_params")
    val requestParams: RequestParams? = null,
    @param:JsonProperty("search_cutoff")
    val searchCutoff: Boolean? = null,
    @param:JsonProperty("search_time_ms")
    val searchTimeMs: Long? = null,
)

data class Hit(
    val document: SearchDocument? = null,
    val highlight: Map<String, Any>? = null,
    val highlights: List<Any?>? = null,
    @param:JsonProperty("text_match")
    val textMatch: Long? = null,
    @param:JsonProperty("text_match_info")
    val textMatchInfo: TextMatchInfo? = null,
)

// FIX: renamed from "Document" to "SearchDocument". The old name collided
// (by name only) with org.jsoup.nodes.Document used throughout the provider
// for HTML parsing. It happened to compile because jsoup's Document wasn't
// explicitly imported in the same file, but that's fragile — any future
// import of org.jsoup.nodes.Document anywhere that also sees this class via
// wildcard/package resolution turns into a same-package ambiguity error.
data class SearchDocument(
    val category: List<String>? = null,
    val id: String? = null,
    val permalink: String? = null,
    @param:JsonProperty("post_date")
    val postDate: String? = null,
    @param:JsonProperty("post_thumbnail")
    val postThumbnail: String? = null,
    @param:JsonProperty("post_title")
    val postTitle: String? = null,
    @param:JsonProperty("post_type")
    val postType: String? = null,
    @param:JsonProperty("sort_by_date")
    val sortByDate: Long? = null,
)

data class TextMatchInfo(
    @param:JsonProperty("best_field_score")
    val bestFieldScore: String? = null,
    @param:JsonProperty("best_field_weight")
    val bestFieldWeight: Long? = null,
    @param:JsonProperty("fields_matched")
    val fieldsMatched: Long? = null,
    @param:JsonProperty("num_tokens_dropped")
    val numTokensDropped: Long? = null,
    val score: String? = null,
    @param:JsonProperty("tokens_matched")
    val tokensMatched: Long? = null,
    @param:JsonProperty("typo_prefix_score")
    val typoPrefixScore: Long? = null,
)

data class RequestParams(
    @param:JsonProperty("collection_name")
    val collectionName: String? = null,
    @param:JsonProperty("first_q")
    val firstQ: String? = null,
    @param:JsonProperty("per_page")
    val perPage: Long? = null,
    val q: String? = null,
)
