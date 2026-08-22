package com.StreamHub

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    private const val ENC_API = "https://enc-dec.app/api/enc-vidlink?text="
    
    // Strict User-Agent to bypass CDN 428/429 blocks
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

        // 1. Try the lightning-fast Native API first
        val nativeSuccess = tryNativeExtraction(tmdbId, isMovie, season, episode, subtitleCallback, callback)
        
        if (nativeSuccess) {
            Log.d(TAG, "Native extraction successful.")
            return true
        }

        // 2. Fallback to WebView if Native API fails or is offline
        Log.w(TAG, "Native extraction failed. Falling back to WebView Auto-Clicker...")
        return tryWebViewExtraction(tmdbId, isMovie, season, episode, subtitleCallback, callback)
    }

    // ========================================================================
    // METHOD 1: NATIVE EXTRACTION (Fast, relies on enc-dec.app)
    // ========================================================================
    private suspend fun tryNativeExtraction(
        tmdbId: String, isMovie: Boolean, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val encResponse = app.get("$ENC_API$tmdbId", timeout = 5).text
            val encJson = JSONObject(encResponse)
            
            if (encJson.optInt("status") != 200) return false
            
            val encryptedToken = encJson.optString("result", "")
            if (encryptedToken.isBlank()) return false

            val apiUrl = if (isMovie) {
                "$MAIN_URL/api/b/movie/$encryptedToken?multiLang=0"
            } else {
                "$MAIN_URL/api/b/tv/$encryptedToken/${season ?: 1}/${episode ?: 1}?multiLang=0"
            }
            
            val jsonText = app.get(
                url = apiUrl, 
                headers = mapOf("Referer" to "$MAIN_URL/", "Origin" to MAIN_URL, "User-Agent" to USER_AGENT),
                timeout = 10
            ).text
            
            parseAndEmitStreams(jsonText, subtitleCallback, callback)
        } catch (e: Exception) {
            Log.e(TAG, "Native extraction error: ${e.message}")
            false
        }
    }

    // ========================================================================
    // METHOD 2: WEBVIEW EXTRACTION (Slower, 100% Independent)
    // ========================================================================
    private suspend fun tryWebViewExtraction(
        tmdbId: String, isMovie: Boolean, season: Int?, episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit
    ): Boolean {
        val context = CloudStreamApp.context ?: return false
        val pageUrl = if (isMovie) "$MAIN_URL/movie/$tmdbId" else "$MAIN_URL/tv/$tmdbId/${season ?: 1}/${episode ?: 1}"
        val session = VidlinkSession(context)

        return try {
            val apiUrl = withTimeoutOrNull(25000) { session.interceptApiUrl(pageUrl) }
            val nativeUserAgent = session.userAgent
            session.destroy()

            if (apiUrl.isNullOrBlank()) return false

            val jsonText = app.get(
                url = apiUrl, 
                headers = mapOf("Referer" to "$MAIN_URL/", "Origin" to MAIN_URL, "User-Agent" to nativeUserAgent)
            ).text
            
            parseAndEmitStreams(jsonText, subtitleCallback, callback, nativeUserAgent)
        } catch (e: Exception) {
            Log.e(TAG, "WebView extraction error: ${e.message}")
            false
        } finally {
            session.destroy()
        }
    }

    // ========================================================================
    // COMMON PARSER FOR BOTH METHODS
    // ========================================================================
    private fun parseAndEmitStreams(
        jsonText: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit,
        customUserAgent: String = USER_AGENT
    ): Boolean {
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
                        headers = mapOf("Origin" to MAIN_URL, "User-Agent" to customUserAgent, "Accept" to "*/*")
                    )
                )
            }
        }

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
        return linksFound
    }

    // ========================================================================
    // WEBVIEW AUTO-CLICKER SESSION
    // ========================================================================
    private class VidlinkSession(private val context: Context) {
        private var webView: WebView? = null
        private val apiUrlDeferred = CompletableDeferred<String?>()
        private val mainHandler = Handler(Looper.getMainLooper())
        private var clickJob: Job? = null
        var userAgent: String = ""
            private set

        @SuppressLint("SetJavaScriptEnabled")
        suspend fun interceptApiUrl(pageUrl: String): String? = withContext(Dispatchers.Main) {
            try {
                webView = WebView(context).apply {
                    layout(0, 0, 1920, 1080) // Prevent Cloudflare 0x0 bot trap
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        blockNetworkImage = false 
                        mediaPlaybackRequiresUserGesture = false 
                    }
                    this@VidlinkSession.userAgent = settings.userAgentString

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)
                            if ((url.contains("/api/b/movie") || url.contains("/api/b/tv")) && request.method == "GET") {
                                if (!apiUrlDeferred.isCompleted) apiUrlDeferred.complete(url)
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            if (clickJob == null || clickJob?.isActive != true) {
                                clickJob = CoroutineScope(Dispatchers.Main).launch {
                                    while (!apiUrlDeferred.isCompleted && webView != null) {
                                        delay(500)
                                        simulateTouch(webView)
                                    }
                                }
                            }
                            super.onPageFinished(view, url)
                        }
                    }
                }
                webView?.loadUrl(pageUrl)
            } catch (e: Exception) {
                if (!apiUrlDeferred.isCompleted) apiUrlDeferred.complete(null)
            }
            val result = apiUrlDeferred.await()
            clickJob?.cancel()
            return@withContext result
        }

        private fun simulateTouch(view: WebView?) {
            if (view == null) return
            val x = 1920f / 2f
            val y = 1080f / 2f
            val downTime = SystemClock.uptimeMillis()
            val eventTime = downTime + 50
            
            val motionEventDown = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
            view.dispatchTouchEvent(motionEventDown)
            val motionEventUp = MotionEvent.obtain(downTime, eventTime, MotionEvent.ACTION_UP, x, y, 0)
            view.dispatchTouchEvent(motionEventUp)
            
            motionEventDown.recycle()
            motionEventUp.recycle()
        }

        fun destroy() {
            clickJob?.cancel()
            mainHandler.post {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                    webView = null
                } catch (e: Exception) {}
            }
        }
    }

    // ========================================================================
    // LOCAL M3U8 SERVER
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
