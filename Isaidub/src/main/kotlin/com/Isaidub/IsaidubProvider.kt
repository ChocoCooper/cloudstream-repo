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

    // NOTE: mainUrl is now a *runtime* value. It starts as the stable
    // "domain finder" URL (isaidub.io) which always redirects (via meta-refresh
    // or JS) to whatever the current working domain is (e.g. isaidub.movie).
    // Every entry-point (getMainPage/search/load/loadLinks) calls
    // resolveActiveDomain() first, which updates this var if the cache is stale.
    override var mainUrl        = "https://isaidub.io"
    override var name           = "Isaidub"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang           = "ta"
    override val hasMainPage    = true

    private val tmdbSemaphore   = Semaphore(15)
    private val scrapeSemaphore = Semaphore(8) // Slightly increased for the deep-inspection concurrent requests

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
    )

    // ==============================================================
    // DYNAMIC DOMAIN RESOLUTION
    // ==============================================================

    // This is the stable "landing" URL that always exists and always
    // redirects to the current live domain. Do NOT change this to the
    // live domain — it's the anchor we bounce off every time to find
    // wherever the site currently lives.
    private val domainAnchorUrl = "https://isaidub.io"

    private var cachedMainUrl: String = mainUrl
    private var lastDomainCheckMs: Long = 0L
    private val domainCacheTtlMs = 20 * 60 * 1000L // re-check every 20 minutes

    /**
     * Resolves the currently active domain by hitting the stable anchor URL
     * and following whatever redirect mechanism it uses (HTTP redirect,
     * <meta http-equiv="refresh">, or a fallback <a href> on the page).
     * Caches the result and mutates [mainUrl] so all existing code (which
     * already references mainUrl everywhere) keeps working unchanged.
     */
    @Synchronized
    private fun domainCacheIsFresh(): Boolean {
        return cachedMainUrl.isNotBlank() &&
            (System.currentTimeMillis() - lastDomainCheckMs) < domainCacheTtlMs
    }

    private suspend fun resolveActiveDomain(): String {
        if (domainCacheIsFresh()) return cachedMainUrl

        try {
            val response = scrapeSemaphore.withPermit {
                app.get(domainAnchorUrl, headers = baseHeaders, timeout = 10, allowRedirects = true)
            }

            var resolved = response.url.trimEnd('/')

            // If the HTTP client followed a real 3xx redirect, response.url already
            // points at the new domain. If the site instead uses a meta-refresh /
            // JS redirect (as isaidub.io currently does), response.url will still be
            // the anchor URL itself, so we need to parse the HTML for the real target.
            if (resolved.trimEnd('/') == domainAnchorUrl.trimEnd('/') ||
                resolved.contains("isaidub.io")
            ) {
                val doc = try { response.document } catch (e: Exception) { null }
                var target: String? = null

                if (doc != null) {
                    // 1) <meta http-equiv="refresh" content="5;url=https://newdomain/">
                    val metaContent = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                    if (!metaContent.isNullOrBlank()) {
                        val m = Regex("url=(.+)", RegexOption.IGNORE_CASE).find(metaContent)
                        if (m != null) {
                            target = m.groupValues[1].trim('\'', '"', ' ')
                        }
                    }

                    // 2) fallback: the visible "Go to new site" anchor link
                    if (target.isNullOrBlank()) {
                        target = doc.select("a[href^=http]").firstOrNull { a ->
                            !a.attr("href").contains("isaidub.io", ignoreCase = true)
                        }?.attr("href")
                    }

                    // 3) fallback: parse it straight out of the inline redirect script
                    //    e.g. window.location.href = "https://newdomain/";
                    if (target.isNullOrBlank()) {
                        val scriptText = doc.select("script").joinToString("\n") { it.data() }
                        val m = Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(scriptText)
                        target = m?.groupValues?.get(1)
                    }
                }

                if (!target.isNullOrBlank()) {
                    resolved = target.trim().trimEnd('/')
                }
            }

            if (resolved.isNotBlank() && resolved.startsWith("http") &&
                !resolved.contains("isaidub.io")
            ) {
                synchronized(this) {
                    cachedMainUrl = resolved
                    lastDomainCheckMs = System.currentTimeMillis()
                }
                mainUrl = resolved
            }
        } catch (e: Exception) {
            // Network hiccup or anchor unreachable: keep using whatever we had
            // cached before (or the anchor itself as last resort) rather than
            // throwing and breaking the whole provider.
        }

        return cachedMainUrl.ifBlank { mainUrl }
    }

    // ==============================================================

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

    private suspend fun fetchTmdbTitle(title: String, year: String = ""): Pair<SimpleTmdbMovie?, String> {
        val encodedQuery = URLEncoder.encode(title, "UTF-8")

        suspend fun search(y: String): String? {
            return fetchFromTmdb { apiKey ->
                val base = "https://api.tmdb.org/3/search/movie?api_key=$apiKey&query=$encodedQuery&language=en"
                if (y.isNotBlank()) "$base&year=$y" else base
            }
        }

        var jsonResponse = search(year)
        var jsonObject = jsonResponse?.let { JSONObject(it) }
        var resultsArray = jsonObject?.optJSONArray("results") ?: JSONArray()

        // Fallback strategy: If exact year fails, search globally (frequent metadata mismatch on pirated sites)
        if (resultsArray.length() == 0 && year.isNotBlank()) {
            jsonResponse = search("")
            jsonObject = jsonResponse?.let { JSONObject(it) }
            resultsArray = jsonObject?.optJSONArray("results") ?: JSONArray()
        }

        if (resultsArray.length() == 0) return Pair(null, year)

        val item = resultsArray.getJSONObject(0)
        val posterPath = item.optString("poster_path", "")
        
        // Strict null poster drop
        if (posterPath.isBlank() || posterPath == "null") return Pair(null, year)

        val rDate = item.optString("release_date", "")
        val resolvedYear = rDate.substringBefore("-").ifBlank { year }

        val movie = SimpleTmdbMovie(
            title = item.optString("title", title),
            posterPath = posterPath,
            overview = item.optString("overview", ""),
            releaseDate = rDate
        )
        return Pair(movie, resolvedYear)
    }

    // mainPage now stores RELATIVE paths + names only. The full URL is built
    // at request time in getMainPage() using the freshly resolved mainUrl,
    // because mainUrl can change between app launches/sessions.
    override val mainPage = mainPageOf(
        "tamil-yearly-dubbed-movies/"   to "New Tamil Dubbed Movies",
        "tamil-action-dubbed-movies/"   to "Tamil Dubbed Action Movies",
        "tamil-thriller-dubbed-movies/" to "Tamil Dubbed Thriller Movies",
        "tamil-comedy-dubbed-movies/"   to "Tamil Dubbed Comedy Movies",
        "tamil-family-dubbed-movies/"   to "Tamil Dubbed Family Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val activeMainUrl = resolveActiveDomain()
        val sectionUrl = "$activeMainUrl/${request.data.trimStart('/')}"
        val homePageLists = mutableListOf<HomePageList>()

        try {
            var targetUrl = sectionUrl
            // Dynamically resolves the latest yearly folder when "yearly" category is clicked
            if (sectionUrl.contains("yearly")) {
                val doc = scrapeSemaphore.withPermit { app.get(sectionUrl, headers = baseHeaders, timeout = 15).document }
                val firstAFolder = doc.selectFirst("div.container div.f a")
                val hrefAttr = firstAFolder?.attr("href") ?: ""
                
                if (hrefAttr.isNotBlank()) {
                    targetUrl = resolveUrl(activeMainUrl, hrefAttr)
                }
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
        val activeMainUrl = resolveActiveDomain()
        val collected  = mutableListOf<SearchResponse>()
        var currentPage = 1
        var maxPageNum = 15 // Safety ceiling 

        // Continues traversing pages until it strictly fulfills the 8 valid media threshold
        while (collected.size < 8 && currentPage <= maxPageNum) {
            val pageUrl = if (currentPage == 1) targetBaseUrl else "${targetBaseUrl.trimEnd('/')}/?get-page=$currentPage"
            try {
                val doc = scrapeSemaphore.withPermit { app.get(pageUrl, headers = baseHeaders, timeout = 15).document }
                
                if (currentPage == 1) {
                    val maxPageStr = doc.selectFirst("span#totalPages")?.text()?.trim()
                    if (!maxPageStr.isNullOrBlank()) {
                        maxPageNum = maxPageStr.toIntOrNull() ?: 1
                    }
                }

                val candidates = mutableListOf<Triple<String, String, String>>()
                
                // Unifies selectors for standard layout (.folder) and genre layout (.f)
                doc.select("div.container div.folder a, div.container div.f a").forEach { a ->
                    val href = a.attr("href")
                    if (!href.contains("/movie/", ignoreCase = true)) return@forEach
                    
                    val rawTitle = a.text().trim().ifBlank { a.attr("title").trim() }
                    if (rawTitle.isBlank()) return@forEach
                    
                    val low = rawTitle.lowercase()
                    val hrefLow = href.lowercase()
                    
                    // Outer Surface Filter
                    val tvKeywords = listOf(
                        "web series", "episode", "season", "sample", "tvmedia", "tvseries", "tvshow", 
                        "tv series", "tv show", "tv media", "epi ", "episodes"
                    )
                    
                    if (tvKeywords.any { low.contains(it) || hrefLow.contains(it) }) return@forEach
                    
                    val yearInTitle = Regex("\\b(19|20)\\d{2}\\b").find(rawTitle)?.value ?: sectionYear
                    val link = resolveUrl(activeMainUrl, href)
                    candidates.add(Triple(rawTitle, link, yearInTitle))
                }

                // Protects against duplicated items captured by unified selectors
                val distinctCandidates = candidates.distinctBy { it.second }

                if (distinctCandidates.isNotEmpty()) {
                    val deferreds = distinctCandidates.map { candidate ->
                        coroutineScope {
                            async {
                                val rawTitle = candidate.first
                                val link = candidate.second
                                val itemYear = candidate.third
                                
                                // ==========================================
                                // DEEP INSPECTION: TV SERIES ELIMINATION
                                // Retrieves candidate media page natively to inspect inner tags
                                val innerDoc = try {
                                    scrapeSemaphore.withPermit { 
                                        app.get(link, headers = baseHeaders, timeout = 10).document 
                                    }
                                } catch (e: Exception) {
                                    return@async null 
                                }
                                
                                var isHiddenTvSeries = false
                                val innerFolders = innerDoc.select("div.container div.folder a, div.container div.f a")
                                
                                for (innerA in innerFolders) {
                                    val innerText = innerA.text().lowercase()
                                    val innerHref = innerA.attr("href").lowercase()
                                    
                                    // Breaks and flags if disguised media contains structural series traits
                                    if (innerText.contains("season") || innerText.contains("episode") || 
                                        innerHref.contains("season") || innerHref.contains("episode")) {
                                        isHiddenTvSeries = true
                                        break
                                    }
                                }
                                
                                if (isHiddenTvSeries) return@async null
                                // ==========================================

                                // Title Normalization
                                var cleanTitle = rawTitle.replace("isaiDub.me", "", ignoreCase = true)
                                    .replace("Tamil Dubbed", "", ignoreCase = true)
                                    .replace(Regex("-"), " ")
                                    .replace(Regex("\\((19|20)\\d{2}\\)"), "")
                                    .trim()
                                
                                cleanTitle = cleanTitle.replace(Regex("(?i)\\b(hd|mp4|sample|dvdrip|bdrip|webrip|original|audio|brrip|camrip|predvd|hdcam|hdtv)\\b"), "").trim()
                                
                                if (cleanTitle.isBlank()) return@async null

                                val (tmdb, resolvedYear) = fetchTmdbTitle(cleanTitle, itemYear)
                                if (tmdb == null || tmdb.posterPath.isBlank() || tmdb.posterPath == "null") return@async null
                                
                                val poster = "https://image.tmdb.org/t/p/w500${tmdb.posterPath}"
                                val plot   = tmdb.overview

                                val t  = URLEncoder.encode(cleanTitle,   "UTF-8")
                                val y  = URLEncoder.encode(resolvedYear, "UTF-8")
                                val p  = URLEncoder.encode(poster,       "UTF-8")
                                val u  = URLEncoder.encode(link,         "UTF-8")
                                val s  = URLEncoder.encode(plot,         "UTF-8")
                                val st = URLEncoder.encode(rawTitle,     "UTF-8") 
                                val data = "$activeMainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=$u&s=$s&st=$st"

                                newMovieSearchResponse(cleanTitle, data) {
                                    this.posterUrl = poster
                                    this.year      = resolvedYear.toIntOrNull()
                                }
                            }
                        }
                    }
                    
                    val results = deferreds.awaitAll()
                    for (r in results) {
                        if (r != null && !collected.any { it.name == r.name }) {
                            collected.add(r)
                            if (collected.size >= 8) break // Immediately halts if quota reaches 8 mid-page
                        }
                    }
                }
            } catch (e: Exception) { break }
            currentPage++
        }
        
        return collected.take(8)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val activeMainUrl = resolveActiveDomain()
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
                val votes = item.optInt("vote_count", 0)
                val posterPath = item.optString("poster_path", "")
                val rDate = item.optString("release_date", "")
                val isUnreleased = rDate.isBlank()
                
                if (posterPath.isBlank() || posterPath == "null" || isUnreleased || votes < 100) continue
                
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
                val data = "$activeMainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=&s=$s&st=$st"
                
                searchResults.add(
                    newMovieSearchResponse(title, data) {
                        this.posterUrl = poster
                        this.year      = year.toIntOrNull()
                    }
                )
            }
        } catch (e: Exception) { }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val activeMainUrl = resolveActiveDomain()

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
            
            moviePageUrl = foundMovie?.link ?: ""
            synopsis = plotStr
            
            if (scrapedTitle.isBlank()) scrapedTitle = foundMovie?.title ?: title
        }

        if (synopsis.isBlank()) {
            val (tmdb, _) = fetchTmdbTitle(title, year)
            synopsis = tmdb?.overview ?: ""
        }

        val dataUrl = "$activeMainUrl/synthetic_meta" +
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
        val activeMainUrl = resolveActiveDomain()
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

        val seenResolutions = mutableSetOf<String>()

        for (linkItem in links) {
            val label = linkItem.first
            val finalUrl = linkItem.second

            if (!seenResolutions.add(label.lowercase())) continue

            val sourceName = "Isaidub ($label) \"$actualScrapedTitle\""
            val isM3u8 = finalUrl.contains(".m3u8")

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name   = sourceName,
                    url    = finalUrl,
                    referer = activeMainUrl,
                    quality = Qualities.Unknown.value,
                    type   = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
        return true
    }

    private suspend fun findMoviePage(title: String, year: String): ScrapedMovie? {
        if (year.isBlank()) return null
        val activeMainUrl = resolveActiveDomain()
        val yearUrl = "$activeMainUrl/tamil-$year-dubbed-movies/"
        
        var folderBestMovie: ScrapedMovie? = null
        var folderBestScore = -1
        val targetTokens = tokenize(title)

        var maxPage = 1
        try {
            val doc = scrapeSemaphore.withPermit { app.get(yearUrl, headers = baseHeaders, timeout = 10).document }
            val maxPageStr = doc.selectFirst("span#totalPages")?.text()?.trim()
            if (maxPageStr != null) {
                maxPage = maxPageStr.toIntOrNull() ?: 1
            }
        } catch (e: Exception) {}

        suspend fun scanPage(url: String) {
            try {
                val pageDoc = scrapeSemaphore.withPermit { app.get(url, headers = baseHeaders, timeout = 10).document }
                
                pageDoc.select("div.container div.folder a, div.container div.f a").forEach { a ->
                    val href = resolveUrl(activeMainUrl, a.attr("href"))
                    if (!href.contains("/movie/", ignoreCase = true)) return@forEach
                    
                    val movieTitle = a.text().trim().ifBlank { a.attr("title").trim() }
                    if (movieTitle.isBlank() || movieTitle.lowercase().contains("sample")) return@forEach

                    val siteTokens = tokenize(movieTitle)
                    var score = 0
                    for (token in targetTokens) {
                        if (siteTokens.contains(token)) score++
                    }
                    if (movieTitle.contains(year)) score += 2 

                    if (score > folderBestScore) {
                        folderBestScore = score
                        folderBestMovie = ScrapedMovie(movieTitle, href)
                    }
                }
            } catch (e: Exception) {}
        }

        scanPage(yearUrl)

        if (folderBestScore >= targetTokens.size && folderBestMovie != null) {
            return folderBestMovie
        }

        if (maxPage > 1) {
            val limit = if (maxPage > 15) 15 else maxPage
            coroutineScope {
                val deferreds = (2..limit).map { p ->
                    async { scanPage("$yearUrl?get-page=$p") }
                }
                deferreds.awaitAll()
            }
        }

        return folderBestMovie
    }

    private fun tokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        val matches = Regex("[a-z0-9]+").findAll(text.lowercase())
        for (match in matches) {
            tokens.add(match.value)
        }
        return tokens
    }

    private suspend fun resolveAllLinks(
        url: String, 
        depth: Int, 
        visited: Set<String> = emptySet()
    ): List<Pair<String, String>> {
        if (depth > 12) return emptyList()

        val cleanUrl = url.lowercase().trimEnd('/')
        if (visited.contains(cleanUrl)) return emptyList()
        val newVisited = visited.toMutableSet()
        newVisited.add(cleanUrl)

        var html = ""
        var responseUrl = url
        try {
            val response = scrapeSemaphore.withPermit { app.get(url, headers = baseHeaders, referer = mainUrl, timeout = 15) }
            if (!response.isSuccessful) return emptyList()
            html = response.text
            responseUrl = response.url
            
            if (isFinalUrl(responseUrl)) {
                val res = extractResolution("", responseUrl)
                return listOf(Pair(res, responseUrl))
            }
        } catch (e: Exception) {
            return emptyList()
        }

        val doc = Jsoup.parse(html)
        val results = mutableListOf<Pair<String, String>>()

        val metaRefresh = doc.selectFirst("meta[http-equiv=refresh]")
        if (metaRefresh != null) {
            val content = metaRefresh.attr("content")
            val urlMatch = Regex("url=(.+)", RegexOption.IGNORE_CASE).find(content)
            if (urlMatch != null) {
                val nextUrl = resolveUrl(responseUrl, urlMatch.groupValues[1].trim('\'', '"', ' '))
                if (!nextUrl.lowercase().contains("sample")) {
                    results.addAll(resolveAllLinks(nextUrl, depth + 1, newVisited))
                    if (results.isNotEmpty()) return results.distinctBy { it.second }
                }
            }
        }

        val linksToFollow = mutableSetOf<Pair<String, String>>()
        
        doc.select("a[href]").forEach { a ->
            val text = a.text().trim().ifBlank { a.attr("title").trim() }
            val href = a.attr("href").trim()
            if (href.isBlank() || href == "#" || href.startsWith("javascript:")) return@forEach
            
            val lowText = text.lowercase()
            val lowHref = href.lowercase()
            
            if (lowText.contains("sample") || lowHref.contains("sample")) return@forEach
            if (lowHref.contains("?get-page=") || lowHref.contains("/category/")) return@forEach
            
            val fullUrl = resolveUrl(responseUrl, href)
            val cleanFullUrl = fullUrl.lowercase().trimEnd('/')
            if (cleanFullUrl == responseUrl.lowercase().trimEnd('/')) return@forEach
            
            val isFormat = lowText.contains("original") || lowText.contains("hdrip") || 
                           lowText.contains("bdrip") || lowText.contains("720p") || 
                           lowText.contains("1080p") || lowText.contains("360p") || 
                           lowText.contains("480p") || lowText.contains("dvdsrc") || 
                           lowText.contains("mp4 hd") || lowText.contains(" hd ") || 
                           lowText.endsWith(" hd") || lowText.contains("hd)") || lowText.contains("hd (")
                           
            val isMovieFolder = lowHref.contains("/movie/")
            val isServer = lowText.contains("download server") || lowText == "download" || lowText.contains("download file")
            val isRedirector = lowHref.contains("download/page/") || lowHref.contains("download/view/") || 
                               lowHref.contains("download/file/") || lowHref.contains("dubpage") || 
                               lowHref.contains("dubmv") || lowHref.contains("uptodub") || 
                               lowHref.contains("dubshare") || lowHref.contains("download.php") || 
                               lowHref.contains("dl.php")
            
            if (isFormat || isMovieFolder || isServer || isRedirector || isFinalUrl(fullUrl)) {
                linksToFollow.add(Pair(text.ifBlank { "Link" }, fullUrl))
            }
        }

        val regex = Regex("""https?://[^"'\s<>]*(?:dubpage|dubmv|uptodub|dubshare|download\.php|\.mp4|\.mkv)[^"'\s<>]*""", RegexOption.IGNORE_CASE)
        regex.findAll(html).forEach { match ->
            val fullUrl = match.value
            if (!fullUrl.lowercase().contains("sample")) {
                linksToFollow.add(Pair("Direct", fullUrl))
            }
        }

        val serverOrFinalLinks = linksToFollow.filter { 
            it.first.lowercase().contains("download server") || 
            isFinalUrl(it.second) || 
            it.second.lowercase().contains("dubpage") || 
            it.second.lowercase().contains("dubmv") || 
            it.second.lowercase().contains("uptodub") || 
            it.second.lowercase().contains("dubshare") || 
            it.second.lowercase().contains("download.php")
        }
        
        val targets = if (serverOrFinalLinks.isNotEmpty()) serverOrFinalLinks else linksToFollow.toList()

        coroutineScope {
            val deferreds = targets.map { linkPair ->
                async {
                    val lbl = linkPair.first
                    val u = linkPair.second
                    
                    if (isFinalUrl(u)) {
                        listOf(Pair(extractResolution(lbl, u), u))
                    } else {
                        val innerLinks = resolveAllLinks(u, depth + 1, newVisited)
                        innerLinks.map { resolved ->
                            Pair(extractResolution(lbl, resolved.second).ifBlank { resolved.first }, resolved.second)
                        }
                    }
                }
            }
            deferreds.awaitAll().forEach { results.addAll(it) }
        }

        return results.distinctBy { it.second }
    }

    private fun extractResolution(text: String, url: String): String {
        val combined = (text + url).lowercase()
        val exactMatch = Regex("""\d{3,4}x\d{3,4}""").find(combined)
        if (exactMatch != null) return exactMatch.value

        return when {
            combined.contains("1080") || combined.contains("1920") -> "1080p"
            combined.contains("720")  || combined.contains("1280") -> "720p"
            combined.contains("640")  || combined.contains("360")  -> "360p"
            combined.contains("480")  || combined.contains("320")  -> "480p"
            else -> "HD"
        }
    }

    private fun isFinalUrl(url: String): Boolean {
        val low = url.lowercase()
        return low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".avi") ||
               low.endsWith(".mov") || low.endsWith(".webm") ||
               low.contains("download.php") || low.contains("dl.php")
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
