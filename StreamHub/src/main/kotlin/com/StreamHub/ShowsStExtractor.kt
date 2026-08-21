package com.StreamHub

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

object ShowsStExtractor {

    private const val TAG = "ShowsStExtractor"
    private const val SOURCE_PREFIX = "111movies"

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

        val pageUrl = if (isMovie) "https://111movies.net/movie/$tmdbId"
                      else "https://111movies.net/tv/$tmdbId/$season/$episode"

        val context = CloudStreamApp.context
        if (context == null) {
            Log.e(TAG, "CloudStreamApp.context is null")
            return false
        }

        // IMPORTANT: api.shows.st validates the request's Origin against the
        // real embed page (111movies.net redirects to player.vidlove.cc/embed/...).
        // A raw request/navigation straight to the API URL has no Origin context
        // and gets rejected with 403 "forbidden" for EVERY source, regardless of
        // source name. The fix is to load the actual content page once (following
        // its redirect), then fire fetch() calls from *inside that page's own JS
        // context* -- exactly what the site's own embedded script does -- so the
        // browser attaches the correct Origin/cookies automatically.
        val session = WebViewPageSession(context)
        val addedSubtitles = mutableSetOf<String>()

        return try {
            val opened = withTimeoutOrNull(20000) { session.open(pageUrl) } ?: false
            if (!opened) {
                Log.e(TAG, "Failed to open/settle content page for session")
                return false
            }

            coroutineScope {
                val jobs = sources.map { source ->
                    async {
                        try {
                            processSource(
                                session, source, tmdbId, isMovie, season, episode,
                                callback, subtitleCallback, addedSubtitles
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing source $source", e)
                            false
                        }
                    }
                }
                val results = jobs.awaitAll()
                results.any { it }
            }
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
        parseHlsManifest(manifest, "$SOURCE_PREFIX [$source]", callback)
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

    private suspend fun parseHlsManifest(
        manifest: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentBandwidth = 0

        manifest.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-STREAM-INF") -> {
                    val attrs = trimmed.substringAfter(":").split(",").mapNotNull { attr ->
                        val parts = attr.split("=")
                        if (parts.size == 2) parts[0] to parts[1].trim('"') else null
                    }.toMap()
                    currentBandwidth = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0
                }
                trimmed.startsWith("http") -> {
                    val quality = when {
                        currentBandwidth > 4000000 -> Qualities.P1080.value
                        currentBandwidth > 2000000 -> Qualities.P720.value
                        currentBandwidth > 1000000 -> Qualities.P480.value
                        else -> Qualities.P360.value
                    }

                    Log.d(TAG, "Adding stream from $sourceName")
                    callback(
                        newExtractorLink(
                            source = sourceName,
                            name = "HLS",
                            url = trimmed,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://111movies.net/"
                            this.quality = quality
                        }
                    )
                }
            }
        }
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
     * Manages a single hidden WebView that loads the real content page
     * (following any redirect, e.g. 111movies.net -> player.vidlove.cc/embed/...)
     * and lets you fire multiple fetch() calls *from inside that page's JS
     * context* afterwards, so requests carry the correct Origin/cookies.
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
         * a client-side redirect to a different origin -- e.g.
         * player.vidlove.cc -- is given time to complete before we consider
         * the session "ready"). Returns true once ready, false on failure.
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
                                    Log.d(TAG, "Content page session ready at: ${webView?.url}")
                                    ready.complete(true)
                                }
                            }
                            settleRunnable = runnable
                            // Debounce: if another navigation (e.g. a client-side
                            // redirect to a different origin) starts within this
                            // window, onPageFinished fires again and reschedules.
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
