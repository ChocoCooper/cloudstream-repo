package com.isaidub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URLEncoder
import kotlin.random.Random

class IsaidubProvider : MainAPI() {
    override var mainUrl = "https://isaidub.guru" 
    val kuttyUrl = "https://kuttymovies1.fast"
    
    override var name = "Isaidub & KuttyMovies"
    override val hasMainPage = true 
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "ta"

    // Massive OMDb Key Rotation Array
    private val omdbApiKeys = listOf(
        "4b447405", "eb0c0475", "7776cbde", "ff28f90b",
        "6c3a2d45", "b07b58c8", "ad04b643", "a95b5205",
        "777d9323", "2c2c3314", "b5cff164", "89a9f57d",
        "73a9858a", "efbd8357"
    )

    // OMDb JSON Data Models
    data class OmdbSearchResponse(val Search: List<OmdbSearchResult>?, val Response: String?)
    data class OmdbSearchResult(val Title: String?, val Year: String?, val Poster: String?)
    data class OmdbTitleResponse(val Title: String?, val Year: String?, val Poster: String?, val Plot: String?, val Response: String?)
    
    data class ScrapedMovie(val title: String, val link: String)

    fun getRandomApiKey() = omdbApiKeys[Random.nextInt(omdbApiKeys.size)]

    fun normalizeTitle(title: String): String {
        var text = title.lowercase().trim().replace("&", "and").replace("judgment", "judgement").replace("_", " ")
        val romanMap = mapOf("ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9")
        romanMap.forEach { (roman, digit) -> text = text.replace(Regex("\\b(part|vol|chapter|volume)\\s+$roman\\b"), digit) }
        return text
    }

    fun tokenize(text: String, yearToRemove: String = ""): Set<String> {
        var cleanText = text.lowercase()
        if (yearToRemove.isNotBlank()) cleanText = cleanText.replace(yearToRemove, "")
        return cleanText.replace(Regex("[^\u0000-\u007F]"), " ").replace(Regex("[^a-z0-9\\s]"), " ").replace(Regex("^(the|a|an)\\s+"), "").split(Regex("\\s+")).filter { it.isNotBlank() }.toSet()
    }

    fun searchByTokenAndYear(movies: List<ScrapedMovie>, queryTitle: String, queryYear: String): List<ScrapedMovie> {
        val queryTokens = tokenize(normalizeTitle(queryTitle))
        val matches = mutableListOf<Pair<ScrapedMovie, Int>>()
        for (movie in movies) {
            if (queryYear.isNotBlank() && !movie.title.lowercase().contains(queryYear)) continue
            val siteTokens = tokenize(normalizeTitle(movie.title), yearToRemove = queryYear)
            val commonTokens = queryTokens.intersect(siteTokens)
            val isMatch = if (queryTokens.size <= 2) (commonTokens.size.toDouble() / queryTokens.size) == 1.0 
                          else (commonTokens.size.toDouble() / queryTokens.size) >= 0.6 && queryTokens.filter { siteTokens.contains(it) && it.length > 2 }.isNotEmpty()
            if (isMatch) matches.add(Pair(movie, commonTokens.size))
        }
        return matches.sortedByDescending { it.second }.map { it.first }
    }

