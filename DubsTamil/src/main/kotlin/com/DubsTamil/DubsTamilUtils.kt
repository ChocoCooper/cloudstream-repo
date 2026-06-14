package com.dubstamil

import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder
import kotlin.random.Random

// Concurrency and Cache optimizations
internal val omdbSemaphore = Semaphore(5)
internal val scrapeSemaphore = Semaphore(5)
internal val pageCache = mutableMapOf<String, Pair<Long, Pair<List<ScrapedMovie>, Int>>>()
internal const val CACHE_DURATION = 5 * 60 * 1000L

internal val baseOmdbKeys = listOf(
    "eb0c0475", "4b447405", "7776cbde", "ff28f90b", "6c3a2d45"
)
internal var activeOmdbKeys = baseOmdbKeys.toMutableList()

data class OmdbSearchResponse(val Search: List<OmdbSearchResult>?, val Response: String?)
data class OmdbSearchResult(val Title: String?, val Year: String?, val Poster: String?)
data class OmdbTitleResponse(val Title: String?, val Year: String?, val Poster: String?, val Plot: String?, val Response: String?)
data class ScrapedMovie(val title: String, val link: String)

internal fun getRandomApiKey(): String {
    if (activeOmdbKeys.isEmpty()) activeOmdbKeys.addAll(baseOmdbKeys)
    return activeOmdbKeys[Random.nextInt(activeOmdbKeys.size)]
}

internal fun removeDeadKey(key: String) {
    activeOmdbKeys.remove(key)
}

// Lenient Token Matcher based on your Python Script
internal fun normalizeTitle(title: String): String {
    var text = title.lowercase().trim()
    text = text.replace("&", "and")
    text = text.replace(Regex("[^\\w\\s]"), "")
    text = text.replace(Regex("\\s+"), " ")
    return text
}

internal fun searchByTokenAndYear(movies: List<ScrapedMovie>, queryTitle: String, queryYear: String): List<ScrapedMovie> {
    val normQuery = normalizeTitle(queryTitle)
    val queryWords = normQuery.split(" ").toSet() - setOf("the", "a", "an", "and", "of", "to", "in", "for", "on", "with", "by")
    
    val matches = mutableListOf<Pair<ScrapedMovie, Double>>()

    for (movie in movies) {
        val titleLower = movie.title.lowercase()
        if (queryYear.isNotBlank() && !titleLower.contains(queryYear)) continue

        val normSite = normalizeTitle(movie.title)
        val siteWords = normSite.split(" ").toSet()
        
        if (queryWords.isEmpty()) continue
        val matchCount = queryWords.count { it in siteWords }.toDouble()
        val matchRatio = matchCount / queryWords.size

        if (matchRatio >= 0.6) {
            matches.add(Pair(movie, matchRatio))
        }
    }
    return matches.sortedByDescending { it.second }.map { it.first }
}

internal suspend fun fetchOmdbMetadata(rawTitle: String, fallbackYear: String = ""): Pair<OmdbTitleResponse?, String> {
    val cleanName = rawTitle.replace("isaiDub.me", "").replace("-", " ").trim()
    val yearRegex = Regex("\\b(19|20)\\d{2}\\b").find(cleanName)
    val extractedYear = yearRegex?.value ?: fallbackYear
    val finalSearchTitle = if (yearRegex != null) cleanName.replace(yearRegex.value, "").trim() else cleanName

    try {
        val encodedQuery = URLEncoder.encode(finalSearchTitle, "UTF-8")
        val apiKey = getRandomApiKey()
        val url = if (extractedYear.isNotBlank()) {
            "https://www.omdbapi.com/?apikey=$apiKey&t=$encodedQuery&y=$extractedYear"
        } else {
            "https://www.omdbapi.com/?apikey=$apiKey&t=$encodedQuery"
        }
        
        val response = omdbSemaphore.withPermit { app.get(url, timeout = 3) }

        if (response.code == 401 || response.text.contains("Limit reached", ignoreCase = true) || response.text.contains("Invalid API key", ignoreCase = true)) {
            removeDeadKey(apiKey)
        } else if (response.isSuccessful && response.text.contains("\"Response\":\"True\"")) {
            val parsed = AppUtils.tryParseJson<OmdbTitleResponse>(response.text)
            if (parsed != null && parsed.Poster != null && parsed.Poster != "N/A") {
                return Pair(parsed, extractedYear)
            }
        }
    } catch (e: Exception) { }
    return Pair(null, extractedYear) 
}

internal suspend fun MainAPI.searchDubbedMovieLinks(title: String, year: String): List<String> {
    val targets = mutableListOf<String>()
    
    year.toIntOrNull()?.let { targets.add("$mainUrl/tamil-$it-dubbed-movies/") }
    title.trim().firstOrNull()?.lowercaseChar()?.let {
        if (it.isLetter()) targets.add("$mainUrl/tamil-atoz-dubbed-movies/$it/") 
        else if (it.isDigit()) targets.add("$mainUrl/tamil-atoz-dubbed-movies/0-9/")
    }
    
    val matched = mutableListOf<ScrapedMovie>()
    for (url in targets.distinct()) {
        if (matched.isNotEmpty()) break
        val (movies, max) = scrapePageAndGetTotal(url)
        val hits = searchByTokenAndYear(movies, title, year)
        if (hits.isNotEmpty()) { matched.addAll(hits); break }
        
        if (max > 1) {
            coroutineScope {
                (2..minOf(max, 6)).map { p -> 
                    async { scrapePageAndGetTotal("$url?get-page=$p").first }
                }.awaitAll().forEach { pMovies ->
                    val pHits = searchByTokenAndYear(pMovies, title, year)
                    if (pHits.isNotEmpty()) matched.addAll(pHits)
                }
            }
        }
    }
    return matched.map { it.link }.distinct()
}

internal suspend fun MainAPI.scrapePageAndGetTotal(url: String): Pair<List<ScrapedMovie>, Int> {
    val cached = pageCache[url]
    if (cached != null && System.currentTimeMillis() - cached.first < CACHE_DURATION) {
        return cached.second
    }

    val movies = mutableListOf<ScrapedMovie>()
    var maxPage = 1
    try {
        val response = scrapeSemaphore.withPermit { app.get(url, timeout = 10) }
        if (!response.isSuccessful) return Pair(emptyList(), 1)
        
        val doc = response.document
        val movieDivs = doc.select("div.f")
        for (div in movieDivs) {
            val aTag = div.selectFirst("a")
            if (aTag != null) {
                val title = aTag.text().trim()
                var link = aTag.attr("href")
                if (link.startsWith("/")) link = "$mainUrl$link"
                movies.add(ScrapedMovie(title, link))
            }
        }

        val totalPagesSpan = doc.selectFirst("span#totalPages")
        if (totalPagesSpan != null) {
            maxPage = totalPagesSpan.text().trim().toIntOrNull() ?: 1
        }
    } catch (e: Exception) { }

    val result = Pair(movies, maxPage)
    pageCache[url] = Pair(System.currentTimeMillis(), result)
    return result
}
