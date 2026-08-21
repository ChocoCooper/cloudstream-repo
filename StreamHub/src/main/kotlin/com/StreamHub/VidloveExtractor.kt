package com.StreamHub

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

object VidloveExtractor {

    private const val TAG = "VidloveExtractor"
    private const val SOURCE_NAME = "Vidlove"

    /** Extracts "scheme://host[:port]" from a full URL, with a trailing slash. */
    private fun originWithSlash(url: String): String {
        return try {
            val u = java.net.URL(url)
            val portPart = if (u.port != -1 && u.port != u.defaultPort) ":${u.port}" else ""
            "${u.protocol}://${u.host}$portPart/"
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse origin from '$url', falling back to it as-is: $e")
            if (url.endsWith("/")) url else "$url/"
        }
    }

    suspend fun getStreams(
        tmdbId: String,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "getStreams called: tmdbId=$tmdbId, isMovie=$isMovie, season=$season, episode=$episode")

        // 1. Fetch available sources dynamically
        val sources = fetchAvailableSources(isMovie)
        if (sources.isNullOrEmpty()) {
            Log.w(TAG, "No sources available for ${if (isMovie) "movie" else "tv"} - cannot proceed")
            return false
        }
        Log.d(TAG, "Available sources: $sources")

        // Direct Vidlove embed URLs
        val pageUrl = if (isMovie) "https://player.vidlove.cc/embed/movie/$tmdbId"
                      else "https://player.vidlove.cc/embed/tv/$tmdbId/$season/$episode"

        val context = CloudStreamApp.context
        if (context == null) {
            Log.e(TAG, "CloudStreamApp.context is null")
            return false
        }

        val session = WebViewPageSession(context)
        val addedSubtitles = mutableSetOf<String>()

        return try {
            val opened = withTimeoutOrNull(20000) { session.open(pageUrl) } ?: false
            if (!opened) {
                Log.e(TAG, "Failed to open/settle content page for session")
                return false
            }

            val realReferer = session.settledUrl?.let { originWithSlash(it) }
                ?: originWithSlash(pageUrl)
            Log.d(TAG, "Using referer for extracted links: $realReferer")

            // Sequential fallback chain: try sources in priority order and stop at the first working one
            var succeeded = false
            for (source in sources) {
                val ok = try {
                    processSource(
                        session, source, tmdbId, isMovie, season, episode,
                        realReferer, callback, subtitleCallback, addedSubtitles
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing source $source", e)
                    false
                }
                if (ok) {
                    Log.d(TAG, "Source '$source' succeeded, stopping fallback chain")
                    succeeded = true
                    break
                }
            }
            succeeded
        } finally {
            session.close()
        }
    }

