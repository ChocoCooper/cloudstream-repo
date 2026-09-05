package com.isaidub

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

data class ScrapedMovie(val title: String, val link: String)
data class SimpleTmdbMovie(val title: String, val posterPath: String, val overview: String, val releaseDate: String)

class IsaidubProvider : MainAPI() {
    override var mainUrl = "https://isaidub.io"
    override var name = "Isaidub"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "ta"
    override val hasMainPage = true

    override val mainPage = mainPageOf("home" to "Home")

    private val tmdbSemaphore = Semaphore(15)
    private val scrapeSemaphore = Semaphore(8)

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    // ==============================================================
    // DYNAMIC DOMAIN RESOLUTION
    // ==============================================================
    private val domainAnchorUrl = "https://isaidub.io"
    private var cachedMainUrl: String = mainUrl
    private var lastDomainCheckMs: Long = 0L
    private val domainCacheTtlMs = 20 * 60 * 1000L

    // Logging helper
    private fun log(message: String) {
        Log.d("IsaidubProvider", message)
    }

    @Synchronized
    private fun domainCacheIsFresh(): Boolean {
        return cachedMainUrl.isNotBlank() && (System.currentTimeMillis() - lastDomainCheckMs) < domainCacheTtlMs
    }

    private suspend fun resolveActiveDomain(): String {
        if (domainCacheIsFresh()) {
            log("Using cached domain: $cachedMainUrl")
            return cachedMainUrl
        }

        var currentTarget = domainAnchorUrl
        var resolvedGood = false
        val visited = mutableSetOf<String>()
        log("Resolving active domain starting from $domainAnchorUrl")

        try {
            for (hop in 0 until 6) {
                val normalized = currentTarget.trimEnd('/')
                if (!visited.add(normalized)) {
                    log("Already visited $normalized, breaking loop")
                    break
                }

                val response = scrapeSemaphore.withPermit {
                    app.get(currentTarget, headers = baseHeaders, timeout = 10)
                }
                if (!response.isSuccessful) {
                    log("HTTP ${response.code} for $currentTarget, breaking")
                    break
                }

                val finalUrl = response.url.trimEnd('/')
                val doc = try { response.document } catch (e: Exception) { null }

                val looksLikeHomepage = doc?.select("div.container div.folder a, div.container div.f a")?.isNotEmpty() == true
                log("Hop $hop: $currentTarget -> finalUrl=$finalUrl, looksLikeHomepage=$looksLikeHomepage")

                if (looksLikeHomepage) {
                    currentTarget = finalUrl
                    resolvedGood = true
                    break
                }

                var nextTarget: String? = null
                if (doc != null) {
                    val metaContent = doc.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                    if (!metaContent.isNullOrBlank()) {
                        nextTarget = Regex("""url=([^\s;'"]+)""", RegexOption.IGNORE_CASE)
                            .find(metaContent)?.groupValues?.get(1)?.trim('\'', '"', ' ')
                    }
                    if (nextTarget.isNullOrBlank()) {
                        val jsRedirect = Regex("""location(?:\.replace|\.href)?\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
                            .find(response.text)
                        if (jsRedirect != null) nextTarget = jsRedirect.groupValues[1]
                    }
                    if (nextTarget.isNullOrBlank()) {
                        nextTarget = doc.select("a[href^=http]").firstOrNull { it.attr("href").trimEnd('/') != finalUrl }?.attr("href")
                    }
                }

                if (nextTarget.isNullOrBlank()) {
                    log("No redirect found, breaking")
                    break
                }
                currentTarget = resolveUrl(finalUrl, nextTarget.trim().trimEnd('/'))
                log("Following redirect to $currentTarget")
            }
        } catch (e: Exception) {
            log("Error during domain resolution: ${e.message}")
        }

        if (resolvedGood && currentTarget.isNotBlank() && currentTarget.startsWith("http")) {
            synchronized(this) {
                cachedMainUrl = currentTarget
                lastDomainCheckMs = System.currentTimeMillis()
            }
            mainUrl = currentTarget
            log("Domain resolved to $currentTarget")
        } else {
            log("Domain resolution failed, keeping $cachedMainUrl")
        }
        return cachedMainUrl.ifBlank { mainUrl }
    }

    // ==============================================================
    // PARALLEL MULTI-KEY TMDB ROTATION POOL
    // ==============================================================
    private val masterTmdbKeys = listOf(
        "fb7bb23f03b6994dafc674c074d01761", "e55425032d3d0f371fc776f302e7c09b",
        "8301a21598f8b45668d5711a814f01f6", "8cf43ad9c085135b9479ad5cf6bbcbda",
        "da63548086e399ffc910fbc08526df05", "13e53ff644a8bd4ba37b3e1044ad24f3",
        "269890f657dddf4635473cf4cf456576", "a2f888b27315e62e471b2d587048f32e"
    ).distinct()

    private val allTmdbKeys = masterTmdbKeys.toMutableList()

    @Synchronized
    private fun getKeysShuffled(): List<String> {
        return allTmdbKeys.shuffled()
    }

    @Synchronized
    private fun markKeyDead(key: String) {
        allTmdbKeys.remove(key)
        log("TMDB key removed: ${key.take(4)}... (${allTmdbKeys.size} left)")
        if (allTmdbKeys.isEmpty()) allTmdbKeys.addAll(masterTmdbKeys)
    }

    private suspend fun fetchFromTmdb(urlBuilder: (String) -> String): String? {
        val keys = getKeysShuffled()
        var resultText: String? = null

        coroutineScope {
            val deferreds = keys.take(4).map { key ->
                async {
                    val url = urlBuilder(key)
                    try {
                        val resp = tmdbSemaphore.withPermit { app.get(url, timeout = 4) }
                        if (resp.code == 401 || resp.text.contains("Invalid API key", true)) {
                            markKeyDead(key)
                            return@async null
                        }
                        if (resp.isSuccessful) resp.text else null
                    } catch (e: Exception) {
                        log("TMDB request failed for key ${key.take(4)}...: ${e.message}")
                        null
                    }
                }
            }
            for (d in deferreds.awaitAll()) {
                if (!d.isNullOrBlank()) {
                    resultText = d
                    break
                }
            }
        }
        return resultText
    }

    private suspend fun fetchTmdbTitle(title: String, year: String = ""): Pair<SimpleTmdbMovie?, String> {
        val encodedQuery = URLEncoder.encode(title, "UTF-8")
        var jsonResponse = fetchFromTmdb { k ->
            val base = "https://api.tmdb.org/3/search/movie?api_key=$k&query=$encodedQuery&language=en"
            if (year.isNotBlank()) "$base&year=$year" else base
        }

        var resultsArray = jsonResponse?.let { JSONObject(it).optJSONArray("results") } ?: JSONArray()

        if (resultsArray.length() == 0 && year.isNotBlank()) {
            log("No TMDB result with year $year for '$title', retrying without year")
            jsonResponse = fetchFromTmdb { k ->
                "https://api.tmdb.org/3/search/movie?api_key=$k&query=$encodedQuery&language=en"
            }
            resultsArray = jsonResponse?.let { JSONObject(it).optJSONArray("results") } ?: JSONArray()
        }

        if (resultsArray.length() == 0) {
            log("No TMDB results for '$title'")
            return Pair(null, year)
        }

        val item = resultsArray.getJSONObject(0)
        val posterPath = item.optString("poster_path", "")
        if (posterPath.isBlank() || posterPath == "null") {
            log("No poster path for TMDB result, skipping")
            return Pair(null, year)
        }

        val rDate = item.optString("release_date", "")
        val resolvedYear = rDate.substringBefore("-").ifBlank { year }

        val movie = SimpleTmdbMovie(
            title = item.optString("title", title),
            posterPath = posterPath,
            overview = item.optString("overview", ""),
            releaseDate = rDate
        )
        log("TMDB match: ${movie.title} (${resolvedYear})")
        return Pair(movie, resolvedYear)
    }

    // ==============================================================
    // HOMEPAGE (REDUCED TO 2 FAST CATEGORIES FOR INSTANT LOAD)
    // ==============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val activeMainUrl = resolveActiveDomain()
        val lists = mutableListOf<HomePageList>()
        if (request.name != "Home") return newHomePageResponse(emptyList(), hasNext = false)

        try {
            log("Fetching homepage from $activeMainUrl")
            val homeDoc = scrapeSemaphore.withPermit { app.get(activeMainUrl, headers = baseHeaders, timeout = 10).document }

            // 1. Latest Movies
            val latestNode = homeDoc.selectFirst("body > div > div:nth-child(29) > a")
                ?: homeDoc.select("a").firstOrNull { it.text().contains(Regex("20\\d\\d")) }
            if (latestNode != null) {
                val latestUrl = resolveUrl(activeMainUrl, latestNode.attr("href"))
                log("Loading latest movies from $latestUrl")
                val latestItems = fetchHomepageSectionItems(latestUrl, 8)
                if (latestItems.isNotEmpty()) {
                    lists.add(HomePageList("Latest Tamil Dubbed Movies", latestItems, isHorizontalImages = false))
                }
            } else {
                log("Could not find latest movies anchor on homepage")
            }

            // 2. Action Movies
            val actionUrl = "$activeMainUrl/tamil-action-dubbed-movies/"
            log("Loading action movies from $actionUrl")
            lists.add(HomePageList("Tamil Action Dubbed Movies", fetchHomepageSectionItems(actionUrl, 8), isHorizontalImages = false))

        } catch (e: Exception) {
            log("Error loading homepage: ${e.message}")
            e.printStackTrace()
        }

        log("Homepage lists: ${lists.size}")
        return newHomePageResponse(lists, hasNext = false)
    }

    private suspend fun fetchHomepageSectionItems(targetUrl: String, limit: Int): List<SearchResponse> {
        val activeMainUrl = resolveActiveDomain()
        val collected = mutableListOf<SearchResponse>()
        log("fetchHomepageSectionItems: $targetUrl")

        try {
            val doc = scrapeSemaphore.withPermit { app.get(targetUrl, headers = baseHeaders, timeout = 10).document }
            val candidates = doc.select("div.container div.folder a, div.container div.f a")
                .filter { it.attr("href").contains("/movie/", true) && !it.text().contains("sample", true) }
                .distinctBy { it.attr("href") }
                .take(limit)

            log("Found ${candidates.size} movie candidates")

            val results = coroutineScope {
                candidates.map { a ->
                    async {
                        try {
                            val rawTitle = a.text().trim().ifBlank { a.attr("title").trim() }
                            val link = resolveUrl(activeMainUrl, a.attr("href"))
                            val cleanTitle = cleanTitle(rawTitle)
                            val extractedYear = Regex("""\b(19|20)\d{2}\b""").find(rawTitle)?.value ?: ""

                            log("Processing candidate: $rawTitle -> $cleanTitle ($extractedYear)")

                            val (tmdb, resolvedYear) = fetchTmdbTitle(cleanTitle, extractedYear)
                            if (tmdb == null) {
                                log("TMDB lookup failed for $cleanTitle")
                                return@async null
                            }

                            val posterUrl = "https://image.tmdb.org/t/p/w500${tmdb.posterPath}"

                            val t = URLEncoder.encode(tmdb.title, "UTF-8")
                            val y = URLEncoder.encode(resolvedYear, "UTF-8")
                            val p = URLEncoder.encode(posterUrl, "UTF-8")
                            val s = URLEncoder.encode(tmdb.overview, "UTF-8")
                            val u = URLEncoder.encode(link, "UTF-8")

                            val syntheticData = "$activeMainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=$u&s=$s"

                            newMovieSearchResponse(tmdb.title, syntheticData) {
                                this.posterUrl = posterUrl
                                this.year = resolvedYear.toIntOrNull()
                            }
                        } catch (e: Exception) {
                            log("Error in async candidate: ${e.message}")
                            null
                        }
                    }
                }.awaitAll()
            }

            results.filterNotNull().forEach { collected.add(it) }
            log("Collected ${collected.size} items from section")
        } catch (e: Exception) {
            log("Error fetching homepage section: ${e.message}")
        }

        return collected
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val activeMainUrl = resolveActiveDomain()
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        log("Search query: $query")
        val jsonResponse = fetchFromTmdb { k ->
            "https://api.tmdb.org/3/search/movie?api_key=$k&query=$encodedQuery&language=en"
        } ?: return emptyList()

        val searchResults = mutableListOf<SearchResponse>()
        try {
            val resultsArray = JSONObject(jsonResponse).optJSONArray("results") ?: JSONArray()
            for (i in 0 until resultsArray.length()) {
                val item = resultsArray.getJSONObject(i)
                val posterPath = item.optString("poster_path", "")
                val rDate = item.optString("release_date", "")

                if (posterPath.isBlank() || posterPath == "null" || rDate.isBlank() || item.optInt("vote_count", 0) < 50) continue

                val title = item.optString("title", "")
                val year = rDate.substringBefore("-")
                val poster = "https://image.tmdb.org/t/p/w500$posterPath"
                val plot = item.optString("overview", "")

                val t = URLEncoder.encode(title, "UTF-8")
                val y = URLEncoder.encode(year, "UTF-8")
                val p = URLEncoder.encode(poster, "UTF-8")
                val s = URLEncoder.encode(plot, "UTF-8")
                val data = "$activeMainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=&s=$s"

                searchResults.add(newMovieSearchResponse(title, data) {
                    this.posterUrl = poster
                    this.year = year.toIntOrNull()
                })
            }
        } catch (e: Exception) {
            log("Error parsing TMDB search results: ${e.message}")
        }
        log("Search returned ${searchResults.size} results")
        return searchResults
    }

    // ==============================================================
    // CORE CASCADING LOAD (MAPPING DIRECTORIES TO EPISODES)
    // ==============================================================
    override suspend fun load(url: String): LoadResponse? {
        val activeMainUrl = resolveActiveDomain()
        var title = "Unknown Title"
        var yearStr = ""
        var poster: String? = null
        var synopsis: String? = null
        var moviePageUrl = url

        log("load() called with url: $url")

        if (url.contains("synthetic_meta")) {
            val uri = Uri.parse(url)
            title = URLDecoder.decode(uri.getQueryParameter("t") ?: "", "UTF-8")
            yearStr = URLDecoder.decode(uri.getQueryParameter("y") ?: "", "UTF-8")
            poster = URLDecoder.decode(uri.getQueryParameter("p") ?: "", "UTF-8")
            synopsis = URLDecoder.decode(uri.getQueryParameter("s") ?: "", "UTF-8")

            val passedUrl = URLDecoder.decode(uri.getQueryParameter("url") ?: "", "UTF-8")
            log("Parsed synthetic meta: title=$title, year=$yearStr, passedUrl=$passedUrl")
            if (passedUrl.isBlank()) {
                log("No direct movie URL provided, searching site...")
                val foundMovie = findMoviePage(title, yearStr) ?: run {
                    log("findMoviePage returned null")
                    return null
                }
                moviePageUrl = foundMovie.link
                log("Found movie page: ${foundMovie.title} -> $moviePageUrl")
            } else {
                moviePageUrl = passedUrl
            }
        }

        val episodeList = mutableListOf<Episode>()
        try {
            log("Fetching movie page: $moviePageUrl")
            val doc = scrapeSemaphore.withPermit { app.get(moviePageUrl, headers = baseHeaders, timeout = 10).document }

            val formatFolders = doc.select("div.folder a, div.f a").filter {
                it.attr("href").contains("/movie/", true) && !it.text().contains("sample", true)
            }
            log("Format folders found: ${formatFolders.size}")

            if (formatFolders.isEmpty()) {
                log("No format folders; looking for download links directly...")
                doc.select("a[href*='/download/page/'], a[href*='/download/view/'], a[href*='/download/file/']").forEach { dlAnchor ->
                    val epTitle = dlAnchor.text().trim()
                    if (!epTitle.contains("sample", true)) {
                        episodeList.add(newEpisode(resolveUrl(activeMainUrl, dlAnchor.attr("href"))) {
                            this.name = epTitle
                            this.posterUrl = poster
                        })
                    }
                }
            } else {
                log("Processing ${formatFolders.size} format folders")
                coroutineScope {
                    formatFolders.map { formatAnchor ->
                        async {
                            val formatName = formatAnchor.text().trim()
                            val formatUrl = resolveUrl(activeMainUrl, formatAnchor.attr("href"))
                            log("Processing format folder: $formatName -> $formatUrl")

                            try {
                                val formatDoc = app.get(formatUrl, headers = baseHeaders, timeout = 10).document
                                val downloadLinks = formatDoc.select("a[href*='/download/page/'], a[href*='/download/view/'], a[href*='/download/file/']")

                                log("Download links in $formatName: ${downloadLinks.size}")
                                downloadLinks.forEach { dlAnchor ->
                                    val epTitle = dlAnchor.text().trim().ifBlank { formatName }
                                    if (!epTitle.contains("sample", true)) {
                                        val epMatch = Regex("""(?i)epi\s0(\d+)""").find(epTitle)
                                        val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull()

                                        episodeList.add(newEpisode(resolveUrl(activeMainUrl, dlAnchor.attr("href"))) {
                                            this.name = "[$formatName] $epTitle"
                                            this.episode = epNum
                                            this.posterUrl = poster
                                        })
                                    }
                                }
                            } catch (e: Exception) {
                                log("Error fetching format folder $formatUrl: ${e.message}")
                            }
                        }
                    }.awaitAll()
                }
            }
        } catch (e: Exception) {
            log("Error loading movie page: ${e.message}")
            e.printStackTrace()
        }

        log("Total episodes collected: ${episodeList.size}")
        if (episodeList.isEmpty()) {
            log("No episodes found, returning null")
            return null
        }

        log("Returning TvSeriesLoadResponse with ${episodeList.size} episodes")
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.year = yearStr.toIntOrNull()
            this.plot = synopsis
        }
    }

    // ==============================================================
    // THE ANTI-TARPIT RESOLVER
    // ==============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val activeMainUrl = resolveActiveDomain()
        log("loadLinks() called with data: $data")

        val finalMediaUrls = traceStreamHop(data, activeMainUrl)
        if (finalMediaUrls.isEmpty()) {
            log("No media URLs found after tracing")
            return false
        }

        log("Found ${finalMediaUrls.size} media URLs")
        for (finalUrl in finalMediaUrls) {
            val isM3u8 = finalUrl.contains(".m3u8", ignoreCase = true)
            val res = extractResolution(finalUrl)

            log("Adding extractor link: $finalUrl (${res})")
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "Isaidub ($res)",
                    url = finalUrl,
                    referer = activeMainUrl,
                    quality = Qualities.Unknown.value,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                )
            )
        }
        return true
    }

    private suspend fun traceStreamHop(startUrl: String, baseUrl: String): Set<String> {
        val finalMediaUrls = mutableSetOf<String>()
        val queue = mutableListOf(startUrl)
        val visited = mutableSetOf<String>()
        val regexPattern = Regex(
            """https?://[^"'\s<>]?(?:dubpage|dubmv|uptodub|dubshare|download\.php|\.mp4|\.mkv)[^"'\s<>]*""",
            RegexOption.IGNORE_CASE
        )

        log("traceStreamHop starting from $startUrl")
        var safetyDepth = 0
        while (queue.isNotEmpty() && safetyDepth < 10) {
            val currentUrl = queue.removeFirst()
            if (!visited.add(currentUrl)) continue
            log("Hop $safetyDepth: processing $currentUrl")

            if (currentUrl.contains("download.php") || currentUrl.contains("dl.php")) {
                try {
                    val fastPathMedia = Regex("""url=(https?://[^"'\s>]+)""").find(currentUrl)?.groupValues?.get(1)
                        ?: Regex("""url=([^&]+)""").find(currentUrl)?.groupValues?.get(1)?.let { URLDecoder.decode(it, "UTF-8") }

                    if (!fastPathMedia.isNullOrBlank()) {
                        log("Found fast path media: $fastPathMedia")
                        finalMediaUrls.add(fastPathMedia)
                        continue
                    }
                } catch (e: Exception) {
                    log("Error parsing fast path: ${e.message}")
                }
            }

            if (isFinalUrl(currentUrl)) {
                log("Found final media URL: $currentUrl")
                finalMediaUrls.add(currentUrl)
                continue
            }

            try {
                val response = app.get(currentUrl, headers = baseHeaders, timeout = 10)
                if (!response.isSuccessful) {
                    log("HTTP ${response.code} for $currentUrl")
                    continue
                }

                val html = response.text
                val nextHops = regexPattern.findAll(html).map { it.value }.filter { !it.contains("sample", true) }.toList()

                if (nextHops.isNotEmpty()) {
                    log("Found ${nextHops.size} next hops in HTML")
                    queue.addAll(nextHops)
                } else {
                    val metaRefresh = Regex(
                        """<meta[^>]+http-equiv=["']?refresh["']?[^>]+content=["']?[^"']*?url=([^"'\s>]+)["']?""",
                        RegexOption.IGNORE_CASE
                    ).find(html)
                    if (metaRefresh != null) {
                        val refreshUrl = resolveUrl(currentUrl, metaRefresh.groupValues[1])
                        log("Meta refresh found: $refreshUrl")
                        queue.add(refreshUrl)
                    } else {
                        log("No next hops or meta refresh found for $currentUrl")
                    }
                }
            } catch (e: Exception) {
                log("Error fetching $currentUrl: ${e.message}")
            }
            safetyDepth++
        }
        log("traceStreamHop finished with ${finalMediaUrls.size} final URLs")
        return finalMediaUrls
    }

    // ==============================================================
    // UTILITIES
    // ==============================================================
    private fun cleanTitle(raw: String): String {
        var clean = raw.replace(Regex("""(?i)isaiDub\.me|isaiDub\.io|isaiDub\..+?|Tamil Dubbed\.|\[.?\]|\(.*\)"""), "")
        clean = clean.replace(Regex("""(?i)\b(hd|mp4|sample|dvdrip|bdrip|webrip|original|audio|brrip|camrip|predvd|hdcam|hdtv)\b"""), "")
        return clean.replace("-", " ").trim()
    }

    private fun extractResolution(url: String): String {
        val low = url.lowercase()
        return when {
            low.contains("1080") -> "1080p"
            low.contains("720") -> "720p"
            low.contains("480") -> "480p"
            low.contains("360") -> "360p"
            else -> "HD"
        }
    }

    private fun isFinalUrl(url: String): Boolean {
        val low = url.lowercase()
        return low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".avi") || low.endsWith(".webm")
    }

    private fun resolveUrl(base: String, href: String): String {
        if (href.startsWith("http")) return href
        if (href.startsWith("//")) return "https:$href"
        if (href.startsWith("/")) {
            val u = Uri.parse(base)
            return "${u.scheme}://${u.host}$href"
        }
        val baseClean = base.trimEnd('/')
        val idx = baseClean.lastIndexOf('/')
        return if (idx > 7) "${baseClean.substring(0, idx)}/$href" else "$baseClean/$href"
    }

    private suspend fun findMoviePage(title: String, year: String): ScrapedMovie? {
        if (year.isBlank()) {
            log("findMoviePage: year is blank, cannot search")
            return null
        }
        val activeMainUrl = resolveActiveDomain()
        val yearUrl = "$activeMainUrl/tamil-$year-dubbed-movies/"
        log("findMoviePage: searching $yearUrl for '$title'")

        var bestMovie: ScrapedMovie? = null
        var bestScore = -1
        val targetTokens = title.lowercase().split(Regex("""[\s-]+""")).filter { it.length > 2 }

        try {
            val doc = scrapeSemaphore.withPermit { app.get(yearUrl, headers = baseHeaders, timeout = 10).document }
            val anchors = doc.select("div.container div.folder a, div.container div.f a")
            log("Found ${anchors.size} movie anchors on year page")
            anchors.forEach { a ->
                val href = resolveUrl(activeMainUrl, a.attr("href"))
                if (!href.contains("/movie/", true)) return@forEach

                val movieTitle = a.text().trim().ifBlank { a.attr("title").trim() }
                if (movieTitle.isBlank() || movieTitle.contains("sample", true)) return@forEach

                val siteTokens = movieTitle.lowercase().split(Regex("""[\s-]+"""))
                var score = targetTokens.count { siteTokens.contains(it) }
                if (movieTitle.contains(year)) score += 2

                log("Candidate: $movieTitle (score=$score)")
                if (score > bestScore) {
                    bestScore = score
                    bestMovie = ScrapedMovie(movieTitle, href)
                }
            }
        } catch (e: Exception) {
            log("Error in findMoviePage: ${e.message}")
        }

        log("Best match: ${bestMovie?.title} with score $bestScore")
        return bestMovie
    }
}
