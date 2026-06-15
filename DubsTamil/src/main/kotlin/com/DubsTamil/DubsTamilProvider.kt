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
import kotlin.random.Random

// ============================================================
// DATA CLASSES
// ============================================================

data class OmdbSearchResponse(val Search: List<OmdbSearchResult>?, val Response: String?)
data class OmdbSearchResult(val Title: String?, val Year: String?, val Poster: String?)
data class OmdbTitleResponse(val Title: String?, val Year: String?, val Poster: String?, val Plot: String?, val Response: String?)
data class ScrapedMovie(val title: String, val link: String)

// ============================================================
// ISAIDUB PROVIDER
// ============================================================

class IsaidubProvider : MainAPI() {

    override var mainUrl         = "https://isaidub.guru"
    override var name            = "DubsTamil"
    override val supportedTypes  = setOf(TvType.Movie)
    override var lang            = "ta"
    override val hasMainPage     = true

    // ── Concurrency ──────────────────────────────────────────
    internal val omdbSemaphore   = Semaphore(5)
    internal val scrapeSemaphore = Semaphore(5)

    // ── OMDB key pool ────────────────────────────────────────
    private val baseOmdbKeys = listOf("eb0c0475", "4b447405", "7776cbde", "ff28f90b", "6c3a2d45")
    private val activeOmdbKeys = baseOmdbKeys.toMutableList()

    internal fun getRandomApiKey(): String {
        if (activeOmdbKeys.isEmpty()) activeOmdbKeys.addAll(baseOmdbKeys)
        return activeOmdbKeys[Random.nextInt(activeOmdbKeys.size)]
    }
    internal fun removeDeadKey(key: String) { activeOmdbKeys.remove(key) }

    // ── Page cache ───────────────────────────────────────────
    private val pageCache      = mutableMapOf<String, Pair<Long, Pair<List<ScrapedMovie>, Int>>>()
    private val cacheDuration  = 5 * 60 * 1000L

    // ── Home page sections ───────────────────────────────────
    override val mainPage = mainPageOf(
        "$mainUrl/tamil-yearly-dubbed-movies/" to "New Tamil Dubbed Movies",
        "$mainUrl/tamil-action-dubbed-movies/" to "Tamil Dubbed Action Movies",
        "$mainUrl/tamil-comedy-dubbed-movies/" to "Tamil Dubbed Comedy Movies",
        "$mainUrl/tamil-horror-dubbed-movies/" to "Tamil Dubbed Horror Movies",
        "$mainUrl/tamil-family-dubbed-movies/" to "Tamil Dubbed Family Movies"
    )

    // ============================================================
    // HOME PAGE
    // ============================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageLists = mutableListOf<HomePageList>()

