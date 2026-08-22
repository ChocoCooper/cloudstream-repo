package com.StreamHub

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

object VidloveExtractor {

    private const val TAG = "Vidlove"
    private const val DISPLAY_NAME = "Vidlove"
    private const val TARGET_SOURCE = "vidapi"
    private const val ORIGIN_URL = "https://player.vidlove.cc"

    suspend fun getStreams(
        tmdbId: String,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "getStreams called: tmdbId=$tmdbId, isMovie=$isMovie, season=$season, episode=$episode")

        // Build the direct API endpoint with hardcoded vidapi source
        val apiUrl = if (isMovie) {
            "https://api.shows.st/movie?id=$tmdbId&mode=json&sources=$TARGET_SOURCE"
        } else {
            "https://api.shows.st/tv?id=$tmdbId&season=$season&episode=$episode&mode=json&sources=$TARGET_SOURCE"
        }

        val refererUrl = if (isMovie) {
            "$ORIGIN_URL/embed/movie/$tmdbId"
        } else {
            "$ORIGIN_URL/embed/tv/$tmdbId/$season/$episode"
        }

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "Origin" to ORIGIN_URL,
            "Referer" to refererUrl,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site"
        )

        return try {
            val response = app.get(apiUrl, headers = headers, timeout = 15).text
            if (response.isBlank()) {
                Log.w(TAG, "API returned an empty response")
                return false
            }

            val json = JSONObject(response)
            val manifest = findManifest(json)

            if (manifest.isNullOrBlank()) {
                Log.w(TAG, "No manifest found in response: ${response.take(300)}")
                return false
            }

            val added = emitSingleLink(manifest, refererUrl, callback)
            if (added) {
                addShowsStSubtitles(json, subtitleCallback)
            }
            added
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching from api.shows.st: ${e.message}", e)
            false
        }
    }

    /**
     * Locates the HLS manifest text within the JSON response.
     */
    private fun findManifest(json: JSONObject): String? {
        json.optJSONObject("source")?.optString("manifest")?.takeIf { it.isNotBlank() }?.let { return it }

        json.optJSONArray("sources")?.let { arr ->
            for (i in 0 until arr.length()) {
                val m = arr.optJSONObject(i)?.optString("manifest")
                if (!m.isNullOrBlank()) return m
            }
        }

        json.optJSONObject("data")?.optJSONObject("source")?.optString("manifest")
            ?.takeIf { it.isNotBlank() }?.let { return it }

        json.optString("manifest").takeIf { it.isNotBlank() }?.let { return it }
        json.optJSONObject("stream")?.optString("manifest")?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    private suspend fun emitSingleLink(
        manifest: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val hasVariant = manifest.lines().any { it.trim().startsWith("http") }
        if (!hasVariant) {
            Log.w(TAG, "Manifest has no variant stream URLs, skipping")
            return false
        }

        val localUrl = LocalManifestServer.serve(manifest.toByteArray(Charsets.UTF_8))
        Log.d(TAG, "Emitting combined-quality link ($DISPLAY_NAME) via $localUrl")

        callback(
            newExtractorLink(
                source = DISPLAY_NAME,
                name = DISPLAY_NAME,
                url = localUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
            }
        )
        return true
    }

    private fun addShowsStSubtitles(
        json: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val subtitlesArray = json.optJSONArray("subtitles") ?: return
        val addedSubtitles = mutableSetOf<String>()

        for (i in 0 until subtitlesArray.length()) {
            val subObj = subtitlesArray.optJSONObject(i) ?: continue
            val subUrl = subObj.optString("file")
            val label = subObj.optString("label")
            if (subUrl.isNotBlank() && label.contains("English", ignoreCase = true)) {
                val lang = "English"
                if (addedSubtitles.add(lang)) {
                    subtitleCallback.invoke(SubtitleFile(lang, subUrl))
                }
            }
        }
    }

    /**
     * Local loopback HTTP server to serve the manifest in-memory to ExoPlayer.
     */
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
                Log.d(TAG, "LocalManifestServer listening on 127.0.0.1:$port")

                thread(isDaemon = true, name = "VidloveManifestServer") {
                    while (!ss.isClosed) {
                        try {
                            val client = ss.accept()
                            thread(isDaemon = true, name = "VidloveManifestClient") {
                                handleClient(client)
                            }
                        } catch (e: Exception) {
                            if (!ss.isClosed) {
                                Log.e(TAG, "LocalManifestServer accept error: $e")
                            }
                        }
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
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }

                    val path = requestLine.split(" ").getOrNull(1) ?: "/"
                    val id = path.trimStart('/').substringBefore("?").substringBefore(".")
                    val entry = manifests[id]
                    val output = s.getOutputStream()

                    if (entry == null) {
                        val body = "not found".toByteArray()
                        val header = "HTTP/1.1 404 Not Found\r\n" +
                            "Content-Type: text/plain\r\n" +
                            "Content-Length: ${body.size}\r\n" +
                            "Connection: close\r\n\r\n"
                        output.write(header.toByteArray(Charsets.US_ASCII))
                        output.write(body)
                    } else {
                        val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/vnd.apple.mpegurl\r\n" +
                            "Content-Length: ${entry.bytes.size}\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Cache-Control: no-cache\r\n" +
                            "Connection: close\r\n\r\n"
                        output.write(header.toByteArray(Charsets.US_ASCII))
                        output.write(entry.bytes)
                    }
                    output.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "LocalManifestServer client error: $e")
                }
            }
        }

        private fun pruneExpired() {
            val cutoff = System.currentTimeMillis() - TTL_MS
            manifests.entries.removeAll { it.value.createdAt < cutoff }
        }

        fun serve(bytes: ByteArray): String {
            val p = ensureStarted()
            pruneExpired()
            val id = UUID.randomUUID().toString()
            manifests[id] = Entry(bytes, System.currentTimeMillis())
            return "http://127.0.0.1:$p/$id.m3u8"
        }
    }
}
