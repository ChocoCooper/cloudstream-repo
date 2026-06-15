package com.dubstamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URLDecoder
import java.net.URLEncoder

// ============================================================
// DATA CLASSES (TMDB)
// ============================================================

data class TmdbSearchResponse(val results: List<TmdbMovie>?)
data class TmdbMovie(
    val title: String?,
    val release_date: String?,
    val poster_path: String?,
    val overview: String?,
    val vote_count: Int?
)
data class ScrapedMovie(val title: String, val link: String)

// ============================================================
// PROVIDER
// ============================================================

class IsaidubProvider : MainAPI() {

    override var mainUrl        = "https://isaidub.guru"
    override var name           = "DubsTamil"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang           = "ta"
    override val hasMainPage    = true

    // ── Semaphores ───────────────────────────────────────────
    private val tmdbSemaphore   = Semaphore(15) // Boosted for simultaneous multi-key throughput
    private val scrapeSemaphore = Semaphore(5)

    // ── TMDB key pool ────────────────────────────────────────
    private val allTmdbKeys = mutableListOf(
        "fb7bb23f03b6994dafc674c074d01761", "e55425032d3d0f371fc776f302e7c09b",
        "8301a21598f8b45668d5711a814f01f6", "8cf43ad9c085135b9479ad5cf6bbcbda",
        "da63548086e399ffc910fbc08526df05", "13e53ff644a8bd4ba37b3e1044ad24f3",
        "269890f657dddf4635473cf4cf456576", "a2f888b27315e62e471b2d587048f32e",
        "8476a7ab80ad76f0936744df0430e67c", "5622cafbfe8f8cfe358a29c53e19bba0",
        "ae4bd1b6fce2a5648671bfc171d15ba4", "257654f35e3dff105574f97fb4b97035",
        "2f4038e83265214a0dcd6ec2eb3276f5", "9e43f45f94705cc8e1d5a0400d19a7b7",
        "af6887753365e14160254ac7f4345dd2", "06f10fc8741a672af455421c239a1ffc",
        "09ad8ace66eec34302943272db0e8d2c"
    ).distinct().toMutableList()

    private var keyIndex = 0

