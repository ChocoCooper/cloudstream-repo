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
    private const val DISPLAY_NAME = "Vidlove"

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

        // Direct embed endpoints -- this IS the real player app's own origin,
        // no redirect hop needed.
        val pageUrl = if (isMovie) "https://player.vidlove.cc/embed/movie/$tmdbId"
                      else "https://player.vidlove.cc/embed/tv/$tmdbId/$season/$episode"

        val context = CloudStreamApp.context
        if (context == null) {
            Log.e(TAG, "CloudStreamApp.context is null")
            return false
        }

        // IMPORTANT: api.shows.st validates the request's Origin against the
        // real embed page origin (player.vidlove.cc). A raw request/navigation
        // straight to the API URL has no Origin context and gets rejected with
        // 403 "forbidden" for EVERY source, regardless of source name. The fix
        // is to load the actual embed page once, then fire fetch() calls from
        // *inside that page's own JS context* -- exactly what the site's own
        // embedded script does -- so the browser attaches the correct
        // Origin/cookies automatically.
        val session = WebViewPageSession(context)
        val addedSubtitles = mutableSetOf<String>()

        return try {
            val opened = withTimeoutOrNull(20000) { session.open(pageUrl) } ?: false
            if (!opened) {
                Log.e(TAG, "Failed to open/settle content page for session")
                return false
            }

            // Use the ACTUAL page the session settled on (in case of any
            // client-side redirect), not necessarily the exact pageUrl we
            // navigated to. The manifest host (d.shows.st) validates Referer
            // against this real origin.
            val realReferer = session.settledUrl?.let { originWithSlash(it) }
                ?: originWithSlash(pageUrl)
            Log.d(TAG, "Using referer for extracted links: $realReferer")

            // Sequential fallback chain: try sources in priority order and stop
            // at the first one that actually yields a working manifest, so only
            // ONE "Vidlove" link surfaces to the player instead of one per source.
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

    /**
     * Fetches the list of available sources from api.shows.st/source-order.
     * This endpoint has been observed to work fine with a plain direct
     * WebView navigation (no page/Origin context needed), unlike the
     * per-movie/per-tv endpoints.
     */
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

            // Candidate key names to try, in order of likelihood.
            val candidateKeys = if (isMovie) {
                listOf("movie", "movies", "film", "films")
            } else {
                listOf("tv", "show", "shows", "series")
            }

            var arr = candidateKeys.firstNotNullOfOrNull { candidateKey ->
                json.optJSONArray(candidateKey)?.also {
                    Log.d(TAG, "Found sources under key '$candidateKey'")
                }
            }

            // The API has been observed to only return a "tv" array in
            // source-order, with no separate movie list. Since the same
            // source names are used by both the /movie and /tv endpoints,
            // fall back to the "tv" list for movies when no movie-specific
            // key is present.
            if (arr == null && isMovie) {
                arr = json.optJSONArray("tv")
                if (arr != null) {
                    Log.w(TAG, "No movie-specific key found; falling back to 'tv' source list for movie sources")
                }
            }

            if (arr == null) {
                Log.e(TAG, "No matching array for isMovie=$isMovie among keys $candidateKeys. Raw response: $jsonText")
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

        // Fetch from inside the already-loaded content page's JS context, so
        // the request carries the correct Origin/cookies (mirrors the site's
        // own fetch(url, { mode: 'cors', credentials: 'omit' }) call).
        val jsonText = withTimeoutOrNull(30000) { session.fetchJson(apiUrl) }
        if (jsonText.isNullOrBlank()) {
            Log.w(TAG, "Page-context fetch returned empty or timed out for $source")
            return false
        }

        Log.d(TAG, "Got response for $source, length=${jsonText.length}")
        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error for $source: $e. Raw: ${jsonText.take(300)}")
            return false
        }

        val sourceObj = json.optJSONObject("source") ?: run {
            Log.w(TAG, "No 'source' object for $source")
            return false
        }
        val manifest = sourceObj.optString("manifest")
        if (manifest.isBlank()) {
            Log.w(TAG, "Empty manifest for $source")
            return false
        }

        Log.d(TAG, "Manifest found for $source")
        val added = emitSingleLink(manifest, referer, callback)
        if (!added) {
            // Manifest existed but contained no usable variants -- treat as a
            // failed source so the fallback chain moves on to the next one.
            return false
        }
        addShowsStSubtitles(json, subtitleCallback, addedSubtitles)
        return true
    }

    /**
     * Loads a URL in a hidden WebView and returns the page's text content.
     * Used only for the source-order endpoint, which works fine without a
     * page/Origin context. Uses CloudStreamApp.context (global application
     * context) to create the WebView.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchUrlWithWebView(
        url: String,
        headers: Map<String, String>
    ): String? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                var webView: WebView? = null
                try {
                    val context = CloudStreamApp.context
                    if (context == null) {
                        Log.e(TAG, "CloudStreamApp.context is null")
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
                                    // evaluateJavascript's callback returns a JSON-encoded string
                                    // literal (e.g. "{\"tv\":[\"warden\"]}"), NOT the raw text.
                                    // Decoding it with JSONTokener correctly un-escapes \", \n, \\,
                                    // unicode escapes, etc.
                                    val decoded = try {
                                        JSONTokener(result ?: "null").nextValue() as? String
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Failed to decode evaluateJavascript result: $e")
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
                    Log.e(TAG, "WebView error: $e")
                    if (continuation.isActive) continuation.resume(null)
                }

                continuation.invokeOnCancellation {
                    webView?.destroy()
                    webView = null
                }
            }
        }

    /**
     * Emits exactly ONE ExtractorLink for the entire master manifest (all
     * resolutions), instead of manually parsing #EXT-X-STREAM-INF lines and
     * emitting a separate link per quality. The raw manifest text is embedded
     * directly as a "data:" URI so ExoPlayer's own HLS parser handles ABR/
     * quality-track switching internally -- exactly like a normal single-URL
     * HLS source -- and the player shows just one "Vidlove" entry with
     * multiple resolution tracks inside it, rather than "Vidlove 480p",
     * "Vidlove 720p", etc. as separate sources.
     *
     * Media3's DefaultDataSource has built-in special-case handling for the
     * "data:" scheme regardless of the underlying HTTP client (e.g. Cronet),
     * so this works without needing a local server or extra dependencies.
     *
     * Returns true if the manifest contained at least one variant stream
     * (i.e. a link was actually emitted), false otherwise.
     */
    private fun emitSingleLink(
        manifest: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Sanity check: make sure this actually looks like an HLS playlist
        // with at least one stream reference before we bother emitting it.
        val hasVariant = manifest.lines().any { it.trim().startsWith("http") }
        if (!hasVariant) {
            Log.w(TAG, "Manifest has no variant stream URLs, skipping")
            return false
        }

        val encoded = Base64.encodeToString(manifest.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        val dataUri = "data:application/vnd.apple.mpegurl;base64,$encoded"

        Log.d(TAG, "Emitting single combined-quality link ($DISPLAY_NAME)")
        callback(
            newExtractorLink(
                source = DISPLAY_NAME,
                name = DISPLAY_NAME,
                url = dataUri,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                // No fixed quality: the embedded master playlist carries all
                // resolutions, and ExoPlayer's HLS engine picks/exposes them
                // as tracks on its own.
            }
        )
        return true
    }

    private fun addShowsStSubtitles(
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

    /**
     * Manages a single hidden WebView that loads the real embed page
     * (player.vidlove.cc/embed/...) and lets you fire multiple fetch() calls
     * *from inside that page's JS context* afterwards, so requests carry the
     * correct Origin/cookies.
     *
     * Why not just read evaluateJavascript's return value? Because
     * evaluateJavascript does NOT await Promises -- it returns immediately
     * with the synchronous result of the script, which for an async fetch()
     * call is useless. Instead we inject a JavascriptInterface bridge that
     * the page's JS explicitly calls once each fetch's promise resolves, and
     * match results back to Kotlin-side coroutines via a per-call request ID.
     */
    private class WebViewPageSession(private val context: android.content.Context) {

        private var webView: WebView? = null
        private val pendingRequests = ConcurrentHashMap<String, CancellableContinuation<String?>>()
        private val ready = CompletableDeferred<Boolean>()
        private val mainHandler = Handler(Looper.getMainLooper())

        /**
         * The page URL the session actually settled on (after any client-side
         * redirect). This is the real Origin that api.shows.st / d.shows.st
         * validate requests against, so it must be used (not necessarily the
         * exact pageUrl passed to [open]) when building Referer headers for
         * extracted links.
         */
        @Volatile
        var settledUrl: String? = null
            private set

        /**
         * JS-exposed bridge. The page's injected fetch script calls
         * AndroidBridge.onFetchResult(requestId, resultText) once its
         * promise resolves (or rejects, in which case resultText carries
         * an error marker so callers can log it).
         */
        private inner class Bridge {
            @JavascriptInterface
            fun onFetchResult(requestId: String, result: String) {
                pendingRequests.remove(requestId)?.let { cont ->
                    if (cont.isActive) cont.resume(result)
                }
            }
        }

        /**
         * Loads [pageUrl] and waits for navigation to settle (debounced, so
         * any client-side redirect is given time to complete before we
         * consider the session "ready"). Returns true once ready, false on
         * failure.
         */
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
                            Log.d(TAG, "Page settled candidate: $url")
                            settleRunnable?.let { mainHandler.removeCallbacks(it) }
                            val runnable = Runnable {
                                if (!ready.isCompleted) {
                                    settledUrl = webView?.url
                                    Log.d(TAG, "Content page session ready at: $settledUrl")
                                    ready.complete(true)
                                }
                            }
                            settleRunnable = runnable
                            // Debounce: if another navigation (e.g. a client-side
                            // redirect) starts within this window, onPageFinished
                            // fires again and reschedules.
                            mainHandler.postDelayed(runnable, 700)
                        }
                    }
                }
                webView?.loadUrl(pageUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open content page: $e")
                if (!ready.isCompleted) ready.complete(false)
            }
            ready.await()
        }

        /**
         * Fires a fetch(apiUrl) from inside the loaded page's JS context and
         * returns the response body text, or null on timeout/error.
         */
        suspend fun fetchJson(apiUrl: String, timeoutMs: Long = 25000): String? {
            if (!withTimeoutOrNull(timeoutMs) { ready.await() }.let { it == true }) {
                Log.e(TAG, "Session never became ready; cannot fetch $apiUrl")
                return null
            }
            val wv = webView ?: return null
            val requestId = UUID.randomUUID().toString()

            return withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        pendingRequests[requestId] = cont

                        // JSONObject.quote() safely JSON/JS-escapes the string
                        // and wraps it in quotes, producing a valid JS literal.
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
            }?.also { result ->
                if (result.startsWith("__FETCH_ERROR__")) {
                    Log.e(TAG, "In-page fetch error for $apiUrl: $result")
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