        try {
            val yearlyDoc = scrapeSemaphore.withPermit {
                app.get("$mainUrl/tamil-yearly-dubbed-movies/", timeout = 15).document
            }
            var latestYearUrl = ""
            var latestYear    = ""

            for (a in yearlyDoc.select("a[href]")) {
                val href = a.attr("href")
                if (href.contains(Regex("tamil-\\d{4}-dubbed-movies"))) {
                    latestYearUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                    latestYear    = Regex("\\d{4}").find(href)?.value ?: ""
                    break
                }
            }

            coroutineScope {
                val newMoviesDeferred = async {
                    if (latestYearUrl.isNotEmpty()) fetchSectionItems(latestYearUrl, latestYear)
                    else emptyList()
                }
                val actionDeferred  = async { fetchSectionItems("$mainUrl/tamil-action-dubbed-movies/") }
                val comedyDeferred  = async { fetchSectionItems("$mainUrl/tamil-comedy-dubbed-movies/") }
                val horrorDeferred  = async { fetchSectionItems("$mainUrl/tamil-horror-dubbed-movies/") }
                val familyDeferred  = async { fetchSectionItems("$mainUrl/tamil-family-dubbed-movies/") }

                val newMoviesList = newMoviesDeferred.await()
                if (newMoviesList.isNotEmpty())
                    homePageLists.add(HomePageList("New Tamil Dubbed Movies", newMoviesList, isHorizontalImages = false))

                listOf(
                    "Tamil Dubbed Action Movies" to actionDeferred.await(),
                    "Tamil Dubbed Comedy Movies" to comedyDeferred.await(),
                    "Tamil Dubbed Horror Movies" to horrorDeferred.await(),
                    "Tamil Dubbed Family Movies" to familyDeferred.await()
                ).forEach { (title, items) ->
                    if (items.isNotEmpty())
                        homePageLists.add(HomePageList(title, items, isHorizontalImages = false))
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    // ============================================================
    // SEARCH  — OMDB → synthetic URL
    // ============================================================

    override suspend fun search(query: String): List<SearchResponse> {
        var omdbJson: String? = null
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        try {
            val apiKey = getRandomApiKey()
            val url    = "https://www.omdbapi.com/?apikey=$apiKey&s=$encodedQuery&type=movie"
            val response = omdbSemaphore.withPermit { app.get(url, timeout = 3) }
            when {
                response.code == 401
                || response.text.contains("Limit reached",   ignoreCase = true)
                || response.text.contains("Invalid API key", ignoreCase = true) -> removeDeadKey(apiKey)
                response.isSuccessful
                && response.text.contains("\"Response\":\"True\"") -> omdbJson = response.text
            }
        } catch (e: Exception) { }

        if (omdbJson == null) return emptyList()
        val parsed = AppUtils.tryParseJson<OmdbSearchResponse>(omdbJson)

        return (parsed?.Search?.filter { !it.Poster.isNullOrBlank() && it.Poster != "N/A" } ?: emptyList())
            .mapNotNull { item ->
                val title  = item.Title ?: return@mapNotNull null
                val year   = item.Year?.replace(Regex("[^0-9]"), "") ?: ""
                val poster = item.Poster ?: ""
                val t = URLEncoder.encode(title,  "UTF-8")
                val y = URLEncoder.encode(year,   "UTF-8")
                val p = URLEncoder.encode(poster, "UTF-8")
                val targetData = "$mainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=&s="
                newMovieSearchResponse(title, targetData) {
                    this.posterUrl = poster
                    this.year      = year.toIntOrNull()
                }
            }
    }

    // ============================================================
    // LOAD
    // ============================================================

    override suspend fun load(url: String): LoadResponse? {
        val uri     = android.net.Uri.parse(url)
        val title   = URLDecoder.decode(uri.getQueryParameter("t")   ?: "", "UTF-8")
        val year    = URLDecoder.decode(uri.getQueryParameter("y")   ?: "", "UTF-8")
        val poster  = URLDecoder.decode(uri.getQueryParameter("p")   ?: "", "UTF-8")
        val synopsis = URLDecoder.decode(uri.getQueryParameter("s")  ?: "", "UTF-8")
        // "url" param may already be filled (from homepage) or empty (from search)
        var moviePageUrl = URLDecoder.decode(uri.getQueryParameter("url") ?: "", "UTF-8")

        if (title.isBlank()) return null

        // If not pre-filled, find it now (search flow)
        if (moviePageUrl.isBlank()) {
            moviePageUrl = findMoviePage(title, year) ?: return null
        }

        val dataUrl = "$mainUrl/synthetic_meta" +
            "?t=${URLEncoder.encode(title,        "UTF-8")}" +
            "&y=${URLEncoder.encode(year,         "UTF-8")}" +
            "&p=${URLEncoder.encode(poster,       "UTF-8")}" +
            "&url=${URLEncoder.encode(moviePageUrl, "UTF-8")}" +
            "&s=${URLEncoder.encode(synopsis,     "UTF-8")}"

        return newMovieLoadResponse(title, dataUrl, TvType.Movie, dataUrl) {
            this.posterUrl   = poster.ifBlank { null }
            this.year        = year.toIntOrNull()
            this.plot        = synopsis.ifBlank { null }
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
        val uri          = android.net.Uri.parse(data)
        val moviePageUrl = URLDecoder.decode(uri.getQueryParameter("url") ?: "", "UTF-8")
        if (moviePageUrl.isBlank()) return false

        val finalUrl = resolveFinalLink(moviePageUrl, depth = 0) ?: return false

        callback.invoke(
            newExtractorLink(
                source = name,
                name   = name,
                url    = finalUrl,
                type   = if (finalUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    // ============================================================
    // FETCH SECTION ITEMS  — from DubsTamilHomePage.kt
    // ============================================================

    private suspend fun fetchSectionItems(
        targetBaseUrl: String,
        sectionYear: String = ""
    ): List<SearchResponse> {
        val listItems        = mutableListOf<SearchResponse>()
        var currentPage      = 1
        val maxPagesToScrape = 3

        while (listItems.size < 6 && currentPage <= maxPagesToScrape) {
            val targetUrl = if (currentPage == 1) targetBaseUrl else "$targetBaseUrl?get-page=$currentPage"
            try {
                val doc = scrapeSemaphore.withPermit { app.get(targetUrl, timeout = 15).document }
                val validMovieLinks = mutableListOf<Pair<String, String>>()

                for (a in doc.select("div.f a")) {
                    val linkTitle = a.text().trim()
                    var link      = a.attr("href")
                    if (link.startsWith("/")) link = "$mainUrl$link"
                    val lowerTitle = linkTitle.lowercase()
                    val lowerLink  = link.lowercase()
                    if (lowerTitle.contains("web series") || lowerLink.contains("web-series") ||
                        lowerTitle.contains("season")     || lowerTitle.contains("episode")) continue
                    validMovieLinks.add(Pair(linkTitle, link))
                }

                if (validMovieLinks.isEmpty()) break

                val responses = coroutineScope {
                    validMovieLinks.map { (linkTitle, link) ->
                        async {
                            val cleanTitle   = linkTitle.replace("isaiDub.me", "").replace("-", " ").trim()
                            val (omdbMatch, resolvedYear) = fetchOmdbMetadata(cleanTitle, sectionYear)
                            val omdbPoster   = omdbMatch?.Poster?.takeIf { it != "N/A" } ?: return@async null
                            val plotSynopsis = omdbMatch.Plot?.takeIf { it != "N/A" } ?: "No synopsis available."

                            val t = URLEncoder.encode(cleanTitle,   "UTF-8")
                            val y = URLEncoder.encode(resolvedYear, "UTF-8")
                            val p = URLEncoder.encode(omdbPoster,   "UTF-8")
                            val u = URLEncoder.encode(link,         "UTF-8")
                            val s = URLEncoder.encode(plotSynopsis, "UTF-8")
                            val targetData = "$mainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=$u&s=$s"

                            newMovieSearchResponse(cleanTitle, targetData) {
                                this.posterUrl = omdbPoster
                                this.year      = resolvedYear.toIntOrNull()
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                for (res in responses) {
                    if (listItems.size < 6 && listItems.none { it.name == res.name })
                        listItems.add(res)
                }

                val maxPageStr = doc.selectFirst("span#totalPages")?.text()?.trim()?.toIntOrNull()
                if (maxPageStr != null && currentPage >= maxPageStr) break

            } catch (e: Exception) { break }
            currentPage++
        }
        return listItems
    }

    // ============================================================
    // FETCH OMDB METADATA  — title lookup by name+year
    // ============================================================

    private suspend fun fetchOmdbMetadata(
        rawTitle: String,
        fallbackYear: String = ""
    ): Pair<OmdbTitleResponse?, String> {
        val cleanName      = rawTitle.replace("isaiDub.me", "").replace("-", " ").trim()
        val yearRegex      = Regex("\\b(19|20)\\d{2}\\b").find(cleanName)
        val extractedYear  = yearRegex?.value ?: fallbackYear
        val finalSearchTitle = if (yearRegex != null)
            cleanName.replace(yearRegex.value, "").trim() else cleanName

        return try {
            val encodedQuery = URLEncoder.encode(finalSearchTitle, "UTF-8")
            val apiKey       = getRandomApiKey()
            val url          = if (extractedYear.isNotBlank())
                "https://www.omdbapi.com/?apikey=$apiKey&t=$encodedQuery&y=$extractedYear"
            else
                "https://www.omdbapi.com/?apikey=$apiKey&t=$encodedQuery"

            val response = omdbSemaphore.withPermit { app.get(url, timeout = 3) }
            when {
                response.code == 401
                || response.text.contains("Limit reached",   ignoreCase = true)
                || response.text.contains("Invalid API key", ignoreCase = true) -> {
                    removeDeadKey(apiKey)
                    Pair(null, extractedYear)
                }
                response.isSuccessful
                && response.text.contains("\"Response\":\"True\"") -> {
                    val parsed = AppUtils.tryParseJson<OmdbTitleResponse>(response.text)
                    if (parsed?.Poster != null && parsed.Poster != "N/A")
                        Pair(parsed, extractedYear)
                    else
                        Pair(null, extractedYear)
                }
                else -> Pair(null, extractedYear)
            }
        } catch (e: Exception) {
            Pair(null, extractedYear)
        }
    }

    // ============================================================
    // FIND MOVIE PAGE  — year-directory + token match
    // ============================================================

    private suspend fun findMoviePage(title: String, year: String): String? {
        val targets = mutableListOf<String>()
        year.toIntOrNull()?.let { targets.add("$mainUrl/tamil-$it-dubbed-movies/") }
        title.trim().firstOrNull()?.lowercaseChar()?.let {
            if (it.isLetter()) targets.add("$mainUrl/tamil-atoz-dubbed-movies/$it/")
            else if (it.isDigit()) targets.add("$mainUrl/tamil-atoz-dubbed-movies/0-9/")
        }

        for (baseUrl in targets.distinct()) {
            val (movies, maxPage) = scrapePage(baseUrl)
            tokenMatch(movies, title, year).firstOrNull()?.let { return it.link }

            if (maxPage > 1) {
                coroutineScope {
                    (2..minOf(maxPage, 6)).map { p ->
                        async { scrapePage("$baseUrl?get-page=$p").first }
                    }.awaitAll()
                }.flatMap { tokenMatch(it, title, year) }
                 .firstOrNull()?.let { return it.link }
            }
        }
        return null
    }

    // ============================================================
    // SCRAPE PAGE
    // ============================================================

    private suspend fun scrapePage(url: String): Pair<List<ScrapedMovie>, Int> {
        pageCache[url]?.let { (ts, data) ->
            if (System.currentTimeMillis() - ts < cacheDuration) return data
        }
        return try {
            val response = scrapeSemaphore.withPermit { app.get(url, timeout = 10) }
            if (!response.isSuccessful) return Pair(emptyList(), 1)
            val doc     = response.document
            val movies  = doc.select("div.f").mapNotNull { div ->
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
    // TOKEN MATCH  — mirrors Python get_tokens() + scoring
    // ============================================================

    private fun tokenMatch(
        movies: List<ScrapedMovie>,
        queryTitle: String,
        queryYear: String
    ): List<ScrapedMovie> {
        val targetTokens = Regex("[a-z0-9]+")
            .findAll("$queryTitle $queryYear".lowercase())
            .map { it.value }.toSet()

        return movies
            .filter { queryYear.isBlank() || queryYear in it.title }
            .mapNotNull { movie ->
                val siteTokens = Regex("[a-z0-9]+")
                    .findAll(movie.title.lowercase()).map { it.value }.toSet()
                val score = targetTokens.intersect(siteTokens).size
                if (score > 0) Pair(movie, score) else null
            }
            .sortedByDescending { it.second }
            .map { it.first }
    }

    // ============================================================
    // RECURSIVE RESOLVER  — mirrors Python interactive_browser()
    // ============================================================

    private suspend fun resolveFinalLink(url: String, depth: Int): String? {
        if (depth > 15) return null
        if (isFinalUrl(url)) return url

        val response = try {
            scrapeSemaphore.withPermit { app.get(url, timeout = 15, referer = mainUrl) }
        } catch (e: Exception) { return null }

        if (!response.isSuccessful) return null

        extractFinalFromHtml(response.text)?.let { return it }

        val doc   = response.document
        val links =
            if ("isaidub.guru" in url && "/download/" !in url)
                extractIsaidubLinks(doc, url).ifEmpty { extractDownloadLinks(doc, url) }
            else
                extractDownloadLinks(doc, url)

        if (links.isEmpty()) return null

        links.firstOrNull { "download server" in it.first.lowercase() }
            ?.let { return resolveFinalLink(it.second, depth + 1) }

        if (links.size == 1) return resolveFinalLink(links[0].second, depth + 1)

        for (q in listOf("1080", "720", "480", "360")) {
            links.firstOrNull { q in it.first }
                ?.let { return resolveFinalLink(it.second, depth + 1) }
        }

        return resolveFinalLink(links[0].second, depth + 1)
    }

    // ============================================================
    // HELPERS
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
            val low = href.lowercase().trimEnd('/')
            if (low.endsWith("-tamil-dubbed-movie") || low.endsWith("-tamil-dubbed")) return@mapNotNull null
            if ("?get-page=" in low || "/category/" in low) return@mapNotNull null
            Pair(text, resolveUrl(baseUrl, href))
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
