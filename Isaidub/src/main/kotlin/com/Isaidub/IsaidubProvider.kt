package com.isaidub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject
import org.json.JSONArray
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

data class ScrapedMovie(val title: String, val link: String)
data class SimpleTmdbMovie(
    val title: String, 
    val posterPath: String, 
    val overview: String, 
    val releaseDate: String
)

class IsaidubProvider : MainAPI() {

    override var mainUrl = "https://isaidub.ceo"
    override var name = "Isaidub"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "ta"
    override val hasMainPage = true

    private val tmdbSemaphore = Semaphore(15)
    private val scrapeSemaphore = Semaphore(5)

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val masterTmdbKeys = listOf(
        "fb7bb23f03b6994dafc674c074d01761", "e55425032d3d0f371fc776f302e7c09b",
        "8301a21598f8b45668d5711a814f01f6", "8cf43ad9c085135b9479ad5cf6bbcbda"
    ).distinct()

    private val allTmdbKeys = masterTmdbKeys.toMutableList()
    private var keyIndex = 0

    @Synchronized
    private fun getKeysRotated(): List<String> {
        if (allTmdbKeys.isEmpty()) return emptyList()
        val list = mutableListOf<String>()
        for (i in allTmdbKeys.indices) {
            list.add(allTmdbKeys[(keyIndex + i) % allTmdbKeys.size])
        }
        keyIndex = (keyIndex + 1) % allTmdbKeys.size
        return list
    }

    private suspend fun fetchFromTmdb(urlBuilder: (String) -> String): String? {
        for (key in getKeysRotated()) {
            val url = urlBuilder(key)
            try {
                val resp = tmdbSemaphore.withPermit { app.get(url, timeout = 5) }
                if (resp.isSuccessful) return resp.text
            } catch (e: Exception) { continue }
        }
        return null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val sectionUrl = request.data
        return newHomePageResponse(emptyList())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val jsonResponse = fetchFromTmdb { apiKey ->
            "https://api.tmdb.org/3/search/movie?api_key=$apiKey&query=$encodedQuery&language=en"
        } ?: return emptyList()

        val searchResults = mutableListOf<SearchResponse>()
        try {
            val jsonObject = JSONObject(jsonResponse)
            val resultsArray = jsonObject.optJSONArray("results") ?: JSONArray()
            for (i in 0 until resultsArray.length()) {
                val item = resultsArray.getJSONObject(i)
                val title = item.optString("title", "")
                val rDate = item.optString("release_date", "")
                if (title.isBlank() || rDate.isBlank()) continue
                val year = rDate.substringBefore("-")
                val poster = "https://image.tmdb.org/t/p/w500${item.optString("poster_path", "")}"
                val data = "$mainUrl/synthetic_meta?t=${URLEncoder.encode(title, "UTF-8")}&y=$year&p=${URLEncoder.encode(poster, "UTF-8")}"
                searchResults.add(newMovieSearchResponse(title, data) {
                    this.posterUrl = poster
                    this.year = year.toIntOrNull()
                })
            }
        } catch (e: Exception) { }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        if (!url.contains("synthetic_meta")) return null
        val uri = android.net.Uri.parse(url)
        val title = URLDecoder.decode(uri.getQueryParameter("t") ?: "", "UTF-8")
        val year = URLDecoder.decode(uri.getQueryParameter("y") ?: "", "UTF-8")
        val poster = URLDecoder.decode(uri.getQueryParameter("p") ?: "", "UTF-8")
        
        val foundMovie = findMoviePage(title, year) ?: return null
        
        return newMovieLoadResponse(title, foundMovie.link, TvType.Movie, foundMovie.link) {
            this.posterUrl = poster
            this.year = year.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = resolveAllLinks(data, depth = 0)
        for (linkItem in links) {
            callback.invoke(ExtractorLink(
                this.name,
                "Isaidub (${linkItem.first})",
                linkItem.second,
                mainUrl,
                Qualities.Unknown.value,
                isM3u8 = linkItem.second.contains(".m3u8")
            ))
        }
        return links.isNotEmpty()
    }

    private suspend fun findMoviePage(title: String, year: String): ScrapedMovie? {
        val yearUrl = "$mainUrl/tamil-$year-dubbed-movies/"
        val targetTokens = tokenize(title)
        var bestMatch: ScrapedMovie? = null
        var bestScore = -1

        suspend fun scan(url: String) {
            try {
                val doc = scrapeSemaphore.withPermit { app.get(url, headers = baseHeaders, timeout = 10).document }
                doc.select("a[href]").forEach { a ->
                    val href = resolveUrl(mainUrl, a.attr("href"))
                    if (!href.contains("/movie/")) return@forEach
                    val movieTitle = a.text().trim()
                    if (movieTitle.lowercase().contains("sample")) return@forEach
                    
                    val siteTokens = tokenize(movieTitle)
                    val score = targetTokens.intersect(siteTokens).size + (if (movieTitle.contains(year)) 2 else 0)
                    if (score > bestScore) {
                        bestScore = score
                        bestMatch = ScrapedMovie(movieTitle, href)
                    }
                }
            } catch (e: Exception) {}
        }

        scan(yearUrl)
        return bestMatch
    }

    private suspend fun resolveAllLinks(url: String, depth: Int, visited: MutableSet<String> = mutableSetOf()): List<Pair<String, String>> {
        if (depth > 12) return emptyList()
        val cleanUrl = url.lowercase().trimEnd('/')
        if (visited.contains(cleanUrl)) return emptyList()
        visited.add(cleanUrl)

        var html = ""
        try {
            val response = scrapeSemaphore.withPermit { app.get(url, headers = baseHeaders, timeout = 15) }
            if (isFinalUrl(response.url)) return listOf(Pair(extractResolution("", response.url), response.url))
            html = response.text
        } catch (e: Exception) { return emptyList() }

        val doc = Jsoup.parse(html)
        val results = mutableListOf<Pair<String, String>>()
        val linksToFollow = mutableSetOf<Pair<String, String>>()

        doc.select("a[href]").forEach { a ->
            val text = a.text().trim().ifBlank { a.attr("title").trim() }
            val href = a.attr("href")
            if (href.isBlank() || href == "#") return@forEach
            if (text.lowercase().contains("sample")) return@forEach
            
            val fullUrl = resolveUrl(url, href)
            val lowText = text.lowercase()
            val lowHref = href.lowercase()
            
            if (lowText.contains("download server") || lowText == "download" || 
                lowHref.contains("/movie/") || isFinalUrl(fullUrl) || 
                lowHref.contains("dubpage") || lowHref.contains("dubmv")) {
                linksToFollow.add(Pair(text.ifBlank { "Link" }, fullUrl))
            }
        }

        coroutineScope {
            linksToFollow.map { pair ->
                async {
                    if (isFinalUrl(pair.second)) listOf(Pair(extractResolution(pair.first, pair.second), pair.second))
                    else resolveAllLinks(pair.second, depth + 1, visited)
                }
            }.awaitAll().forEach { results.addAll(it) }
        }
        return results.distinctBy { it.second }
    }

    private fun isFinalUrl(url: String) = url.lowercase().run { endsWith(".mp4") || contains("download.php") || contains("uptodub.ch") }
    private fun extractResolution(text: String, url: String) = if (url.contains("1080")) "1080p" else if (url.contains("720")) "720p" else "360p"
    private fun tokenize(text: String) = Regex("[a-z0-9]+").findAll(text.lowercase()).map { it.value }.toSet()
    private fun resolveUrl(base: String, href: String) = if (href.startsWith("http")) href else "$mainUrl$href"
}
