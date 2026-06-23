package com.dubstamil

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

    override var mainUrl        = "https://isaidub.guru"
    override var name           = "DubsTamil"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang           = "ta"
    override val hasMainPage    = true

    private val tmdbSemaphore   = Semaphore(15)
    private val scrapeSemaphore = Semaphore(5)

    private val masterTmdbKeys = listOf(
        "fb7bb23f03b6994dafc674c074d01761", "e55425032d3d0f371fc776f302e7c09b",
        "8301a21598f8b45668d5711a814f01f6", "8cf43ad9c085135b9479ad5cf6bbcbda",
        "da63548086e399ffc910fbc08526df05", "13e53ff644a8bd4ba37b3e1044ad24f3",
        "269890f657dddf4635473cf4cf456576", "a2f888b27315e62e471b2d587048f32e",
        "8476a7ab80ad76f0936744df0430e67c", "5622cafbfe8f8cfe358a29c53e19bba0",
        "ae4bd1b6fce2a5648671bfc171d15ba4", "257654f35e3dff105574f97fb4b97035",
        "2f4038e83265214a0dcd6ec2eb3276f5", "9e43f45f94705cc8e1d5a0400d19a7b7",
        "af6887753365e14160254ac7f4345dd2", "06f10fc8741a672af455421c239a1ffc",
        "09ad8ace66eec34302943272db0e8d2c", "ea118e768e75a1fe3b53dc99c9e4de09"
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

    @Synchronized
    private fun markKeyDead(key: String) {
        allTmdbKeys.remove(key)
        if (allTmdbKeys.isEmpty()) {
            allTmdbKeys.addAll(masterTmdbKeys)
        }
    }

    private suspend fun fetchFromTmdb(urlBuilder: (String) -> String): String? {
        val keysToTry = getKeysRotated() 
        for (key in keysToTry) {
            val url = urlBuilder(key)
            try {
                val resp = tmdbSemaphore.withPermit { app.get(url, timeout = 5) }
                if (resp.code == 401 || resp.text.contains("Invalid API key", true) || resp.text.contains("rate limit", true)) {
                    markKeyDead(key)
                    continue 
                }
                if (resp.isSuccessful) {
                    return resp.text
                }
            } catch (e: Exception) {
                continue 
            }
        }
        return null
    }

    private val pageCache     = mutableMapOf<String, Pair<Long, Pair<List<ScrapedMovie>, Int>>>()
    private val cacheDuration = 5 * 60 * 1000L

    override val mainPage = mainPageOf(
        "$mainUrl/tamil-yearly-dubbed-movies/"  to "New Tamil Dubbed Movies",
        "$mainUrl/tamil-action-dubbed-movies/"  to "Tamil Dubbed Action Movies",
        "$mainUrl/tamil-comedy-dubbed-movies/"  to "Tamil Dubbed Comedy Movies",
        "$mainUrl/tamil-horror-dubbed-movies/"  to "Tamil Dubbed Horror Movies",
        "$mainUrl/tamil-family-dubbed-movies/"  to "Tamil Dubbed Family Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val sectionUrl = request.data
        val homePageLists = mutableListOf<HomePageList>()

        try {
            var targetUrl = sectionUrl
            if (sectionUrl.contains("yearly")) {
                val doc = scrapeSemaphore.withPermit { app.get(sectionUrl, timeout = 15).document }
                var resolved = ""
                val aTags = doc.select("a[href]")
                for (a in aTags) {
                    val href = a.attr("href")
                    if (href.contains(Regex("tamil-\\d{4}-dubbed-movies"))) {
                        resolved = if (href.startsWith("http")) href else "$mainUrl$href"
                        break
                    }
                }
                if (resolved.isEmpty()) return newHomePageResponse(emptyList(), hasNext = false)
                targetUrl = resolved
            }

            val yearMatch = Regex("\\d{4}").find(targetUrl)
            val year = yearMatch?.value ?: ""
            val items = fetchSectionItems(targetUrl, year)
            if (items.isNotEmpty()) {
                homePageLists.add(HomePageList(request.name, items, isHorizontalImages = false))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    private suspend fun fetchSectionItems(
        targetBaseUrl: String,
        sectionYear: String = ""
    ): List<SearchResponse> {
        val collected  = mutableListOf<SearchResponse>()
        var currentPage = 1
        val maxPages   = 3

        while (collected.size < 12 && currentPage <= maxPages) {
            val pageUrl = if (currentPage == 1) targetBaseUrl else "$targetBaseUrl?get-page=$currentPage"
            try {
                val doc = scrapeSemaphore.withPermit { app.get(pageUrl, timeout = 15).document }

                val candidates = mutableListOf<Pair<String, String>>()
                val aTags = doc.select("div.f a")
                for (a in aTags) {
                    val title = a.text().trim()
                    var link  = a.attr("href")
                    if (link.startsWith("/")) link = "$mainUrl$link"
                    val low = title.lowercase()
                    val lowL = link.lowercase()
                    if (low.contains("web series") || lowL.contains("web-series") ||
                        low.contains("season")      || low.contains("episode") ||
                        low.contains("series")      || lowL.contains("series")) {
                        continue
                    }
                    candidates.add(Pair(title, link))
                }

                if (candidates.isEmpty()) break

                val deferreds = mutableListOf<kotlinx.coroutines.Deferred<SearchResponse?>>()
                coroutineScope {
                    for (candidate in candidates) {
                        val rawTitle = candidate.first
                        val link = candidate.second
                        val def = async {
                            val cleanTitle = rawTitle.replace("isaiDub.me", "").replace(Regex("-"), " ").trim()
                            val (tmdb, resolvedYear) = fetchTmdbTitle(cleanTitle, sectionYear)

                            if (tmdb == null) return@async null
                            
                            val poster = "https://image.tmdb.org/t/p/w500${tmdb.posterPath}"
                            val plot   = tmdb.overview

                            val t  = URLEncoder.encode(cleanTitle,   "UTF-8")
                            val y  = URLEncoder.encode(resolvedYear, "UTF-8")
                            val p  = URLEncoder.encode(poster,       "UTF-8")
                            val u  = URLEncoder.encode(link,         "UTF-8")
                            val s  = URLEncoder.encode(plot,         "UTF-8")
                            val st = URLEncoder.encode(rawTitle,     "UTF-8") 
                            val data = "$mainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=$u&s=$s&st=$st"

                            newMovieSearchResponse(cleanTitle, data) {
                                this.posterUrl = poster
                                this.year      = resolvedYear.toIntOrNull()
                            }
                        }
                        deferreds.add(def)
                    }
                }
                
                val results = deferreds.awaitAll()
                for (r in results) {
                    if (r != null) {
                        var isDuplicate = false
                        for (c in collected) {
                            if (c.name == r.name) isDuplicate = true
                        }
                        if (collected.size < 12 && !isDuplicate) collected.add(r)
                    }
                }

                val maxPageStr = doc.selectFirst("span#totalPages")?.text()?.trim()
                val maxPageNum = maxPageStr?.toIntOrNull() ?: 1
                if (currentPage >= maxPageNum) break

            } catch (e: Exception) { break }
            currentPage++
        }
        return collected
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        val jsonResponse = fetchFromTmdb { apiKey ->
            "https://api.tmdb.org/3/search/movie?api_key=$apiKey&query=$encodedQuery&language=ta"
        } ?: return emptyList()

        val searchResults = mutableListOf<SearchResponse>()
        
        try {
            val jsonObject = JSONObject(jsonResponse)
            val resultsArray = jsonObject.optJSONArray("results") ?: JSONArray()
            
            for (i in 0 until resultsArray.length()) {
                val item = resultsArray.getJSONObject(i)
                val votes = item.optInt("vote_count", 0)
                val posterPath = item.optString("poster_path", "")
                val rDate = item.optString("release_date", "")
                val isUnreleased = rDate.isBlank()
                
                if (posterPath.isBlank() || isUnreleased || votes < 100) continue
                
                val title = item.optString("title", "")
                if (title.isBlank()) continue
                
                val year   = rDate.substringBefore("-")
                val poster = "https://image.tmdb.org/t/p/w500$posterPath"
                val plot   = item.optString("overview", "")
                
                val t  = URLEncoder.encode(title,  "UTF-8")
                val y  = URLEncoder.encode(year,   "UTF-8")
                val p  = URLEncoder.encode(poster, "UTF-8")
                val s  = URLEncoder.encode(plot,   "UTF-8")
                val st = "" 
                val data = "$mainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=&s=$s&st=$st"
                
                searchResults.add(
                    newMovieSearchResponse(title, data) {
                        this.posterUrl = poster
                        this.year      = year.toIntOrNull()
                    }
                )
            }
        } catch (e: Exception) { }

        if (searchResults.isEmpty()) {
            val fallbackUrl = "$mainUrl/search.php?q=$encodedQuery"
            try {
                val document = app.get(fallbackUrl).document
                val divs = document.select("div.f, div.f1")
                for (div in divs) {
                    val a = div.selectFirst("a")
                    if (a != null) {
                        val title = a.text().trim()
                        val href = fixUrl(a.attr("href"))
                        if (title.isNotBlank() && href.isNotBlank()) {
                            searchResults.add(newMovieSearchResponse(title, href, TvType.Movie))
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        if (!url.contains("synthetic_meta")) {
            return newMovieLoadResponse("Isaidub Movie", url, TvType.Movie, url)
        }

        val uri      = android.net.Uri.parse(url)
        val title    = URLDecoder.decode(uri.getQueryParameter("t")   ?: "", "UTF-8")
        val year     = URLDecoder.decode(uri.getQueryParameter("y")   ?: "", "UTF-8")
        val poster   = URLDecoder.decode(uri.getQueryParameter("p")   ?: "", "UTF-8")
        var synopsis = URLDecoder.decode(uri.getQueryParameter("s")   ?: "", "UTF-8")
        var moviePageUrl = URLDecoder.decode(uri.getQueryParameter("url") ?: "", "UTF-8")
        var scrapedTitle = URLDecoder.decode(uri.getQueryParameter("st")  ?: "", "UTF-8")

        if (title.isBlank()) return null

        if (moviePageUrl.isBlank()) {
            var foundMovie: ScrapedMovie? = null
            var plotStr = ""
            coroutineScope {
                val pageDeferred = async { findMoviePage(title, year) }
                val plotDeferred = async {
                    if (synopsis.isBlank()) {
                        val (tmdb, _) = fetchTmdbTitle(title, year)
                        tmdb?.overview ?: ""
                    } else synopsis
                }
                foundMovie = pageDeferred.await()
                plotStr = plotDeferred.await()
            }
            if (foundMovie == null) return null
            moviePageUrl = foundMovie!!.link
            synopsis = plotStr
            
            if (scrapedTitle.isBlank()) scrapedTitle = foundMovie!!.title
        }

        if (synopsis.isBlank()) {
            val (tmdb, _) = fetchTmdbTitle(title, year)
            synopsis = tmdb?.overview ?: ""
        }

        val dataUrl = "$mainUrl/synthetic_meta" +
            "?t=${URLEncoder.encode(title,         "UTF-8")}" +
            "&y=${URLEncoder.encode(year,          "UTF-8")}" +
            "&p=${URLEncoder.encode(poster,        "UTF-8")}" +
            "&url=${URLEncoder.encode(moviePageUrl,"UTF-8")}" +
            "&s=${URLEncoder.encode(synopsis,      "UTF-8")}" +
            "&st=${URLEncoder.encode(scrapedTitle, "UTF-8")}"

        return newMovieLoadResponse(title, dataUrl, TvType.Movie, dataUrl) {
            this.posterUrl = poster.ifBlank { null }
            this.year      = year.toIntOrNull()
            this.plot      = synopsis.ifBlank { null }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var moviePageUrl = ""
        var actualScrapedTitle = ""

        if (data.contains("synthetic_meta")) {
            val uri = android.net.Uri.parse(data)
            val parsedUrl = URLDecoder.decode(uri.getQueryParameter("url") ?: "", "UTF-8")
            actualScrapedTitle = URLDecoder.decode(uri.getQueryParameter("st") ?: "", "UTF-8")
            
            if (parsedUrl.isBlank()) {
                val title = URLDecoder.decode(uri.getQueryParameter("t") ?: "", "UTF-8")
                val year  = URLDecoder.decode(uri.getQueryParameter("y") ?: "", "UTF-8")
                val foundMovie = findMoviePage(title, year)
                if (foundMovie == null) return false
                moviePageUrl = foundMovie.link
                actualScrapedTitle = foundMovie.title
            } else {
                moviePageUrl = parsedUrl
            }
        } else {
            moviePageUrl = data
        }

        if (moviePageUrl.isBlank()) return false
        
        if (actualScrapedTitle.isBlank()) {
            val uri = android.net.Uri.parse(data)
            actualScrapedTitle = URLDecoder.decode(uri.getQueryParameter("t") ?: "Movie", "UTF-8")
        }

        val links = resolveAllLinks(moviePageUrl, depth = 0)
        if (links.isEmpty()) return false

        for (linkItem in links) {
            val label = linkItem.first
            val finalUrl = linkItem.second
            val sourceName = "Isaidub ($label) \"$actualScrapedTitle\""

            val isM3u8 = finalUrl.contains(".m3u8")

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name   = sourceName,
                    url    = finalUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type   = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
        return true
    }

    private suspend fun fetchTmdbTitle(
        rawTitle: String,
        fallbackYear: String = ""
    ): Pair<SimpleTmdbMovie?, String> {
        val clean     = rawTitle.replace("isaiDub.me", "").replace(Regex("-"), " ").trim()
        val yearMatch = Regex("\\b(19|20)\\d{2}\\b").find(clean)
        val usedYear  = yearMatch?.value ?: fallbackYear
        val searchTitle = if (yearMatch != null) clean.replace(yearMatch.value, "").trim() else clean
        val encoded = URLEncoder.encode(searchTitle, "UTF-8")

        val jsonResponse = fetchFromTmdb { apiKey ->
            var url = "https://api.tmdb.org/3/search/movie?api_key=$apiKey&query=$encoded"
            if (usedYear.isNotBlank()) {
                url += "&primary_release_year=$usedYear"
            }
            url
        }

        if (jsonResponse != null) {
            try {
                val jsonObject = JSONObject(jsonResponse)
                val resultsArray = jsonObject.optJSONArray("results") ?: JSONArray()
                
                for (i in 0 until resultsArray.length()) {
                    val item = resultsArray.getJSONObject(i)
                    val poster = item.optString("poster_path", "")
                    val rDate = item.optString("release_date", "")
                    val isUnreleased = rDate.isBlank()
                    
                    if (poster.isNotBlank() && !isUnreleased) {
                        val movie = SimpleTmdbMovie(
                            title = item.optString("title", ""),
                            posterPath = poster,
                            overview = item.optString("overview", ""),
                            releaseDate = rDate
                        )
                        return Pair(movie, usedYear)
                    }
                }
            } catch (e: Exception) {}
        }
        return Pair(null, usedYear)
    }

    private suspend fun findMoviePage(title: String, year: String): ScrapedMovie? {
        if (year.isBlank()) return null

        val searchUrl = "$mainUrl/tamil-$year-dubbed-movies/"
        var bestMatchMovie: ScrapedMovie? = null
        var bestMatchScore = 0
        
        val targetTokens = tokenize("$title $year")
        val targetTokenCount = targetTokens.size

        val firstPageData = scrapePage(searchUrl)
        val firstPageMovies = firstPageData.first
        val maxPage = firstPageData.second

        for (movie in firstPageMovies) {
            if (!movie.title.contains(year)) continue
            val siteTokens = tokenize(movie.title)
            var score = 0
            for (token in targetTokens) {
                if (siteTokens.contains(token)) score++
            }
            if (score > bestMatchScore) {
                bestMatchScore = score
                bestMatchMovie = movie
            }
        }

        if (bestMatchScore >= targetTokenCount) {
            return bestMatchMovie
        }

        if (maxPage > 1) {
            val limit = if (maxPage > 10) 10 else maxPage
            val deferreds = mutableListOf<kotlinx.coroutines.Deferred<List<ScrapedMovie>>>()
            coroutineScope {
                for (p in 2..limit) {
                    val def = async {
                        val pageData = scrapePage("$searchUrl?get-page=$p")
                        pageData.first
                    }
                    deferreds.add(def)
                }
            }
            val otherPagesMovies = deferreds.awaitAll()
            for (pageMovies in otherPagesMovies) {
                for (movie in pageMovies) {
                    if (!movie.title.contains(year)) continue
                    val siteTokens = tokenize(movie.title)
                    var score = 0
                    for (token in targetTokens) {
                        if (siteTokens.contains(token)) score++
                    }
                    if (score > bestMatchScore) {
                        bestMatchScore = score
                        bestMatchMovie = movie
                    }
                }
            }
        }

        return bestMatchMovie
    }

    private suspend fun scrapePage(url: String): Pair<List<ScrapedMovie>, Int> {
        val cached = pageCache[url]
        if (cached != null) {
            if (System.currentTimeMillis() - cached.first < cacheDuration) {
                return cached.second
            }
        }

        var movies = emptyList<ScrapedMovie>()
        var maxPage = 1

        try {
            val resp = scrapeSemaphore.withPermit { app.get(url, timeout = 10) }
            if (resp.isSuccessful) {
                val doc = resp.document
                val parsedMovies = mutableListOf<ScrapedMovie>()
                val divs = doc.select("div.f")
                for (div in divs) {
                    val a = div.selectFirst("a")
                    if (a != null) {
                        val t = a.text().trim()
                        var l = a.attr("href")
                        if (l.startsWith("/")) l = "$mainUrl$l"
                        if (t.isNotBlank() && l.isNotBlank()) {
                            parsedMovies.add(ScrapedMovie(t, l))
                        }
                    }
                }
                movies = parsedMovies
                val parsedMax = doc.selectFirst("span#totalPages")?.text()?.trim()?.toIntOrNull()
                if (parsedMax != null) {
                    maxPage = parsedMax
                }
            }
        } catch (e: Exception) {}

        val resultPair = Pair(movies, maxPage)
        pageCache[url] = Pair(System.currentTimeMillis(), resultPair)
        return resultPair
    }

    private fun tokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val matches = Regex("[a-z0-9]+").findAll(text.lowercase())
        for (match in matches) {
            tokens.add(match.value)
        }
        return tokens
    }

    private suspend fun resolveAllLinks(url: String, depth: Int): List<Pair<String, String>> {
        if (depth > 15) return emptyList()

        var html = ""
        var doc: org.jsoup.nodes.Document? = null
        try {
            val response = scrapeSemaphore.withPermit { app.get(url, timeout = 15, referer = mainUrl) }
            if (!response.isSuccessful) return emptyList()
            html = response.text
            doc = response.document
        } catch (e: Exception) {
            return emptyList()
        }

        if (doc == null) return emptyList()

        val rawFinal = extractFinalFromHtml(html)
        if (rawFinal != null) {
            val res = extractResolution("", rawFinal)
            return listOf(Pair(res, rawFinal))
        }

        var links = emptyList<Pair<String, String>>()
        if (url.contains("isaidub.guru") && !url.contains("/download/")) {
            links = extractIsaidubLinks(doc, url)
            if (links.isEmpty()) {
                links = extractDownloadLinks(doc, url)
            }
        } else {
            links = extractDownloadLinks(doc, url)
        }

        if (links.isEmpty()) return emptyList()

        var dlServer: Pair<String, String>? = null
        for (link in links) {
            if (link.first.lowercase().contains("download server")) {
                dlServer = link
                break
            }
        }

        if (dlServer != null) {
            return resolveAllLinks(dlServer.second, depth + 1)
        }

        if (links.size == 1) {
            return resolveAllLinks(links[0].second, depth + 1)
        }

        val deferreds = mutableListOf<kotlinx.coroutines.Deferred<List<Pair<String, String>>>>()
        coroutineScope {
            for (linkPair in links) {
                val label = linkPair.first
                val linkUrl = linkPair.second
                val def = async {
                    if (isFinalUrl(linkUrl)) {
                        val res = extractResolution(label, linkUrl)
                        listOf(Pair(res, linkUrl))
                    } else {
                        val resolvedLinks = resolveAllLinks(linkUrl, depth + 1)
                        val formattedLinks = mutableListOf<Pair<String, String>>()
                        for (resolved in resolvedLinks) {
                            val lbl = resolved.first
                            val u = resolved.second
                            var finalLabel = extractResolution(label, u)
                            if (finalLabel.isBlank()) finalLabel = lbl
                            formattedLinks.add(Pair(finalLabel, u))
                        }
                        formattedLinks
                    }
                }
                deferreds.add(def)
            }
        }
        
        val nestedResults = deferreds.awaitAll()
        val finalLinks = mutableListOf<Pair<String, String>>()
        for (nested in nestedResults) {
            finalLinks.addAll(nested)
        }

        val uniqueLinks = mutableListOf<Pair<String, String>>()
        val seenUrls = mutableSetOf<String>()
        for (item in finalLinks) {
            if (!seenUrls.contains(item.second)) {
                seenUrls.add(item.second)
                uniqueLinks.add(item)
            }
        }
        return uniqueLinks
    }

    private fun extractResolution(text: String, url: String): String {
        val combined = (text + url).lowercase()
        
        val exactMatch = Regex("""\d{3,4}x\d{3,4}""").find(combined)
        if (exactMatch != null) return exactMatch.value

        return when {
            combined.contains("1080") || combined.contains("1920") -> "1920x1080"
            combined.contains("720")  || combined.contains("1280") -> "1280x720"
            combined.contains("640")  || combined.contains("360")  -> "640x360"
            combined.contains("480")  || combined.contains("320")  -> "480x320"
            else -> "HD"
        }
    }

    private fun isFinalUrl(url: String): Boolean {
        val low = url.lowercase()
        return low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".avi") ||
               low.endsWith(".mov") || low.endsWith(".webm") ||
               low.contains("download.php") || low.contains("dl.php")
    }

    private fun extractFinalFromHtml(html: String): String? {
        val m1 = Regex("""https?://[^\s"'<>]*download\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(html)
        if (m1 != null) return m1.value
        val m2 = Regex("""https?://[^\s"'<>]*dl\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(html)
        if (m2 != null) return m2.value
        val m3 = Regex("""https?://[^\s"'<>]*\.mp4[^"'\s]*""", RegexOption.IGNORE_CASE).find(html)
        if (m3 != null) return m3.value
        return null
    }

    private fun extractIsaidubLinks(
        doc: org.jsoup.nodes.Document,
        baseUrl: String
    ): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val divs = doc.select("div.f, div.bf")
        for (div in divs) {
            val a = div.selectFirst("a[href]") ?: continue
            val text = a.text().trim()
            val href = a.attr("href")
            if (text.isBlank() || href.isBlank()) continue
            if (text.lowercase().contains("sample")) continue
            
            val fullUrl = resolveUrl(baseUrl, href)
            val low = fullUrl.lowercase().trimEnd('/')
            
            if (low.contains("?get-page=") || low.contains("/category/")) continue
            if (low == baseUrl.lowercase().trimEnd('/')) continue
            
            results.add(Pair(text, fullUrl))
        }
        val uniqueResults = mutableListOf<Pair<String, String>>()
        val seenUrls = mutableSetOf<String>()
        for (item in results) {
            if (!seenUrls.contains(item.second)) {
                seenUrls.add(item.second)
                uniqueResults.add(item)
            }
        }
        return uniqueResults
    }

    private fun extractDownloadLinks(
        doc: org.jsoup.nodes.Document,
        baseUrl: String
    ): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        val aTags = doc.select("a[href]")
        for (a in aTags) {
            val text = a.text().trim()
            val href = a.attr("href")
            if (text.isBlank() || href.isBlank()) continue
            val full = resolveUrl(baseUrl, href)
            val low  = full.lowercase()
            val lowT = text.lowercase()
            
            var matched = false
            if (lowT.contains("download server") || lowT.contains("download")) {
                matched = true
            } else if (low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".avi")) {
                matched = true
            } else if (low.contains("download.php") || low.contains("dl.php")) {
                matched = true
            } else if (low.contains("dubpage.xyz") || low.contains("dubmv.xyz") || low.contains("dub.uptodub.ch")) {
                matched = true
            }
            
            if (matched) {
                results.add(Pair(text, full))
            }
        }
        val uniqueResults = mutableListOf<Pair<String, String>>()
        val seenUrls = mutableSetOf<String>()
        for (item in results) {
            if (!seenUrls.contains(item.second)) {
                seenUrls.add(item.second)
                uniqueResults.add(item)
            }
        }
        return uniqueResults
    }

    private fun resolveUrl(base: String, href: String): String {
        if (href.startsWith("http")) return href
        if (href.startsWith("//")) return "https:$href"
        if (href.startsWith("/")) {
            val u = android.net.Uri.parse(base)
            return "${u.scheme}://${u.host}$href"
        }
        val baseClean = base.trimEnd('/')
        val idx = baseClean.lastIndexOf('/')
        val basePath = if (idx > 7) baseClean.substring(0, idx) else baseClean
        return "$basePath/$href"
    }
}