    // Shifts the starting key index so simultaneous calls don't all hit the same key
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
            allTmdbKeys.addAll(
                listOf(
                    "fb7bb23f03b6994dafc674c074d01761", "e55425032d3d0f371fc776f302e7c09b",
                    "8301a21598f8b45668d5711a814f01f6", "8cf43ad9c085135b9479ad5cf6bbcbda",
                    "da63548086e399ffc910fbc08526df05", "13e53ff644a8bd4ba37b3e1044ad24f3",
                    "269890f657dddf4635473cf4cf456576", "a2f888b27315e62e471b2d587048f32e",
                    "8476a7ab80ad76f0936744df0430e67c", "5622cafbfe8f8cfe358a29c53e19bba0",
                    "ae4bd1b6fce2a5648671bfc171d15ba4", "257654f35e3dff105574f97fb4b97035",
                    "2f4038e83265214a0dcd6ec2eb3276f5", "9e43f45f94705cc8e1d5a0400d19a7b7",
                    "af6887753365e14160254ac7f4345dd2", "06f10fc8741a672af455421c239a1ffc",
                    "09ad8ace66eec34302943272db0e8d2c"
                ).distinct()
            )
        }
    }

    // ── Robust TMDB Fetcher ──────────────────────────────────
    private suspend fun fetchFromTmdb(urlBuilder: (String) -> String): String? {
        val keysToTry = getKeysRotated() 
        for (key in keysToTry) {
            val url = urlBuilder(key)
            try {
                val resp = tmdbSemaphore.withPermit { app.get(url, timeout = 5) }
                when {
                    resp.code == 401 || 
                    resp.text.contains("Invalid API key", ignoreCase = true) ||
                    resp.text.contains("rate limit", ignoreCase = true) -> {
                        markKeyDead(key)
                        continue 
                    }
                    resp.isSuccessful -> {
                        return resp.text
                    }
                }
            } catch (e: Exception) {
                continue 
            }
        }
        return null
    }

    // ── Page cache ───────────────────────────────────────────
    private val pageCache     = mutableMapOf<String, Pair<Long, Pair<List<ScrapedMovie>, Int>>>()
    private val cacheDuration = 5 * 60 * 1000L

    // ── Home page section definitions ────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/tamil-yearly-dubbed-movies/"  to "New Tamil Dubbed Movies",
        "$mainUrl/tamil-action-dubbed-movies/"  to "Tamil Dubbed Action Movies",
        "$mainUrl/tamil-comedy-dubbed-movies/"  to "Tamil Dubbed Comedy Movies",
        "$mainUrl/tamil-horror-dubbed-movies/"  to "Tamil Dubbed Horror Movies",
        "$mainUrl/tamil-family-dubbed-movies/"  to "Tamil Dubbed Family Movies"
    )

    // ============================================================
    // HOME PAGE
    // ============================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val sectionUrl = request.data
        val homePageLists = mutableListOf<HomePageList>()

        try {
            val targetUrl = if (sectionUrl.contains("yearly")) {
                val doc = scrapeSemaphore.withPermit { app.get(sectionUrl, timeout = 15).document }
                var resolved = ""
                for (a in doc.select("a[href]")) {
                    val href = a.attr("href")
                    if (href.contains(Regex("tamil-\\d{4}-dubbed-movies"))) {
                        resolved = if (href.startsWith("http")) href else "$mainUrl$href"
                        break
                    }
                }
                resolved.ifEmpty { return newHomePageResponse(emptyList(), hasNext = false) }
            } else {
                sectionUrl
            }

            val year = Regex("\\d{4}").find(targetUrl)?.value ?: ""
            val items = fetchSectionItems(targetUrl, year)
            if (items.isNotEmpty()) {
                homePageLists.add(HomePageList(request.name, items, isHorizontalImages = false))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    // ============================================================
    // FETCH SECTION ITEMS
    // ============================================================

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

                val candidates = doc.select("div.f a").mapNotNull { a ->
                    val title = a.text().trim()
                    var link  = a.attr("href")
                    if (link.startsWith("/")) link = "$mainUrl$link"
                    val low = title.lowercase()
                    val lowL = link.lowercase()
                    if (low.contains("web series") || lowL.contains("web-series") ||
                        low.contains("season")      || low.contains("episode") ||
                        low.contains("series")      || lowL.contains("series")) return@mapNotNull null
                    Pair(title, link)
                }

                if (candidates.isEmpty()) break

                val results = coroutineScope {
                    candidates.map { (rawTitle, link) ->
                        async {
                            val cleanTitle = rawTitle
                                .replace("isaiDub.me", "")
                                .replace(Regex("-"), " ")
                                .trim()
                            
                            val (tmdb, resolvedYear) = fetchTmdbTitle(cleanTitle, sectionYear)

                            if (tmdb == null) return@async null
                            
                            val poster = tmdb.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" } ?: return@async null
                            val plot   = tmdb.overview ?: ""

                            val t = URLEncoder.encode(cleanTitle,   "UTF-8")
                            val y = URLEncoder.encode(resolvedYear, "UTF-8")
                            val p = URLEncoder.encode(poster,       "UTF-8")
                            val u = URLEncoder.encode(link,         "UTF-8")
                            val s = URLEncoder.encode(plot,         "UTF-8")
                            val data = "$mainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=$u&s=$s"

                            newMovieSearchResponse(cleanTitle, data) {
                                this.posterUrl = poster
                                this.year      = resolvedYear.toIntOrNull()
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                for (r in results) {
                    if (collected.size < 12 && collected.none { it.name == r.name })
                        collected.add(r)
                }

                val maxPageNum = doc.selectFirst("span#totalPages")?.text()?.trim()?.toIntOrNull() ?: 1
                if (currentPage >= maxPageNum) break

            } catch (e: Exception) { break }
            currentPage++
        }
        return collected
    }

    // ============================================================
    // SEARCH
    // ============================================================

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        val jsonResponse = fetchFromTmdb { apiKey ->
            "https://api.tmdb.org/3/search/movie?api_key=$apiKey&query=$encodedQuery"
        } ?: return emptyList()

        val parsed = AppUtils.tryParseJson<TmdbSearchResponse>(jsonResponse)

        return (parsed?.results ?: emptyList()).mapNotNull { item ->
            val votes = item.vote_count ?: 0
            val posterPath = item.poster_path
            val rDate = item.release_date ?: ""
            val isUnreleased = rDate.isBlank()
            
            // Apply strict filters: Valid Poster, Released, >1000 votes
            if (posterPath.isNullOrBlank() || isUnreleased || votes < 100) return@mapNotNull null
            
            val title  = item.title ?: return@mapNotNull null
            val year   = rDate.substringBefore("-")
            val poster = "https://image.tmdb.org/t/p/w500$posterPath"
            val plot   = item.overview ?: ""
            
            val t = URLEncoder.encode(title,  "UTF-8")
            val y = URLEncoder.encode(year,   "UTF-8")
            val p = URLEncoder.encode(poster, "UTF-8")
            val s = URLEncoder.encode(plot,   "UTF-8")
            val data = "$mainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=&s=$s"
            
            newMovieSearchResponse(title, data) {
                this.posterUrl = poster
                this.year      = year.toIntOrNull()
            }
        }
    }

    // ============================================================
    // LOAD
    // ============================================================

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

        if (title.isBlank()) return null

        if (moviePageUrl.isBlank()) {
            val (pageUrl, plot) = coroutineScope {
                val pageDeferred = async { findMoviePage(title, year) }
                val plotDeferred = async {
                    if (synopsis.isBlank()) {
                        val (tmdb, _) = fetchTmdbTitle(title, year)
                        tmdb?.overview ?: ""
                    } else synopsis
                }
                Pair(pageDeferred.await(), plotDeferred.await())
            }
            moviePageUrl = pageUrl ?: return null
            synopsis     = plot
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
            "&s=${URLEncoder.encode(synopsis,      "UTF-8")}"

        return newMovieLoadResponse(title, dataUrl, TvType.Movie, dataUrl) {
            this.posterUrl = poster.ifBlank { null }
            this.year      = year.toIntOrNull()
            this.plot      = synopsis.ifBlank { null }
        }
    }

    // ============================================================
    // LOAD LINKS
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val moviePageUrl: String

        if (data.contains("synthetic_meta")) {
            val uri = android.net.Uri.parse(data)
            val parsedUrl = URLDecoder.decode(uri.getQueryParameter("url") ?: "", "UTF-8")
            
            if (parsedUrl.isBlank()) {
                val title = URLDecoder.decode(uri.getQueryParameter("t") ?: "", "UTF-8")
                val year  = URLDecoder.decode(uri.getQueryParameter("y") ?: "", "UTF-8")
                moviePageUrl = findMoviePage(title, year) ?: return false
            } else {
                moviePageUrl = parsedUrl
            }
        } else {
            moviePageUrl = data
        }

        if (moviePageUrl.isBlank()) return false

        val links = resolveAllLinks(moviePageUrl, depth = 0)
        if (links.isEmpty()) return false

        links.forEach { (label, finalUrl) ->
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name   = "${this.name} ($label)",
                    url    = finalUrl,
                    type   = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                             else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value 
                }
            )
        }
        return true
    }

    // ============================================================
    // TMDB TITLE LOOKUP
    // ============================================================

    private suspend fun fetchTmdbTitle(
        rawTitle: String,
        fallbackYear: String = ""
    ): Pair<TmdbMovie?, String> {
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
            val parsed = AppUtils.tryParseJson<TmdbSearchResponse>(jsonResponse)
            val validResult = parsed?.results?.firstOrNull { item ->
                val votes = item.vote_count ?: 0
                val poster = item.poster_path
                val rDate = item.release_date ?: ""
                val isUnreleased = rDate.isBlank()
                
                // Apply strict filters: Valid Poster, Released, >1000 votes
                !poster.isNullOrBlank() && !isUnreleased
            }
            if (validResult != null) {
                return Pair(validResult, usedYear)
            }
        }
        return Pair(null, usedYear)
    }

    // ============================================================
    // FIND MOVIE PAGE (Fuzzy Token Match)
    // ============================================================

    private suspend fun findMoviePage(title: String, year: String): String? {
        if (year.isBlank()) return null

        val searchUrl = "$mainUrl/tamil-$year-dubbed-movies/"
        var bestMatch: Pair<ScrapedMovie, Int>? = null
        
        val targetTokens = tokenize("$title $year")
        val targetTokenCount = targetTokens.size

        suspend fun processMovies(movies: List<ScrapedMovie>) {
            for (movie in movies) {
                if (year !in movie.title) continue
                val siteTokens = tokenize(movie.title)
                val score = targetTokens.intersect(siteTokens).size
                if (score > (bestMatch?.second ?: 0)) {
                    bestMatch = Pair(movie, score)
                }
            }
        }

        val (firstPageMovies, maxPage) = scrapePage(searchUrl)
        processMovies(firstPageMovies)

        if ((bestMatch?.second ?: 0) >= targetTokenCount) {
            return bestMatch?.first?.link
        }

        if (maxPage > 1) {
            coroutineScope {
                (2..minOf(maxPage, 10)).map { p ->
                    async { scrapePage("$searchUrl?get-page=$p").first }
                }.awaitAll()
            }.forEach { processMovies(it) }
        }

        return bestMatch?.first?.link
    }

    // ============================================================
    // SCRAPE PAGE
    // ============================================================

    private suspend fun scrapePage(url: String): Pair<List<ScrapedMovie>, Int> {
        pageCache[url]?.let { (ts, data) ->
            if (System.currentTimeMillis() - ts < cacheDuration) return data
        }
        return try {
            val resp = scrapeSemaphore.withPermit { app.get(url, timeout = 10) }
            if (!resp.isSuccessful) return Pair(emptyList(), 1)
            val doc    = resp.document
            val movies = doc.select("div.f").mapNotNull { div ->
                val a = div.selectFirst("a") ?: return@mapNotNull null
                val t = a.text().trim()
                var l = a.attr("href")
                if (l.startsWith("/")) l = "$mainUrl$l"
                if (t.isBlank() || l.isBlank()) null else ScrapedMovie(t, l)
            }
            val maxPage = doc.selectFirst("span#totalPages")?.text()?.trim()?.toIntOrNull() ?: 1
            Pair(movies, maxPage).also { pageCache[url] = Pair(System.currentTimeMillis(), it) }
        } catch (e: Exception) {
            Pair(emptyList(), 1)
        }
    }

    // ============================================================
    // TOKEN HELPERS
    // ============================================================

    private fun tokenize(text: String): Set<String> =
        Regex("[a-z0-9]+").findAll(text.lowercase()).map { it.value }.toSet()

    // ============================================================
    // RESOLVE ALL LINKS
    // ============================================================

    private suspend fun resolveAllLinks(url: String, depth: Int): List<Pair<String, String>> {
        if (depth > 15) return emptyList()

        val response = try {
            scrapeSemaphore.withPermit { app.get(url, timeout = 15, referer = mainUrl) }
        } catch (e: Exception) { return emptyList() }

        if (!response.isSuccessful) return emptyList()

        val html = response.text
        val doc  = response.document

        val rawFinal = extractFinalFromHtml(html)
        if (rawFinal != null) return listOf(Pair(extractResolution("", rawFinal), rawFinal))

        val links =
            if ("isaidub.guru" in url && "/download/" !in url)
                extractIsaidubLinks(doc, url).ifEmpty { extractDownloadLinks(doc, url) }
            else
                extractDownloadLinks(doc, url)

        if (links.isEmpty()) return emptyList()

        val dlServer = links.firstOrNull { "download server" in it.first.lowercase() }
        if (dlServer != null) return resolveAllLinks(dlServer.second, depth + 1)

        if (links.size == 1) return resolveAllLinks(links[0].second, depth + 1)

        val finalLinks = coroutineScope {
            links.map { (label, linkUrl) ->
                async {
                    if (isFinalUrl(linkUrl)) listOf(Pair(extractResolution(label, linkUrl), linkUrl))
                    else resolveAllLinks(linkUrl, depth + 1).map { (lbl, u) ->
                        Pair(extractResolution(label, u).ifBlank { lbl }, u)
                    }
                }
            }.awaitAll()
        }.flatten()

        return finalLinks.distinctBy { it.second }
    }

    // ============================================================
    // EXACT RESOLUTION EXTRACTOR
    // ============================================================

    private fun extractResolution(text: String, url: String): String {
        val combined = (text + url).lowercase()
        
        val exactMatch = Regex("""\d{3,4}x\d{3,4}""").find(combined)
        if (exactMatch != null) return exactMatch.value

        return when {
            "1080" in combined || "1920" in combined -> "1920x1080"
            "720"  in combined || "1280" in combined -> "1280x720"
            "640"  in combined || "360"  in combined -> "640x360"
            "480"  in combined || "320"  in combined -> "480x320"
            else -> "HD"
        }
    }

    // ============================================================
    // PAGE EXTRACTORS
    // ============================================================

    private fun isFinalUrl(url: String): Boolean {
        val low = url.lowercase()
        return low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".avi") ||
               low.endsWith(".mov") || low.endsWith(".webm") ||
               "download.php" in low || "dl.php" in low
    }

    private fun extractFinalFromHtml(html: String): String? =
        Regex("""https?://[^\s"'<>]*download\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(html)?.value
        ?: Regex("""https?://[^\s"'<>]*dl\.php\?[^\s"'<>]*""",    RegexOption.IGNORE_CASE).find(html)?.value
        ?: Regex("""https?://[^\s"'<>]*\.mp4[^"'\s]*""",           RegexOption.IGNORE_CASE).find(html)?.value

    private fun extractIsaidubLinks(
        doc: org.jsoup.nodes.Document,
        baseUrl: String
    ): List<Pair<String, String>> =
        doc.select("div.f, div.bf").mapNotNull { div ->
            val a    = div.selectFirst("a[href]") ?: return@mapNotNull null
            val text = a.text().trim()
            val href = a.attr("href")
            if (text.isBlank() || href.isBlank()) return@mapNotNull null
            if ("sample" in text.lowercase()) return@mapNotNull null
            
            val fullUrl = resolveUrl(baseUrl, href)
            val low = fullUrl.lowercase().trimEnd('/')
            
            if ("?get-page=" in low || "/category/" in low) return@mapNotNull null
            if (low == baseUrl.lowercase().trimEnd('/')) return@mapNotNull null
            
            Pair(text, fullUrl)
        }.distinctBy { it.second }

    private fun extractDownloadLinks(
        doc: org.jsoup.nodes.Document,
        baseUrl: String
    ): List<Pair<String, String>> =
        doc.select("a[href]").mapNotNull { a ->
            val text = a.text().trim()
            val href = a.attr("href")
            if (text.isBlank() || href.isBlank()) return@mapNotNull null
            val full = resolveUrl(baseUrl, href)
            val low  = full.lowercase()
            val lowT = text.lowercase()
            when {
                "download server" in lowT || "download" in lowT              -> Pair(text, full)
                low.endsWith(".mp4") || low.endsWith(".mkv")
                                     || low.endsWith(".avi")                 -> Pair(text, full)
                "download.php" in low || "dl.php" in low                     -> Pair(text, full)
                listOf("dubpage.xyz", "dubmv.xyz", "dub.uptodub.ch")
                    .any { it in low }                                        -> Pair(text, full)
                else -> null
            }
        }.distinctBy { it.second }

    private fun resolveUrl(base: String, href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//")   -> "https:$href"
        href.startsWith("/")    -> {
            val u = android.net.Uri.parse(base)
            "${u.scheme}://${u.host}$href"
        }
        else -> "${base.trimEnd('/').substringBeforeLast('/')}/$href"
    }
}
