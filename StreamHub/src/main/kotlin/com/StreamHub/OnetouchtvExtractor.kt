package com.StreamHub

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import kotlin.math.abs

object OnetouchtvExtractor {

    private const val BASE_URL = "https://api3.devcorp.me"
    private const val DEC_API = "https://enc-dec.app/api/dec-onetouchtv"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    suspend fun getStream(
        title: String,
        year: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            
            // 1. Search OneTouchTV 
            val searchResText = app.get(
                "$BASE_URL/vod/search?page=1&keyword=$encodedTitle",
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 30L
            ).text

            // 2. Decrypt Search Results
            val decSearchText = decrypt(searchResText) ?: return false
            var otResultsNode = tryParseJson<JsonNode>(decSearchText) ?: return false
            
            if (otResultsNode.isObject && otResultsNode.has("result")) {
                otResultsNode = otResultsNode.get("result")
            }
            if (!otResultsNode.isArray || otResultsNode.isEmpty) return false

            // 3. Match the correct entry using Python's Smart Match Algorithm
            var exactMatch: JsonNode? = null
            var fallbackMatch: JsonNode? = null
            val normTarget = title.normalizeTitle()
            
            for (res in otResultsNode) {
                val resTitle = res.get("title")?.asText() ?: continue
                val normRes = resTitle.normalizeTitle()
                val resYear = res.get("year")?.asText()?.trim()
                
                val otherTitles = mutableListOf<String>()
                val otherTitlesNode = res.get("otherTitles")
                if (otherTitlesNode != null && otherTitlesNode.isArray) {
                    for (t in otherTitlesNode) {
                        otherTitles.add(t.asText().normalizeTitle())
                    }
                }
                
                val titleMatched = normRes == normTarget ||
                        normRes.contains(normTarget) ||
                        normTarget.contains(normRes) ||
                        otherTitles.contains(normTarget) ||
                        otherTitles.any { it.contains(normTarget) || (it.length > 3 && normTarget.contains(it)) }
                        
                if (titleMatched) {
                    if (fallbackMatch == null) {
                        fallbackMatch = res
                    }
                    
                    if (year != null && year != "N/A" && !resYear.isNullOrBlank() && resYear != "None") {
                        val rYear = resYear.toIntOrNull()
                        val tYear = year.toIntOrNull()
                        if (rYear != null && tYear != null) {
                            if (abs(rYear - tYear) <= 1) {
                                exactMatch = res
                                break
                            }
                        } else if (resYear == year) {
                            exactMatch = res
                            break
                        }
                    } else {
                        exactMatch = res
                        break
                    }
                }
            }

            val match = exactMatch ?: fallbackMatch ?: return false
            val mediaId = match.get("id")?.asText() ?: return false

            // 4. Detail Fetch
            val detailResText = app.get(
                "$BASE_URL/vod/$mediaId/detail",
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 30L
            ).text

            val decDetailText = decrypt(detailResText) ?: return false
            val detailData = tryParseJson<JsonNode>(decDetailText) ?: return false
            
            val episodesNode = detailData.get("episodes")
            if (episodesNode == null || !episodesNode.isArray || episodesNode.isEmpty) return false

            // 5. Select Episode
            var selectedEp: JsonNode? = null
            if (season == null) {
                selectedEp = episodesNode.get(0)
            } else {
                for (ep in episodesNode) {
                    if (ep.get("episode")?.asInt() == episode) {
                        selectedEp = ep
                        break
                    }
                }
            }
            
            if (selectedEp == null) return false
            val identifier = selectedEp.get("identifier")?.asText() ?: return false
            val playId = selectedEp.get("playId")?.asText() ?: return false

            // 6. Final Stream API Fetch
            val streamResText = app.get(
                "$BASE_URL/vod/$identifier/episode/$playId",
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 30L
            ).text

            val decStreamText = decrypt(streamResText) ?: return false
            var streamRoot = tryParseJson<JsonNode>(decStreamText) ?: return false
            if (streamRoot.isObject && streamRoot.has("result")) {
                streamRoot = streamRoot.get("result")
            }

            // 7. Extract Sources
            var foundLinks = false
            val sources = streamRoot.get("sources")
            if (sources != null && sources.isArray) {
                for (src in sources) {
                    val linkUrl = src.get("url")?.asText() ?: continue
                    if (linkUrl.isNotBlank()) {
                        val headersMap = mutableMapOf("User-Agent" to USER_AGENT)
                        val srcHeaders = src.get("headers")
                        if (srcHeaders != null && srcHeaders.isObject) {
                            srcHeaders.fieldNames().forEach { key ->
                                headersMap[key] = srcHeaders.get(key).asText()
                            }
                        }
                        
                        callback.invoke(
                            newExtractorLink(
                                "OneTouchTv",
                                "OneTouchTv",
                                linkUrl,
                                INFER_TYPE
                            ) {
                                quality = Qualities.Unknown.value
                                headers = headersMap
                            }
                        )
                        foundLinks = true
                    }
                }
            }

            // 8. Extract Subtitles (English will be filtered safely by the Provider mappedCallback)
            val tracks = streamRoot.get("track") ?: streamRoot.get("tracks")
            if (tracks != null && tracks.isArray) {
                for (track in tracks) {
                    val trackUrl = track.get("file")?.asText() ?: continue
                    val langLabel = track.get("name")?.asText() ?: track.get("code")?.asText() ?: "Unknown"
                    if (trackUrl.isNotBlank()) {
                        subtitleCallback.invoke(SubtitleFile(langLabel, trackUrl))
                    }
                }
            }

            return foundLinks
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
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                json = mapOf("text" to encryptedText),
                timeout = 30L
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
}
