package com.StreamHub

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object ShowsStExtractor {

    private const val TAG = "ShowsStExtractor"
    private const val SOURCE_PREFIX = "111movies"

    // Create a single OkHttp client with DdosGuardKiller interceptor
    private val client by lazy {
        OkHttpClient.Builder()
            .addInterceptor(DdosGuardKiller(alwaysBypass = false))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
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

        val sources = listOf("vidapi", "moviebox2", "cinefreak", "warden", "ipcloud", "tcloud", "moviebox")
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

        // Build OkHttp request
        val requestBuilder = Request.Builder()
            .url(apiUrl)
            .get()
        headers.forEach { (key, value) -> requestBuilder.header(key, value) }
        val request = requestBuilder.build()

        // Execute with the custom client (which includes DdosGuardKiller interceptor)
        val response = withContext(Dispatchers.IO) {
            client.newCall(request).execute()
        }

        Log.d(TAG, "API response for $source: ${response.code}")
        if (!response.isSuccessful) {
            response.close()
            Log.w(TAG, "Source $source failed: ${response.code}")
            return false
        }

        val body = response.body?.string()
        response.close()
        if (body.isNullOrBlank()) {
            Log.w(TAG, "Empty body for $source")
            return false
        }

        val json = try {
            JSONObject(body)
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
