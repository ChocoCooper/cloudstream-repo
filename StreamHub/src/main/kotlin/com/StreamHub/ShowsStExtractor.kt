package com.StreamHub

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
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

        val semaphore = Semaphore(2)
        val addedSubtitles = mutableSetOf<String>()

        return coroutineScope {
            val jobs = sources.map { source ->
                async {
                    semaphore.withPermit {
                        try {
                            processSource(
                                source, tmdbId, isMovie, season, episode,
                                pageUrl, callback, subtitleCallback, addedSubtitles
                            )
                        } catch (e: Exception) {
                            Log.e(TAG, "Error processing source $source", e)
                            false
                        }
                    }
                }
            }
            val results = jobs.awaitAll()
            results.any { it }
        }
    }

    /**
     * Fetches the list of available sources from api.shows.st/source-order.
     * Uses WebView to bypass Cloudflare/TLS fingerprinting.
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
            val key = if (isMovie) "movie" else "tv"
            val arr = json.optJSONArray(key)
            if (arr == null) {
                Log.e(TAG, "No '$key' array in source-order")
                null
            } else {
                (0 until arr.length()).map { arr.getString(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing source-order", e)
            null
        }
    }

    private suspend fun processSource(
        source: String,
        tmdbId: String,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        pageUrl: String,
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

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to pageUrl,
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest"
        )

        // Fetch JSON using WebView (bypasses TLS fingerprint)
        val jsonText = withTimeoutOrNull(30000) { fetchUrlWithWebView(apiUrl, headers) }
        if (jsonText.isNullOrBlank()) {
            Log.w(TAG, "WebView returned empty or timed out for $source")
            return false
        }

        Log.d(TAG, "Got response for $source, length=${jsonText.length}")
        val json = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error for $source: $e")
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
     * Uses CloudStreamApp.context (global application context) to create the WebView.
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
                                    // unicode escapes, etc. A manual trim()/removeSurrounding("\"")
                                    // only strips the outer quotes and leaves inner backslash-quote
                                    // sequences intact, which breaks downstream JSONObject parsing.
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
}
