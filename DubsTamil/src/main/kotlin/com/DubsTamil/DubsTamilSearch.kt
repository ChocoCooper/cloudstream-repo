package com.dubstamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder

// Works for any MainAPI provider since the OMDB API operates independently
suspend fun MainAPI.getSharedSearchData(query: String): List<SearchResponse> {
    var omdbJson: String? = null
    val encodedQuery = URLEncoder.encode(query, "UTF-8")

    try {
        val apiKey = getRandomApiKey()
        val url = "https://www.omdbapi.com/?apikey=$apiKey&s=$encodedQuery&type=movie"
        val response = omdbSemaphore.withPermit { app.get(url, timeout = 3) }

        if (response.code == 401 || response.text.contains("Limit reached", ignoreCase = true) || response.text.contains("Invalid API key", ignoreCase = true)) {
            removeDeadKey(apiKey)
        } else if (response.isSuccessful && response.text.contains("\"Response\":\"True\"")) {
            omdbJson = response.text
        }
    } catch (e: Exception) { }

    if (omdbJson == null) return emptyList()
    val parsed = AppUtils.tryParseJson<OmdbSearchResponse>(omdbJson)
    
    val validOmdbResults = parsed?.Search?.filter { item ->
        !item.Poster.isNullOrBlank() && item.Poster != "N/A"
    } ?: emptyList()

    return validOmdbResults.mapNotNull { item ->
        val title = item.Title ?: return@mapNotNull null
        val year = item.Year?.replace(Regex("[^0-9]"), "") ?: "" 
        val fullPoster = item.Poster ?: ""

        val t = URLEncoder.encode(title, "UTF-8")
        val y = URLEncoder.encode(year, "UTF-8")
        val p = URLEncoder.encode(fullPoster, "UTF-8")
        
        // This generates a synthetic URL bound to whichever provider (mainUrl) is calling it.
        val targetData = "$mainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=&s="

        newMovieSearchResponse(title, targetData) {
            this.posterUrl = fullPoster
            this.year = year.toIntOrNull()
        }
    }
}
