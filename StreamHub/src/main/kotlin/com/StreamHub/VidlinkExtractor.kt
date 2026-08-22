package com.StreamHub

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object VidlinkExtractor {

    private const val TAG = "VidLink"
    private const val DISPLAY_NAME = "Vidlink"
    private const val MAIN_URL = "https://vidlink.pro"
    private const val ENC_API = "https://enc-dec.app/api/enc-vidlink?text="
    private const val PROXY_DOMAIN = "https://noon.mooncase.online/mp"
    
    // Strict User-Agent binding
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

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
            // 1. Fetch encrypted token natively
            val encResponse = app.get(
                url = "$ENC_API$tmdbId",
                headers = mapOf("User-Agent" to USER_AGENT),
                timeout = 15
            ).text
            
            val encJson = JSONObject(encResponse)
            if (encJson.optInt("status") != 200) return false
            
            val encryptedToken = encJson.optString("result", "")
            if (encryptedToken.isBlank()) return false

            // 2. Fetch Streams from Vidlink API
            val apiUrl = if (isMovie) {
                "$MAIN_URL/api/b/movie/$encryptedToken?multiLang=0"
            } else {
                "$MAIN_URL/api/b/tv/$encryptedToken/${season ?: 1}/${episode ?: 1}?multiLang=0"
            }
            
            val jsonText = app.get(
                url = apiUrl, 
                headers = mapOf(
                    "Referer" to "$MAIN_URL/",
                    "Origin" to MAIN_URL,
                    "User-Agent" to USER_AGENT
                ),
                timeout = 15
            ).text
            
            val json = JSONObject(jsonText)
            val streamObj = json.optJSONObject("stream")
            val qualitiesObj = streamObj?.optJSONObject("qualities")

            var linksFound = false
            if (qualitiesObj != null && qualitiesObj.length() > 0) {
                val m3u8Builder = StringBuilder("#EXTM3U\n")
                val sortedKeys = qualitiesObj.keys().asSequence().toList().sortedByDescending { it.toIntOrNull() ?: 0 }
                
                for (qualityKey in sortedKeys) {
                    val qData = qualitiesObj.optJSONObject(qualityKey) ?: continue
                    var videoUrl = qData.optString("url")
                    
                    if (videoUrl.isNotBlank()) {
                        
                        // MAGIC FIX 1: The Host Swap
                        // Reroute heavily firewalled CDN links through the open proxy
                        if (videoUrl.contains("bcdn.hakunaymatata.com")) {
                            videoUrl = videoUrl.replace(Regex("^https?://[^/]+"), PROXY_DOMAIN)
                        }
                        
                        val bandwidth = when (qualityKey) {
                            "1080" -> 5000000; "720" -> 2500000; "480" -> 1200000; "360" -> 800000; else -> 1500000
                        }
                        val height = qualityKey.toIntOrNull() ?: 720
                        val width = (height * 16) / 9
                        
                        m3u8Builder.append("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,RESOLUTION=${width}x${height}\n")
                        m3u8Builder.append(videoUrl).append("\n")
                        linksFound = true
                    }
                }
                
                if (linksFound) {
                    val localM3u8Url = LocalManifestServer.serve(m3u8Builder.toString().toByteArray(Charsets.UTF_8))
                    
                    callback(
                        ExtractorLink(
                            source = DISPLAY_NAME,
                            name = DISPLAY_NAME, 
                            url = localM3u8Url,
                            referer = "$MAIN_URL/",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8,
                            headers = mapOf(
                                // MAGIC FIX 2: Strip the "Origin" bot-trap. 
                                // Send ONLY the User-Agent and Referer to mimic a real HTML5 <video> tag.
                                "User-Agent" to USER_AGENT,
                                "Referer" to "$MAIN_URL/"
                            )
                        )
                    )
                }
            }

            // Extract Subtitles
            val captions = streamObj?.optJSONArray("captions") ?: json.optJSONArray("subtitles") ?: streamObj?.optJSONArray("subtitles")
            if (captions != null) {
                val addedSubs = mutableSetOf<String>()
                for (i in 0 until captions.length()) {
                    val sub = captions.optJSONObject(i) ?: continue
                    val subUrl = sub.optString("url").ifBlank { sub.optString("file") }
                    val label = sub.optString("label").ifBlank { sub.optString("language", "English") }

                    if (subUrl.isNotBlank() && addedSubs.add(subUrl)) {
                        subtitleCallback(SubtitleFile(lang = label, url = subUrl))
                    }
                }
            }

            linksFound
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Error in Vidlink native extraction: ${e.message}", e)
            false
        }
    }

    // ========================================================================
    // LOCAL M3U8 SERVER (Unchanged)
    // ========================================================================
    private object LocalManifestServer {
        private const val TTL_MS = 30 * 60 * 1000L
        private data class Entry(val bytes: ByteArray, val createdAt: Long)
        private val lock = Any()
        private var serverSocket: ServerSocket? = null
        private var port: Int = -1
        private val manifests = ConcurrentHashMap<String, Entry>()

        private fun ensureStarted(): Int {
            synchronized(lock) {
                val existing = serverSocket
                if (existing != null && !existing.isClosed) return port
                val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                port = ss.localPort
                thread(isDaemon = true, name = "VidlinkManifestServer") {
                    while (!ss.isClosed) {
                        try {
                            val client = ss.accept()
                            thread(isDaemon = true) { handleClient(client) }
                        } catch (e: Exception) {}
                    }
                }
                return port
            }
        }

        private fun handleClient(socket: Socket) {
            socket.use { s ->
                try {
                    s.soTimeout = 10000
                    val input = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val requestLine = input.readLine() ?: return
                    while (true) { if (input.readLine().isNullOrEmpty()) break }
                    
                    val path = requestLine.split(" ").getOrNull(1) ?: "/"
                    val id = path.trimStart('/').substringBefore("?").substringBefore(".")
                    val entry = manifests[id]
                    val output = s.getOutputStream()

                    if (entry == null) {
                        output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    } else {
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${entry.bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                        output.write(header.toByteArray(Charsets.US_ASCII))
                        output.write(entry.bytes)
                    }
                    output.flush()
                } catch (e: Exception) {}
            }
        }

        fun serve(bytes: ByteArray): String {
            val p = ensureStarted()
            manifests.entries.removeAll { it.value.createdAt < System.currentTimeMillis() - TTL_MS }
            val id = UUID.randomUUID().toString()
            manifests[id] = Entry(bytes, System.currentTimeMillis())
            return "http://127.0.0.1:$p/$id.m3u8"
        }
    }
}
