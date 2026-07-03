package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlin.math.abs

object OnetouchtvExtractor {

    private const val BASE_URL = "https://api3.devcorp.me"
    private const val DEC_API = "https://enc-dec.app/api/dec-onetouchtv"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    // ── Data Classes ──────────────────────────────────────────────────────────

    private data class OtSearchRoot(
        @param:JsonProperty("result") val result: List<OtSearchResult>?
    )

    private data class OtSearchResult(
        @param:JsonProperty("id") val id: Int?,
        @param:JsonProperty("title") val title: String?,
        @param:JsonProperty("year") val year: String?,
        @param:JsonProperty("otherTitles") val otherTitles: List<String>?
    )

    private data class OtDetail(
        @param:JsonProperty("id") val id: Int?,
        @param:JsonProperty("episodes") val episodes: List<OtEpisode>?
    )

    private data class OtEpisode(
        @param:JsonProperty("identifier") val identifier: String?,
        @param:JsonProperty("playId") val playId: Int?,
        @param:JsonProperty("episode") val episode: Double? // Parsed as double for absolute/fractional numbering
    )

    private data class OtStreamResult(
        @param:JsonProperty("sources") val sources: List<OtSource>?,
        @param:JsonProperty("track") val track: List<OtTrack>?,
        @param:JsonProperty("tracks") val tracks: List<OtTrack>?
    )

    private data class OtStreamResponse(
        @param:JsonProperty("result") val result: OtStreamResult?
    )

    private data class OtSource(
        @param:JsonProperty("name") val name: String?,
        @param:JsonProperty("quality") val quality: String?,
        @param:JsonProperty("type") val type: String?,
        @param:JsonProperty("url") val url: String?,
        @param:JsonProperty("headers") val headers: Map<String, String>?
    )

    private data class OtTrack(
        @param:JsonProperty("name") val name: String?,
        @param:JsonProperty("code") val code: String?,
        @param:JsonProperty("file") val file: String?
    )

    // ── Core Entry Point ──────────────────────────────────────────────────────

    suspend fun getStream(
        title: String,
        year: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // 1. Search OneTouchTV (Passes map directly to encode in OkHttp)
            val searchResText = app.get(
                "$BASE_URL/vod/search",
                params = mapOf("page" to "1", "keyword" to title),
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 15L
            ).text

            // 2. Decrypt Search Results
            val decSearchText = decrypt(searchResText) ?: return false
            val otResults = tryParseJson<OtSearchRoot>(decSearchText)?.result 
                ?: tryParseJson<List<OtSearchResult>>(decSearchText) 
                ?: return false

            if (otResults.isEmpty()) return false

            // 3. Match the correct entry using Python's Smart Match Algorithm
            val match = findMatchInResults(otResults, title, year) ?: return false
            val mediaId = match.id ?: return false

            // 4. Detail Fetch
            val detailResText = app.get(
                "$BASE_URL/vod/$mediaId/detail",
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 15L
            ).text

            val decDetailText = decrypt(detailResText) ?: return false
            val detailData = tryParseJson<OtDetail>(decDetailText) ?: return false
            val episodes = detailData.episodes ?: emptyList()

            if (episodes.isEmpty()) return false

            // 5. Select Episode
            val selectedEp = if (season == null) {
                // Movie
                episodes.firstOrNull()
            } else {
                // TV Series
                episodes.find { it.episode?.toInt() == episode }
            } ?: return false

            val identifier = selectedEp.identifier ?: return false
            val playId = selectedEp.playId ?: return false

            // 6. Final Stream API Fetch
            val streamResText = app.get(
                "$BASE_URL/vod/$identifier/episode/$playId",
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 15L
            ).text

            val decStreamText = decrypt(streamResText) ?: return false
            val streamRoot = tryParseJson<OtStreamResponse>(decStreamText)?.result 
                ?: tryParseJson<OtStreamResult>(decStreamText) 
                ?: return false

            // 7. Extract Sources
            val sources = streamRoot.sources ?: emptyList()
            sources.forEach { src ->
                val linkUrl = src.url ?: return@forEach
                if (linkUrl.isNotBlank()) {
                    val srcHeaders = src.headers?.toMutableMap() ?: mutableMapOf()
                    if (!srcHeaders.containsKey("User-Agent")) {
                        srcHeaders["User-Agent"] = USER_AGENT
                    }
                    callback.invoke(
                        newExtractorLink(
                            "OneTouchTv",
                            "OneTouchTv",
                            linkUrl,
                            INFER_TYPE
                        ) {
                            quality = Qualities.Unknown.value // Prevents appending random qualities to title
                            headers = srcHeaders
                        }
                    )
                }
            }

            // 8. Extract Subtitles (English will be filtered safely by the Provider mappedCallback)
            val tracks = streamRoot.track ?: streamRoot.tracks ?: emptyList()
            tracks.forEach { track ->
                val trackUrl = track.file ?: return@forEach
                val langLabel = track.name ?: track.code ?: "Unknown"
                if (trackUrl.isNotBlank()) {
                    subtitleCallback.invoke(SubtitleFile(langLabel, trackUrl))
                }
            }

            return sources.isNotEmpty()
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Decrypts payloads exactly like the Python tester, flexibly returning JSON content as a String
     */
    private suspend fun decrypt(encryptedText: String): String? {
        try {
            val responseText = app.post(
                DEC_API,
                headers = mapOf("User-Agent" to USER_AGENT),
                json = mapOf("text" to encryptedText),
                timeout = 15L
            ).text

            val jsonNode = tryParseJson<JsonNode>(responseText)
            if (jsonNode?.get("status")?.asInt() == 200) {
                val resultNode = jsonNode.get("result")
                return if (resultNode != null && !resultNode.isNull) {
                    if (resultNode.isTextual) resultNode.asText() else resultNode.toString()
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    private fun String.normalizeTitle(): String {
        return this.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Performs an exact, substring, or alias match ported closely from Python's find_match_in_results.
     */
    private fun findMatchInResults(
        results: List<OtSearchResult>,
        targetTitle: String,
        targetYear: String?
    ): OtSearchResult? {
        val normTarget = targetTitle.normalizeTitle()
        var exactMatch: OtSearchResult? = null
        var fallbackMatch: OtSearchResult? = null

        for (res in results) {
            val resTitle = res.title ?: continue
            val normRes = resTitle.normalizeTitle()
            val resYear = res.year?.trim()

            val otherTitles = res.otherTitles?.map { it.normalizeTitle() } ?: emptyList()

            // Python Logic: Smart Title Matching
            val titleMatched = normRes == normTarget ||
                    normRes.contains(normTarget) ||
                    normTarget.contains(normRes) ||
                    otherTitles.contains(normTarget) ||
                    otherTitles.any { it.contains(normTarget) || (it.length > 3 && normTarget.contains(it)) }

            if (titleMatched) {
                if (fallbackMatch == null) {
                    fallbackMatch = res
                }

                if (targetYear != null && targetYear != "N/A" && !resYear.isNullOrBlank() && resYear != "None") {
                    val rYear = resYear.toIntOrNull()
                    val tYear = targetYear.toIntOrNull()

                    if (rYear != null && tYear != null) {
                        if (abs(rYear - tYear) <= 1) {
                            exactMatch = res
                            break
                        }
                    } else if (resYear == targetYear) {
                        exactMatch = res
                        break
                    }
                } else {
                    exactMatch = res
                    break
                }
            }
        }

        return exactMatch ?: fallbackMatch
    }
}
