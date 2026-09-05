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

    override var mainUrl        = "https://isaidub.io"
    override var name           = "Isaidub"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang           = "ta"
    override val hasMainPage    = true

    // Only one core entry to intercept for our dynamic multi-category homepage
    override val mainPage = mainPageOf("home" to "Home")

    private val tmdbSemaphore   = Semaphore(15)
    private val scrapeSemaphore = Semaphore(12) 

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
    )

    // ==============================================================
    // DYNAMIC DOMAIN RESOLUTION
    // ==============================================================

    private val domainAnchorUrl = "https://isaidub.io"
    private var cachedMainUrl: String = mainUrl
    private var lastDomainCheckMs: Long = 0L
    private val domainCacheTtlMs = 20 * 60 * 1000L 

    @Synchronized
    private fun domainCacheIsFresh(): Boolean {
        return cachedMainUrl.isNotBlank() &&
            (System.currentTimeMillis() - lastDomainCheckMs) < domainCacheTtlMs
    }

    private suspend fun resolveActiveDomain(): String {
        if (domainCacheIsFresh()) return cachedMainUrl

        var candidateUrl = domainAnchorUrl
        var resolvedGood = false
        val visited = mutableSetOf<String>()

        try {
            for (hop in 0 until 6) {
                val normalized = candidateUrl.trimEnd('/')
                if (!visited.add(normalized)) break

                val response = scrapeSemaphore.withPermit {
                    app.get(candidateUrl, headers = baseHeaders, timeout = 10)
                }
                if (!response.isSuccessful) break

                val finalUrl = response.url.trimEnd('/')
                val doc = try { response.document } catch (e: Exception) { null }

                val looksLikeHomepage = doc?.select("div.container div.folder a, div.container div.f a")?.isNotEmpty() == true

                if (looksLikeHomepage) {
                    candidateUrl = finalUrl
                    resolvedGood = true
                    break
                }

                var nextTarget: String? = null
                if (doc != null) {
                    val metaContent = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                    if (!metaContent.isNullOrBlank()) {
                        nextTarget = Regex("url=(.+)", RegexOption.IGNORE_CASE)
                            .find(metaContent)?.groupValues?.get(1)?.trim('\'', '"', ' ')
                    }
                    if (nextTarget.isNullOrBlank()) {
                        nextTarget = doc.select("a[href^=http]").firstOrNull { a ->
                            a.attr("href").trimEnd('/') != finalUrl
                        }?.attr("href")
                    }
                    if (nextTarget.isNullOrBlank()) {
                        val scriptText = doc.select("script").joinToString("\n") { it.data() }
                        nextTarget = Regex("""location\.href\s*=\s*["']([^"']+)["']""")
                            .find(scriptText)?.groupValues?.get(1)
                    }
                }

                if (nextTarget.isNullOrBlank()) break
                candidateUrl = nextTarget.trim().trimEnd('/')
            }
        } catch (e: Exception) {}

        if (resolvedGood && candidateUrl.isNotBlank() && candidateUrl.startsWith("http")) {
            synchronized(this) {
                cachedMainUrl = candidateUrl
                lastDomainCheckMs = System.currentTimeMillis()
            }
            mainUrl = candidateUrl
        }
        return cachedMainUrl.ifBlank { mainUrl }
    }

    // ==============================================================
    // TMDB LOGIC (SEARCH ONLY)
    // ==============================================================

    private val masterTmdbKeys = listOf(
        "fb7bb23f03b6994dafc674c074d01761", "e55425032d3d0f371fc776f302e7c09b",
        "8301a21598f8b45668d5711a814f01f6", "8cf43ad9c085135b9479ad5cf6bbcbda",
        "da63548086e399ffc910fbc08526df05", "13e53ff644a8bd4ba37b3e1044ad24f3"
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
                if (resp.isSuccessful) return resp.text
            } catch (e: Exception) { continue }
        }
        return null
    }

    // ==============================================================
    // HOMEPAGE & PARSING
    // ==============================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val activeMainUrl = resolveActiveDomain()
        val lists = mutableListOf<HomePageList>()

        if (request.name != "Home") return newHomePageResponse(emptyList(), hasNext = false)

        try {
            val homeDoc = scrapeSemaphore.withPermit { 
                app.get(activeMainUrl, headers = baseHeaders, timeout = 15).document 
            }
            
            // 1. Latest Tamil Dubbed Movies (Dynamically extracts utilizing nth-child with fallback)
            var latestNode = homeDoc.selectFirst("body > div > div:nth-child(29) > a")
            if (latestNode == null || !latestNode.attr("href").contains(Regex("20\\d\\d"))) {
                latestNode = homeDoc.select("a").firstOrNull { it.text().contains(Regex("20\\d\\d")) }
            }
            
            if (latestNode != null) {
                val latestUrl = resolveUrl(activeMainUrl, latestNode.attr("href"))
                val latestItems = fetchHomepageSectionItems(latestUrl, 8)
                if (latestItems.isNotEmpty()) {
                    lists.add(HomePageList("Latest Tamil Dubbed Movies", latestItems, isHorizontalImages = false))
                }
            }

            // 2. Extracted Genres (Dynamically iterates genre folder elements)
            var genreFolderNode = homeDoc.selectFirst("body > div > div:nth-child(41) > a")
            if (genreFolderNode == null || !genreFolderNode.attr("href").contains("genre", ignoreCase = true)) {
                genreFolderNode = homeDoc.select("a").firstOrNull { it.text().contains("Genres", ignoreCase = true) }
            }

            if (genreFolderNode != null) {
                val genrePageUrl = resolveUrl(activeMainUrl, genreFolderNode.attr("href"))
                val genreDoc = scrapeSemaphore.withPermit { 
                    app.get(genrePageUrl, headers = baseHeaders, timeout = 15).document 
                }

                // Specifically looks at nth-child structures on genre page but safely grabs all genre links
                val genreLinks = genreDoc.select("div.f a, div.folder a, body > div > div > a")
                    .filter { it.attr("href").contains("-dubbed-movies") && !it.attr("href").contains("genres") }
                    .distinctBy { it.attr("href") }

                // Taking the top 6 genres to prevent massive network lag on initial homepage load
                val deferredGenres = genreLinks.take(6).map { a ->
                    coroutineScope {
                        async {
                            val gTitle = a.text().trim().replace("Tamil ", "").replace(" Dubbed Movies", " Movies")
                            val gUrl = resolveUrl(activeMainUrl, a.attr("href"))
                            val gItems = fetchHomepageSectionItems(gUrl, 8)
                            if (gItems.isNotEmpty()) HomePageList(gTitle, gItems, isHorizontalImages = false) else null
                        }
                    }
                }
                deferredGenres.awaitAll().filterNotNull().forEach { lists.add(it) }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(lists, hasNext = false)
    }

    /**
     * Gathers items for homepage rows. 
     * IMPORTANT: It visits the internal movie page to scrape the site's own image. No TMDB here.
     */
    private suspend fun fetchHomepageSectionItems(targetUrl: String, limit: Int): List<SearchResponse> {
        val activeMainUrl = resolveActiveDomain()
        val collected = mutableListOf<SearchResponse>()

        try {
            val doc = scrapeSemaphore.withPermit { app.get(targetUrl, headers = baseHeaders, timeout = 15).document }
            val candidates = mutableListOf<Pair<String, String>>()
            
            doc.select("div.container div.folder a, div.container div.f a").forEach { a ->
                val href = a.attr("href")
                if (!href.contains("/movie/", ignoreCase = true)) return@forEach
                
                val rawTitle = a.text().trim().ifBlank { a.attr("title").trim() }
                if (rawTitle.isBlank()) return@forEach
                
                // Exclude series masquerading as movies
                val low = rawTitle.lowercase()
                val tvKeywords = listOf("web series", "episode", "season", "sample", "tvmedia", "epi ")
                if (tvKeywords.any { low.contains(it) }) return@forEach
                
                candidates.add(Pair(rawTitle, resolveUrl(activeMainUrl, href)))
            }

            val distinctCandidates = candidates.distinctBy { it.second }.take(limit)

            val deferreds = distinctCandidates.map { candidate ->
                coroutineScope {
                    async {
                        val rawTitle = candidate.first
                        val link = candidate.second
                        
                        // Deep inspect the inner movie page to get the site's own image
                        val innerDoc = try {
                            scrapeSemaphore.withPermit { app.get(link, headers = baseHeaders, timeout = 10).document }
                        } catch (e: Exception) { return@async null }
                        
                        val innerLinks = innerDoc.select("div.folder a, div.f a").map { it.text().lowercase() }
                        if (innerLinks.any { it.contains("season") || it.contains("episode") }) return@async null
                        
                        // Utilizing requested site image selector: #movie-info > div.movie-info-container > picture
                        var posterUrl = innerDoc.selectFirst("#movie-info > div.movie-info-container > picture source")?.attr("srcset")
                            ?: innerDoc.selectFirst("#movie-info > div.movie-info-container > picture img")?.attr("src")
                            ?: innerDoc.selectFirst("img[alt*='Poster']")?.attr("src")
                            ?: ""
                            
                        if (posterUrl.isNotBlank()) posterUrl = resolveUrl(activeMainUrl, posterUrl)
                        
                        var cleanTitle = rawTitle.replace("isaiDub.me", "", ignoreCase = true)
                            .replace("Tamil Dubbed", "", ignoreCase = true)
                            .replace(Regex("-"), " ")
                            .replace(Regex("\\((19|20)\\d{2}\\)"), "")
                            .replace(Regex("(?i)\\b(hd|mp4|sample|dvdrip|bdrip|webrip|original|audio|brrip|camrip|predvd|hdcam|hdtv)\\b"), "")
                            .trim()
                            
                        val yearMatch = Regex("\\b(19|20)\\d{2}\\b").find(rawTitle)
                        val yearInTitle = yearMatch?.value?.toIntOrNull()

                        newMovieSearchResponse(cleanTitle, link) {
                            this.posterUrl = posterUrl.ifBlank { null }
                            this.year = yearInTitle
                        }
                    }
                }
            }

            deferreds.awaitAll().filterNotNull().forEach { collected.add(it) }
        } catch (e: Exception) {}

        return collected
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
                
                // Generates synthetic meta to hand over to load() 
                val t  = URLEncoder.encode(title,  "UTF-8")
                val y  = URLEncoder.encode(year,   "UTF-8")
                val p  = URLEncoder.encode(poster, "UTF-8")
                val s  = URLEncoder.encode(plot,   "UTF-8")
                val data = "$activeMainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=&s=$s&st="
                
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

        if (url.contains("synthetic_meta")) {
            // Flow: Originated from TMDB Search
            val uri      = android.net.Uri.parse(url)
            val title    = URLDecoder.decode(uri.getQueryParameter("t")   ?: "", "UTF-8")
            val year     = URLDecoder.decode(uri.getQueryParameter("y")   ?: "", "UTF-8")
            val poster   = URLDecoder.decode(uri.getQueryParameter("p")   ?: "", "UTF-8")
            val synopsis = URLDecoder.decode(uri.getQueryParameter("s")   ?: "", "UTF-8")

            if (title.isBlank()) return null
            
            // Find the actual site link
            var foundMovie: ScrapedMovie? = null
            coroutineScope { foundMovie = findMoviePage(title, year) }

            val moviePageUrl = foundMovie?.link ?: ""
            val scrapedTitle = foundMovie?.title ?: title

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
        } else {
            // Flow: Originated from Homepage direct link
            val doc = scrapeSemaphore.withPermit { app.get(url, headers = baseHeaders).document }
            
            val rawTitle = doc.selectFirst("h1, h2")?.text()?.trim() ?: "Isaidub Movie"
            val cleanTitle = rawTitle.replace("isaiDub.me", "", ignoreCase = true)
                .replace("Tamil Dubbed", "", ignoreCase = true)
                .replace(Regex("\\((19|20)\\d{2}\\)"), "").trim()
                
            val year = Regex("\\b(19|20)\\d{2}\\b").find(rawTitle)?.value?.toIntOrNull()
            
            // Re-extracting from site directly
            var posterUrl = doc.selectFirst("#movie-info > div.movie-info-container > picture source")?.attr("srcset")
                ?: doc.selectFirst("#movie-info > div.movie-info-container > picture img")?.attr("src")
                ?: doc.selectFirst("img[alt*='Poster']")?.attr("src")
                ?: ""
            if (posterUrl.isNotBlank()) posterUrl = resolveUrl(activeMainUrl, posterUrl)
            
            val plot = doc.selectFirst("div.synopsis, div.description, p.plot")?.text()?.trim()

            return newMovieLoadResponse(cleanTitle, url, TvType.Movie, url) {
                this.posterUrl = posterUrl.ifBlank { null }
                this.year = year
                this.plot = plot
            }
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
                val foundMovie = findMoviePage(title, year) ?: return false
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

        if (folderBestScore >= targetTokens.size && folderBestMovie != null) return folderBestMovie

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