    suspend fun fetchOmdbMetadata(rawTitle: String, fallbackYear: String = ""): Pair<OmdbTitleResponse?, String> {
        val cleanName = rawTitle.replace("isaiDub.me", "", true).replace("KuttyMovies", "", true).replace("-", " ").replace("_", " ").trim()
        val yearRegex = Regex("\\b(19|20)\\d{2}\\b").find(cleanName)
        val extractedYear = yearRegex?.value ?: fallbackYear
        val finalSearchTitle = if (yearRegex != null) cleanName.replace(yearRegex.value, "").trim() else cleanName

        try {
            val encodedQuery = URLEncoder.encode(finalSearchTitle, "UTF-8")
            val url = if (extractedYear.isNotBlank()) "https://www.omdbapi.com/?apikey=${getRandomApiKey()}&t=$encodedQuery&y=$extractedYear"
                      else "https://www.omdbapi.com/?apikey=${getRandomApiKey()}&t=$encodedQuery"
            
            val response = app.get(url, timeout = 3)
            if (response.isSuccessful && response.text.contains("\"Response\":\"True\"")) {
                val parsed = AppUtils.parseJson<OmdbTitleResponse>(response.text)
                if (parsed.Poster != null && parsed.Poster != "N/A") {
                    return Pair(parsed, extractedYear)
                }
            }
        } catch (e: Exception) { }
        return Pair(null, extractedYear) 
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageLists = mutableListOf<HomePageList>()
        try {
            coroutineScope {
                val isaidubLists = async { IsaidubSource.getHomePages(this@IsaidubProvider) }
                val kuttyLists = async { KuttyMoviesSource.getHomePages(this@IsaidubProvider) }
                homePageLists.addAll(isaidubLists.await())
                homePageLists.addAll(kuttyLists.await())
            }
        } catch (e: Exception) { e.printStackTrace() }
        return newHomePageResponse(homePageLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        var omdbJson: NiceResponse? = null
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        try {
            val response = app.get("https://www.omdbapi.com/?apikey=${getRandomApiKey()}&s=$encodedQuery&type=movie", timeout = 3)
            if (response.isSuccessful && response.text.contains("\"Response\":\"True\"")) { omdbJson = response }
        } catch (e: Exception) { }
        
        if (omdbJson == null) return emptyList()
        val parsed = AppUtils.parseJson<OmdbSearchResponse>(omdbJson.text)
        
        return (parsed.Search?.filter { it.Poster != null && it.Poster != "N/A" } ?: emptyList()).map { item ->
            val title = item.Title ?: ""
            val year = item.Year?.replace(Regex("[^0-9]"), "") ?: "" 
            val fullPoster = item.Poster ?: ""
            
            val t = URLEncoder.encode(title, "UTF-8"); val y = URLEncoder.encode(year, "UTF-8")
            val p = URLEncoder.encode(fullPoster, "UTF-8"); val s = URLEncoder.encode("", "UTF-8") 
            val targetData = "$mainUrl/synthetic_meta?t=$t&y=$y&p=$p&s=$s"

            newMovieSearchResponse(title, targetData) { this.posterUrl = fullPoster; this.year = year.toIntOrNull() }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        if (!url.contains("/synthetic_meta?")) return null
        val uri = java.net.URI(url)
        val params = uri.query?.split("&")?.associate { val parts = it.split("="); parts[0] to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8") } ?: return null

        val title = params["t"] ?: return null
        val year = params["y"] ?: ""
        val omdbPoster = params["p"] ?: "$mainUrl/uploads/posters/default.jpg"
        val failSafeUrl = params["url"] 
        val plotSynopsis = params["s"] ?: "" 
        val yearInt = year.toIntOrNull()

        val collectedLinks = mutableListOf<String>()
        coroutineScope {
            val isaidubScan = async { IsaidubSource.searchMovieLinks(this@IsaidubProvider, title, year) }
            val kuttyScan = async { KuttyMoviesSource.searchMovieLinks(this@IsaidubProvider, title, year) }
            collectedLinks.addAll(isaidubScan.await())
            collectedLinks.addAll(kuttyScan.await())
        }

        if (collectedLinks.isEmpty()) {
            if (!failSafeUrl.isNullOrBlank()) {
                return newMovieLoadResponse(title, failSafeUrl, TvType.Movie, failSafeUrl) { this.posterUrl = omdbPoster; this.year = yearInt; this.plot = plotSynopsis }
            }
            return null
        }

        val combinedDataString = collectedLinks.distinct().joinToString(",")
        return newMovieLoadResponse(title, combinedDataString, TvType.Movie, combinedDataString) {
            this.posterUrl = omdbPoster; this.year = yearInt; this.plot = plotSynopsis 
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        var foundAny = false
        coroutineScope {
            data.split(",").map { url ->
                async {
                    val cleanUrl = url.trim()
                    if (cleanUrl.contains("kuttymovies") || cleanUrl.contains("kuttydownload")) {
                        if (KuttyMoviesSource.extractLinks(this@IsaidubProvider, cleanUrl, callback)) foundAny = true
                    } else {
                        if (IsaidubSource.extractLinks(this@IsaidubProvider, cleanUrl, callback)) foundAny = true
                    }
                }
            }.awaitAll()
        }
        return foundAny
    }
}
