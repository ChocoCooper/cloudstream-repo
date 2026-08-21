package com.StreamHub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

object ShowsStExtractor {

    /**
     * Fetch streams from 111movies.net (api.shows.st) for movies or TV episodes.
     *
     * @param tmdbId   TMDB ID of the movie or TV show
     * @param isMovie  True for movie, false for TV series
     * @param season   Season number (null for movies)
     * @param episode  Episode number (null for movies)
     */
    suspend fun getStream(
        tmdbId: String,
        isMovie: Boolean,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val json = fetchShowsStApi(tmdbId, isMovie, season, episode) ?: return false
            val sourceObj = json.optJSONObject("source") ?: return false
            val manifest = sourceObj.optString("manifest")
            if (manifest.isBlank()) return false

            parseHlsManifest(manifest, "111movies", callback)
            addShowsStSubtitles(json, subtitleCallback)
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun fetchShowsStApi(
        tmdbId: String,
        isMovie: Boolean,
        season: Int?,
        episode: Int?
    ): JSONObject? {
        val url = if (isMovie) {
            "https://api.shows.st/movie?id=$tmdbId&mode=json&sources=vidapi"
        } else {
            "https://api.shows.st/tv?id=$tmdbId&season=$season&episode=$episode&mode=json&sources=vidapi"
        }
        val referer = if (isMovie) {
            "https://111movies.net/movie/$tmdbId"
        } else {
            "https://111movies.net/tv/$tmdbId/$season/$episode"
        }
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to referer,
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest"
        )
        return try {
            val response = app.get(url, headers = headers, timeout = 15L)
            if (response.isSuccessful) JSONObject(response.text) else null
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun parseHlsManifest(
        manifest: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentBandwidth = 0
        var currentResolution = ""
        var currentCodecs = ""

        manifest.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("#EXT-X-STREAM-INF") -> {
                    val attrs = trimmed.substringAfter(":").split(",").mapNotNull { attr ->
                        val parts = attr.split("=")
                        if (parts.size == 2) parts[0] to parts[1].trim('"') else null
                    }.toMap()
                    currentBandwidth = attrs["BANDWIDTH"]?.toIntOrNull() ?: 0
                    currentResolution = attrs["RESOLUTION"] ?: ""
                    currentCodecs = attrs["CODECS"] ?: ""
                }
                trimmed.startsWith("http") -> {
                    val quality = when {
                        currentBandwidth > 4000000 -> Qualities.P1080.value
                        currentBandwidth > 2000000 -> Qualities.P720.value
                        currentBandwidth > 1000000 -> Qualities.P480.value
                        else -> Qualities.P360.value
                    }
                    val linkName = if (currentResolution.isNotBlank()) "$currentResolution ($currentCodecs)" else "HLS"
                    callback(
                        newExtractorLink(
                            source = sourceName,
                            name = linkName,
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

    private fun addShowsStSubtitles(json: JSONObject, subtitleCallback: (SubtitleFile) -> Unit) {
        val subtitlesArray = json.optJSONArray("subtitles") ?: return
        for (i in 0 until subtitlesArray.length()) {
            val subObj = subtitlesArray.optJSONObject(i) ?: continue
            val subUrl = subObj.optString("file")
            val label = subObj.optString("label")
            if (subUrl.isNotBlank() && label.contains("English", ignoreCase = true)) {
                subtitleCallback.invoke(SubtitleFile("English", subUrl))
            }
        }
    }
}