    private suspend fun fetchAvailableSources(isMovie: Boolean): List<String>? {
        val url = "https://api.shows.st/source-order"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest"
        )
        val jsonText = withTimeoutOrNull(15000) { fetchUrlWithWebView(url, headers) }
        if (jsonText.isNullOrBlank()) {
            Log.e(TAG, "Failed to fetch source-order")
            return null
        }
        return try {
            val json = JSONObject(jsonText)
            val candidateKeys = if (isMovie) {
                listOf("movie", "movies", "film", "films")
            } else {
                listOf("tv", "show", "shows", "series")
            }

            var arr = candidateKeys.firstNotNullOfOrNull { candidateKey ->
                json.optJSONArray(candidateKey)
            }

            if (arr == null && isMovie) {
                arr = json.optJSONArray("tv")
            }

            arr?.let { a -> (0 until a.length()).map { a.getString(it) } }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing source-order. Raw response: $jsonText", e)
            null
        }
    }

    private suspend fun processSource(
        session: WebViewPageSession,
        source: String,
        tmdbId: String,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit,
        addedSubtitles: MutableSet<String>
    ): Boolean {
        Log.d(TAG, "Trying source: $source")

        val apiUrl = if (isMovie) {
            "https://api.shows.st/movie?id=$tmdbId&mode=json&sources=$source"
        } else {
            "https://api.shows.st/tv?id=$tmdbId&season=$season&episode=$episode&mode=json&sources=$source"
        }

        val jsonText = withTimeoutOrNull(30000) { session.fetchJson(apiUrl) }
        if (jsonText.isNullOrBlank()) {
            Log.w(TAG, "Page-context fetch returned empty or timed out for $source")
            return false
        }

        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error for $source: $e")
            return false
        }

        val sourceObj = json.optJSONObject("source") ?: return false
        val manifest = sourceObj.optString("manifest")
        val directUrl = sourceObj.optString("url").takeIf { it.isNotBlank() }
            ?: sourceObj.optString("file").takeIf { it.isNotBlank() }

        // Determine the stream URL: direct URL or master playlist content wrapped in Base64 Data URI
        val streamUrl = when {
            !directUrl.isNullOrBlank() -> directUrl
            manifest.startsWith("http") -> manifest.trim()
            manifest.contains("#EXTM3U") -> {
                val encodedManifest = Base64.encodeToString(manifest.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                "data:application/vnd.apple.mpegurl;base64,$encodedManifest"
            }
            else -> null
        }

        if (streamUrl.isNullOrBlank()) {
            Log.w(TAG, "Empty or invalid manifest for $source")
            return false
        }

        Log.d(TAG, "Emitting single master stream for $SOURCE_NAME")

        // Single entry named "Vidlove" with full multi-track resolution support inside ExoPlayer
        callback(
            newExtractorLink(
                source = SOURCE_NAME,
                name = SOURCE_NAME,
                url = streamUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.Auto.value
            }
        )

        addVidloveSubtitles(json, subtitleCallback, addedSubtitles)
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchUrlWithWebView(
        url: String,
        headers: Map<String, String>
    ): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            var webView: WebView? = null
            try {
                val context = CloudStreamApp.context
                if (context == null) {
                    if (continuation.isActive) continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.blockNetworkImage = true
                    settings.blockNetworkLoads = false
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript(
                                "(function() { return document.body ? document.body.innerText : document.documentElement.innerText; })();"
                            ) { result ->
                                val decoded = try {
                                    JSONTokener(result ?: "null").nextValue() as? String
                                } catch (e: Exception) {
                                    null
                                } ?: ""
                                if (continuation.isActive) {
                                    continuation.resume(decoded)
                                }
                            }
                        }
                    }
                }
                webView?.loadUrl(url, headers)
            } catch (e: Exception) {
                if (continuation.isActive) continuation.resume(null)
            }

            continuation.invokeOnCancellation {
                webView?.destroy()
                webView = null
            }
        }
    }

    private fun addVidloveSubtitles(
        json: JSONObject,
        subtitleCallback: (SubtitleFile) -> Unit,
        addedSubtitles: MutableSet<String>
    ) {
        val subtitlesArray = json.optJSONArray("subtitles") ?: return
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

    private class WebViewPageSession(private val context: android.content.Context) {

        private var webView: WebView? = null
        private val pendingRequests = ConcurrentHashMap<String, CancellableContinuation<String?>>()
        private val ready = CompletableDeferred<Boolean>()
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile
        var settledUrl: String? = null
            private set

        private inner class Bridge {
            @JavascriptInterface
            fun onFetchResult(requestId: String, result: String) {
                pendingRequests.remove(requestId)?.let { cont ->
                    if (cont.isActive) cont.resume(result)
                }
            }
        }

        @SuppressLint("SetJavaScriptEnabled")
        suspend fun open(pageUrl: String): Boolean = withContext(Dispatchers.Main) {
            try {
                var settleRunnable: Runnable? = null

                webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.blockNetworkImage = true
                    settings.blockNetworkLoads = false
                    addJavascriptInterface(Bridge(), "AndroidBridge")
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            settleRunnable?.let { mainHandler.removeCallbacks(it) }
                            val runnable = Runnable {
                                if (!ready.isCompleted) {
                                    settledUrl = webView?.url
                                    ready.complete(true)
                                }
                            }
                            settleRunnable = runnable
                            mainHandler.postDelayed(runnable, 700)
                        }
                    }
                }
                webView?.loadUrl(pageUrl)
            } catch (e: Exception) {
                if (!ready.isCompleted) ready.complete(false)
            }
            ready.await()
        }

        suspend fun fetchJson(apiUrl: String, timeoutMs: Long = 25000): String? {
            if (!withTimeoutOrNull(timeoutMs) { ready.await() }.let { it == true }) {
                return null
            }
            val wv = webView ?: return null
            val requestId = UUID.randomUUID().toString()

            return withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        pendingRequests[requestId] = cont

                        val escapedUrl = JSONObject.quote(apiUrl)
                        val escapedId = JSONObject.quote(requestId)

                        val script = """
                            (function() {
                                fetch($escapedUrl, {
                                    headers: { 'Accept': 'application/json' },
                                    mode: 'cors',
                                    credentials: 'omit'
                                })
                                .then(function(r) { return r.text(); })
                                .then(function(t) { AndroidBridge.onFetchResult($escapedId, t); })
                                .catch(function(e) { AndroidBridge.onFetchResult($escapedId, '__FETCH_ERROR__' + e); });
                            })();
                        """.trimIndent()

                        wv.evaluateJavascript(script, null)

                        cont.invokeOnCancellation {
                            pendingRequests.remove(requestId)
                        }
                    }
                }
            }?.takeUnless { it.startsWith("__FETCH_ERROR__") }
        }

        fun close() {
            mainHandler.post {
                webView?.destroy()
                webView = null
            }
        }
    }
}
