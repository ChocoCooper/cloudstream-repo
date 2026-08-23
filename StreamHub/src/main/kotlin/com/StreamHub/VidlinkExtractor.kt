package com.StreamHub

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject

object VidlinkExtractor {

    private const val TAG = "VidLink"
    private const val DISPLAY_NAME = "Vidlink"
    private const val MAIN_URL = "https://vidlink.pro"
    private const val ENC_API = "https://enc-dec.app/api/enc-vidlink?text="
    
    // The Proxy Node that bypasses the bcdn.hakunaymatata CDN Firewall
    private const val PROXY_DOMAIN = "https://noon.mooncase.online/mp"
    
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    suspend fun getStreams(
        tmdbId: String,
        isMovie: Boolean,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "getStreams invoked: tmdbId=$tmdbId")

        return try {
            // 1. Fetch Token
            val encResponse = app.get(
                url = "$ENC_API$tmdbId", 
                headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")
            ).text
            
            val encJson = JSONObject(encResponse)
            if (encJson.optInt("status") != 200) return false
            
            val token = encJson.optString("result", "")
            if (token.isBlank()) return false

            // 2. Fetch Streams
            val apiUrl = if (isMovie) {
                "$MAIN_URL/api/b/movie/$token?multiLang=0"
            } else {
                "$MAIN_URL/api/b/tv/$token/${season ?: 1}/${episode ?: 1}?multiLang=0"
            }
            
            val jsonText = app.get(
                url = apiUrl, 
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$MAIN_URL/",
                    "Origin" to MAIN_URL,
                    "Accept" to "application/json"
                )
            ).text
            
            val json = JSONObject(jsonText)
            val streamObj = json.optJSONObject("stream") ?: return false
            val qualitiesObj = streamObj.optJSONObject("qualities") ?: return false

            var linksFound = false
            val sortedKeys = qualitiesObj.keys().asSequence().toList().sortedByDescending { it.toIntOrNull() ?: 0 }
            
            for (key in sortedKeys) {
                val qData = qualitiesObj.optJSONObject(key) ?: continue
                var videoUrl = qData.optString("url")
                
                if (videoUrl.isNotBlank()) {
                    
                    // ========================================================
                    // THE MISSING HOST SWAP FIX
                    // Reroute heavily firewalled CDN links through the proxy
                    // ========================================================
                    if (videoUrl.contains("bcdn.hakunaymatata.com")) {
                        videoUrl = videoUrl.replace(Regex("^https?://[^/]+"), PROXY_DOMAIN)
                    }

                    val qualityInt = when (key) {
                        "1080" -> Qualities.P1080.value
                        "720" -> Qualities.P720.value
                        "480" -> Qualities.P480.value
                        "360" -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    
                    callback(
                        ExtractorLink(
                            source = DISPLAY_NAME,
                            name = "$DISPLAY_NAME ${key}p", 
                            url = videoUrl,
                            referer = "$MAIN_URL/",
                            quality = qualityInt,
                            type = ExtractorLinkType.VIDEO, // Direct MP4 playback 
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "$MAIN_URL/"
                            )
                        )
                    )
                    linksFound = true
                }
            }

            // 3. Extract Subtitles
            val captions = streamObj.optJSONArray("captions") ?: json.optJSONArray("subtitles") ?: streamObj.optJSONArray("subtitles")
            if (captions != null) {
                for (i in 0 until captions.length()) {
                    val sub = captions.optJSONObject(i) ?: continue
                    val subUrl = sub.optString("url").ifBlank { sub.optString("file") }
                    val label = sub.optString("label").ifBlank { sub.optString("language", "English") }

                    if (subUrl.isNotBlank()) {
                        subtitleCallback(SubtitleFile(lang = label, url = subUrl))
                    }
                }
            }

            linksFound
            
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Native extraction failed: ${e.message}", e)
            false
        }
    }
}
