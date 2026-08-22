package com.StreamHub

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    suspend fun getStreams(
        tmdbId: String,
        isMovie: Boolean,
        season: Int? = null,
        episode: Int? = null,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "getStreams invoked: tmdbId=$tmdbId, isMovie=$isMovie, season=$season, episode=$episode")

        val pageUrl = if (isMovie) {
            "$MAIN_URL/movie/$tmdbId"
        } else {
            "$MAIN_URL/tv/$tmdbId/${season ?: 1}/${episode ?: 1}"
        }

        val context = CloudStreamApp.context
        if (context == null) {
            Log.e(TAG, "CloudStreamApp.context is null")
            return false
        }

        val session = VidlinkSession(context)

        return try {
            // Give Cloudflare up to 25 seconds to solve itself invisibly
            val apiUrl = withTimeoutOrNull(25000) {
                session.interceptApiUrl(pageUrl)
            }
            
            // Extract the native, trusted User-Agent that passed Cloudflare
            val nativeUserAgent = session.userAgent
            session.destroy()

            if (apiUrl.isNullOrBlank()) {
                Log.w(TAG, "Failed to intercept API URL. Cloudflare might be blocking the headless WebView.")
                return false
            }

            Log.d(TAG, "Successfully intercepted encrypted API URL: $apiUrl")

            // Fetch the JSON payload using the exact Native User-Agent to pass the 428 Precondition
            val jsonText = app.get(
                url = apiUrl, 
                headers = mapOf(
                    "Referer" to "$MAIN_URL/",
                    "Origin" to MAIN_URL,
                    "User-Agent" to nativeUserAgent
                )
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
                    val videoUrl = qData.optString("url")
                    
                    if (videoUrl.isNotBlank()) {
                        val bandwidth = when (qualityKey) {
                            "1080" -> 5000000
                            "720" -> 2500000
                            "480" -> 1200000
                            "360" -> 800000
                            else -> 1500000
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
                                "Origin" to MAIN_URL,
                                "User-Agent" to nativeUserAgent // Native UA bypasses 428 and 429 blocks
                            )
                        )
                    )
                }
            }

            val captions = streamObj?.optJSONArray("captions") 
                ?: json.optJSONArray("subtitles")
                ?: streamObj?.optJSONArray("subtitles")

            if (captions != null) {
                val addedSubs = mutableSetOf<String>()
                for (i in 0 until captions.length()) {
                    val sub = captions.optJSONObject(i) ?: continue
                    val subUrl = sub.optString("url").ifBlank { sub.optString("file") }
                    val label = sub.optString("label").ifBlank { sub.optString("language", "English") }

                    if (subUrl.isNotBlank() && addedSubs.add(subUrl)) {
                        subtitleCallback(
                            SubtitleFile(
                                lang = label,
                                url = subUrl
                            )
                        )
                    }
                }
            }

            linksFound
        } catch (e: Exception) {
            Log.e(TAG, "Error in Vidlink extraction: ${e.message}", e)
            false
        } finally {
            session.destroy()
        }
    }

    private class VidlinkSession(private val context: Context) {
        private var webView: WebView? = null
        private val apiUrlDeferred = CompletableDeferred<String?>()
        private val mainHandler = Handler(Looper.getMainLooper())
        
        var userAgent: String = ""
            private set

        @SuppressLint("SetJavaScriptEnabled")
        suspend fun interceptApiUrl(pageUrl: String): String? = withContext(Dispatchers.Main) {
            try {
                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    
                    // CRITICAL: We MUST allow images and network loads so Cloudflare's invisible 
                    // captcha assets load properly and solve themselves.
                    settings.blockNetworkImage = false 
                    settings.blockNetworkLoads = false
                    
                    // Grab the trusted, device-specific native User-Agent
                    this@VidlinkSession.userAgent = settings.userAgentString

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                            
                            // Intercept the API call right after Cloudflare clears
                            if ((url.contains("/api/b/movie") || url.contains("/api/b/tv")) && request.method == "GET") {
                                if (!apiUrlDeferred.isCompleted) {
                                    apiUrlDeferred.complete(url)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                }
                webView?.loadUrl(pageUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize WebView: ${e.message}", e)
                if (!apiUrlDeferred.isCompleted) apiUrlDeferred.complete(null)
            }
            
            apiUrlDeferred.await()
        }

        fun destroy() {
            mainHandler.post {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                    webView = null
                } catch (e: Exception) {
                    Log.w(TAG, "Error destroying WebView session: ${e.message}")
                }
            }
        }
    }

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
                            thread(isDaemon = true, name = "VidlinkManifestClient") {
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
