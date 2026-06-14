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

// --- OPTIMIZATION: CONCURRENCY LIMITERS ---
internal val omdbSemaphore = Semaphore(5)
internal val scrapeSemaphore = Semaphore(5)

// --- OPTIMIZATION: IN-MEMORY CACHE ---
internal val pageCache = mutableMapOf<String, Pair<Long, Pair<List<ScrapedMovie>, Int>>>()
internal const val CACHE_DURATION = 5 * 60 * 1000L // 5 minutes

// --- OPTIMIZATION: SMART KEY EVICTION ---
internal val baseOmdbKeys = listOf(
    "4b447405", "eb0c0475", "7776cbde", "ff28f90b",
    "6c3a2d45", "b07b58c8", "ad04b643", "a95b5205",
    "777d9323", "2c2c3314", "b5cff164", "89a9f57d",
    "73a9858a", "efbd8357"
)
internal var activeOmdbKeys = baseOmdbKeys.toMutableList()

data class OmdbSearchResponse(val Search: List<OmdbSearchResult>?, val Response: String?)
data class OmdbSearchResult(val Title: String?, val Year: String?, val Poster: String?)
data class OmdbTitleResponse(val Title: String?, val Year: String?, val Poster: String?, val Plot: String?, val Response: String?)

data class ScrapedMovie(val title: String, val link: String)
data class ResolutionNode(val label: String, val url: String)

internal fun getRandomApiKey(): String {
    if (activeOmdbKeys.isEmpty()) activeOmdbKeys.addAll(baseOmdbKeys)
    return activeOmdbKeys[Random.nextInt(activeOmdbKeys.size)]
}

internal fun removeDeadKey(key: String) {
    activeOmdbKeys.remove(key)
}

// --- TOKENIZATION & MATCHING HELPERS ---

internal fun normalizeTitle(title: String): String {
    var text = title.lowercase().trim()
    text = text.replace("&", "and")
    text = text.replace("judgment", "judgement")
    text = text.replace("_", " ")

    text = text.replace(Regex("\\b(part|vol|chapter|volume)\\s+"), "")

    val romanMap = mapOf(
        "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5",
        "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9", "x" to "10"
    )
    romanMap.forEach { (roman, digit) ->
        text = text.replace(Regex("\\b$roman\\b"), digit)
    }
    return text
}

internal fun tokenize(text: String, yearToRemove: String = ""): Set<String> {
    var cleanText = text.lowercase()
    if (yearToRemove.isNotBlank()) cleanText = cleanText.replace(yearToRemove, "")
    return cleanText
        .replace(Regex("[^\u0000-\u007F]"), " ") 
        .replace(Regex("[^a-z0-9\\s]"), " ")   
        .replace(Regex("^(the|a|an)\\s+"), "")
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .toSet()
}

internal fun searchByTokenAndYear(movies: List<ScrapedMovie>, queryTitle: String, queryYear: String): List<ScrapedMovie> {
    val queryTokens = tokenize(normalizeTitle(queryTitle))
    val matches = mutableListOf<Pair<ScrapedMovie, Int>>()

    for (movie in movies) {
        val titleLower = movie.title.lowercase()
        if (queryYear.isNotBlank() && !titleLower.contains(queryYear)) continue

        val siteTokens = tokenize(normalizeTitle(movie.title), yearToRemove = queryYear)
        val commonTokens = queryTokens.intersect(siteTokens)
        val matchPercentage = commonTokens.size.toDouble() / queryTokens.size

        val isMatch = if (queryTokens.size <= 2) {
            matchPercentage == 1.0
        } else {
            val significantMatches = queryTokens.filter { siteTokens.contains(it) && it.length > 2 }
            matchPercentage >= 0.6 && significantMatches.isNotEmpty()
        }

        if (isMatch) matches.add(Pair(movie, commonTokens.size))
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

// Extension function on MainAPI so any provider can use it
internal suspend fun MainAPI.searchDubbedMovieLinks(title: String, year: String): List<String> {
    val targets = mutableListOf<String>()
    
    // Generates paths based on the calling provider's `mainUrl`
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
        val doc = scrapeSemaphore.withPermit { app.get(url, timeout = 15).document }
        
        val movieDivs = doc.select("div.f")
        for (div in movieDivs) {
            val aTag = div.selectFirst("a")
            if (aTag != null) {
                val title = aTag.text().trim()
                var link = aTag.attr("href")
                if (link.startsWith("/")) {
                    link = "$mainUrl$link"
                }
                movies.add(ScrapedMovie(title, link))
            }
        }

        val totalPagesSpan = doc.selectFirst("span#totalPages")
        if (totalPagesSpan != null) {
            maxPage = totalPagesSpan.text().trim().toIntOrNull() ?: 1
        } else {
            doc.select("a[href]").forEach { a ->
                val href = a.attr("href")
                val text = a.text().trim()
                if (href.contains("?get-page=") || href.contains("/page/")) {
                    val num = Regex("""(?:get-page=|page/)(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    if (num != null && num > maxPage && num <= 25) maxPage = num
                } else if (text.toIntOrNull() != null) {
                    val num = text.toIntOrNull()
                    if (num != null && num > maxPage && num <= 25 && href.length < 50) maxPage = num
                }
            }
        }
    } catch (e: Exception) { }

    val result = Pair(movies, maxPage)
    pageCache[url] = Pair(System.currentTimeMillis(), result)
    return result
}
