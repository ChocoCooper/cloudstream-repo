package com.dubstamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.random.Random

class IsaidubProvider : MainAPI() {
    override var mainUrl = "https://isaidub.guru"
    override var name = "Isaidub"
    override val hasMainPage = true 
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "ta"

    // --- CONCURRENCY & CACHE ---
    private val omdbSemaphore = Semaphore(5)
    private val scrapeSemaphore = Semaphore(5)
    private val pageCache = mutableMapOf<String, Pair<Long, Pair<List<ScrapedMovie>, Int>>>()
    private val CACHE_DURATION = 5 * 60 * 1000L

    private val baseOmdbKeys = listOf("eb0c0475", "4b447405", "7776cbde", "ff28f90b", "6c3a2d45")
    private var activeOmdbKeys = baseOmdbKeys.toMutableList()

    private data class OmdbTitleResponse(val Title: String?, val Year: String?, val Poster: String?, val Plot: String?, val Response: String?)
    private data class ScrapedMovie(val title: String, val link: String)

    private fun getRandomApiKey(): String {
        if (activeOmdbKeys.isEmpty()) activeOmdbKeys.addAll(baseOmdbKeys)
        return activeOmdbKeys[Random.nextInt(activeOmdbKeys.size)]
    }

    private fun removeDeadKey(key: String) {
        activeOmdbKeys.remove(key)
    }

    // --- SMART TOKENIZATION & MAXIMUM MATCH LOGIC ---
    private fun normalizeTitle(title: String): String {
        var text = title.lowercase().trim()
        text = text.replace("&", "and")
        text = text.replace("judgment", "judgement") // Fix known Isaidub typo
        text = text.replace(Regex("[^a-z0-9\\s]"), " ")
        text = text.replace(Regex("\\s+"), " ")
        return text.trim()
    }

    private fun findBestMatch(movies: List<ScrapedMovie>, queryTitle: String, queryYear: String): String? {
        val normQuery = normalizeTitle(queryTitle)
        val stopwords = setOf("the", "a", "an", "and", "of", "to", "in", "for", "on", "with", "by")
        val queryTokens = normQuery.split(" ").filter { it.isNotBlank() }.toSet() - stopwords

        var bestMovieUrl: String? = null
        var maxMatchCount = 0

        for (movie in movies) {
            // STRICT YEAR CHECK: The year MUST be present in the site title
            if (queryYear.isNotBlank() && !movie.title.contains(queryYear)) {
                continue
            }

            val normSite = normalizeTitle(movie.title)
            val siteTokens = normSite.split(" ").filter { it.isNotBlank() }.toSet() - stopwords

            // Find how many words intersect
            val matchCount = queryTokens.intersect(siteTokens).size

            // Absolute maximum possible token match wins
            if (matchCount > maxMatchCount && matchCount > 0) {
                maxMatchCount = matchCount
                bestMovieUrl = movie.link
            }
        }

        return bestMovieUrl
    }

    private suspend fun fetchOmdbMetadata(rawTitle: String, fallbackYear: String = ""): Pair<OmdbTitleResponse?, String> {
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
            
            val response = omdbSemaphore.withPermit { app.get(url, timeout = 5) }

            if (response.code == 401 || response.text.contains("Limit reached", true) || response.text.contains("Invalid API key", true)) {
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

    private suspend fun scrapePageAndGetTotal(url: String): Pair<List<ScrapedMovie>, Int> {
        val cached = pageCache[url]
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_DURATION) {
            return cached.second
        }

        val movies = mutableListOf<ScrapedMovie>()
        var maxPage = 1
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "*/*"
            )
            val response = scrapeSemaphore.withPermit { app.get(url, headers = headers, timeout = 10) }
            if (!response.isSuccessful) return Pair(emptyList(), 1)
            
            val doc = response.document
            
            // Extract Movies
            for (div in doc.select("div.f")) {
                val aTag = div.selectFirst("a")
                if (aTag != null) {
                    val title = aTag.text().trim()
                    var link = aTag.attr("href")
                    if (link.startsWith("/")) link = "$mainUrl$link"
                    movies.add(ScrapedMovie(title, link))
                }
            }

            // Extract Total Pages
            val hiddenDiv = doc.selectFirst("div[style*=\"display: none;\"] div.pagecontent span#totalPages")
            val visibleTotal = doc.selectFirst("span#totalPages")
            
            if (hiddenDiv != null && hiddenDiv.text().toIntOrNull() != null) {
                maxPage = hiddenDiv.text().toInt()
            } else if (visibleTotal != null && visibleTotal.text().toIntOrNull() != null) {
                maxPage = visibleTotal.text().toInt()
            } else {
                doc.select("div.pagination-container a[href]").forEach { a ->
                    val pageNum = Regex("""get-page=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
                    if (pageNum != null && pageNum > maxPage) maxPage = pageNum
                }
            }
        } catch (e: Exception) { }

        val result = Pair(movies, maxPage)
        pageCache[url] = Pair(System.currentTimeMillis(), result)
        return result
    }

    private suspend fun searchDubbedMovieLinks(title: String, year: String): String? {
        val targets = mutableListOf<String>()
        year.toIntOrNull()?.let { targets.add("$mainUrl/tamil-$it-dubbed-movies/") }
        title.trim().firstOrNull()?.lowercaseChar()?.let {
            if (it.isLetter()) targets.add("$mainUrl/tamil-atoz-dubbed-movies/$it/") 
            else if (it.isDigit()) targets.add("$mainUrl/tamil-atoz-dubbed-movies/0-9/")
        }
        
        for (url in targets.distinct()) {
            val (p1Movies, maxPages) = scrapePageAndGetTotal(url)
            val allMovies = mutableListOf<ScrapedMovie>()
            allMovies.addAll(p1Movies)
            
            // Extract remaining pages concurrently to collect all possible candidates
            if (maxPages > 1) {
                coroutineScope {
                    (2..maxPages).map { p -> 
                        async { scrapePageAndGetTotal("$url?get-page=$p").first }
                    }.awaitAll().forEach { allMovies.addAll(it) }
                }
            }

            // Let the Best Match logic pick the absolute highest token match
            val bestMatch = findBestMatch(allMovies, title, year)
            if (bestMatch != null) return bestMatch
        }
        return null
    }

    // --- MAIN API OVERRIDES ---

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return getSharedHomePageData(page, request)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return getSharedSearchData(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        if (!url.contains("/synthetic_meta?")) {
            val rawName = url.trimEnd('/').substringAfterLast("/").replace("-", " ").replace(Regex("tamil.*", RegexOption.IGNORE_CASE), "").trim()
            val (omdbMatch, resolvedYear) = fetchOmdbMetadata(rawName)
            
            return newMovieLoadResponse(omdbMatch?.Title ?: rawName, url, TvType.Movie, url) {
                this.posterUrl = omdbMatch?.Poster?.takeIf { it != "N/A" }
                this.year = resolvedYear.toIntOrNull()
                this.plot = omdbMatch?.Plot?.takeIf { it != "N/A" } ?: "No synopsis available."
            }
        }

        val uri = URI(url)
        val queryParams = uri.query?.split("&")?.associate {
            val parts = it.split("=")
            parts[0] to URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        } ?: return null

        val title = queryParams["t"] ?: return null
        val year = queryParams["y"] ?: ""
        val omdbPoster = queryParams["p"] ?: "$mainUrl/uploads/posters/default.jpg"
        val failSafeUrl = queryParams["url"] 
        val yearInt = year.toIntOrNull()

        val targetUrl = if (!failSafeUrl.isNullOrBlank()) {
            failSafeUrl
        } else {
            searchDubbedMovieLinks(title, year) ?: return null
        }

        val (detailedMeta, _) = fetchOmdbMetadata(title, year)

        return newMovieLoadResponse(title, url, TvType.Movie, targetUrl) {
            this.posterUrl = omdbPoster
            this.year = yearInt
            this.plot = detailedMeta?.Plot?.takeIf { it != "N/A" } ?: "No synopsis available."
        }
    }

    // --- DEEP LINK EXTRACTION ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        
        var foundAnyLinks = false
        coroutineScope {
            // Kick off the extraction path
            async {
                if (crawlNode(data.trim(), "Auto", 0, mutableSetOf(), callback)) {
                    foundAnyLinks = true
                }
            }.await()
        }
        return foundAnyLinks
    }

    private fun isFinalDownloadUrl(url: String): Boolean {
        val lUrl = url.lowercase()
        return listOf(".mp4", ".mkv", ".avi", ".mov", ".webm").any { lUrl.endsWith(it) } ||
               lUrl.contains("download.php?dl=") ||
               listOf("dubpage.xyz", "dubmv.xyz", "dub.uptodub.ch").any { lUrl.contains(it) }
    }

    private fun invokeCallback(url: String, quality: String, callback: (ExtractorLink) -> Unit) {
        val isM3u8 = url.contains(".m3u8", ignoreCase = true)
        callback.invoke(
            ExtractorLink(
                source = this.name,
                name = "${this.name} $quality",
                url = url,
                referer = "$mainUrl/",
                quality = Qualities.Unknown.value,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8"
                )
            )
        )
    }

    private suspend fun crawlNode(
        url: String, 
        quality: String, 
        depth: Int, 
        seen: MutableSet<String>, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Safety abort for endless loops
        if (depth > 15 || !seen.add(url)) return false

        // Check if URL is instantly recognizable as a video file
        if (isFinalDownloadUrl(url)) {
            invokeCallback(url, quality, callback)
            return true
        }

        var found = false
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Referer" to mainUrl
            )
            
            // Protected scrape
            val response = scrapeSemaphore.withPermit { app.get(url, headers = headers, timeout = 15, allowRedirects = true) }
            if (response.code == 404) return false
            
            if (isFinalDownloadUrl(response.url)) {
                invokeCallback(response.url, quality, callback)
                return true
            }

            val html = response.text
            
            // Fast regex check
            val directMatch = Regex("""https?://[^\s"'<>]*download\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(html)?.value
                ?: Regex("""https?://[^\s"'<>]*dl\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(html)?.value
                ?: Regex("""https?://[^\s"'<>]*\.mp4[^"'\s]*""", RegexOption.IGNORE_CASE).find(html)?.value
                
            if (directMatch != null) {
                invokeCallback(directMatch, quality, callback)
                return true
            }

            val doc = response.document

            // PATH 1: Are there Download Servers?
            val downloadServers = doc.select("a[href]").filter { it.text().contains("download server", ignoreCase = true) }
            if (downloadServers.isNotEmpty()) {
                // If a download server exists, prioritize clicking it
                val srvUrl = fixUrl(downloadServers.first().attr("href"), url)
                if (crawlNode(srvUrl, quality, depth + 1, seen, callback)) found = true
                return found
            }

            // PATH 2: Are there Quality/Part Folders?
            val folders = doc.select("div.f a[href]").filter { !it.text().contains("sample", ignoreCase = true) }
            if (folders.isNotEmpty()) {
                coroutineScope {
                    // Extract ALL available non-sample folders concurrently!
                    folders.map { folder ->
                        async {
                            val fText = folder.text().lowercase()
                            var newQuality = quality
                            if (fText.contains("1080")) newQuality = "1080p"
                            else if (fText.contains("720")) newQuality = "720p"
                            else if (fText.contains("640") || fText.contains("480")) newQuality = "480p"
                            else if (fText.contains("360") || fText.contains("320")) newQuality = "360p"
                            else if (fText.contains("hd") && quality == "Auto") newQuality = "HD"

                            val nextUrl = fixUrl(folder.attr("href"), url)
                            if (crawlNode(nextUrl, newQuality, depth + 1, seen, callback)) found = true
                        }
                    }.awaitAll()
                }
                return found
            }

            // PATH 3: Fallback (any other applicable links on page)
            val fallbackLinks = doc.select("a[href]").filter {
                val hrefLower = it.attr("href").lowercase()
                listOf(".mp4", ".mkv", ".avi", "download.php?dl=", "dubpage.xyz", "dubmv.xyz", "dub.uptodub.ch").any { ext -> hrefLower.contains(ext) }
            }
            if (fallbackLinks.isNotEmpty()) {
                val nextUrl = fixUrl(fallbackLinks.first().attr("href"), url)
                if (crawlNode(nextUrl, quality, depth + 1, seen, callback)) found = true
            }

        } catch (e: Exception) {}
        
        return found
    }

    private fun fixUrl(href: String, baseUrl: String): String {
        if (href.startsWith("http")) return href
        if (href.startsWith("//")) return "https:$href"
        
        return try {
            val hostUrl = "https://${URI(baseUrl).host}"
            if (href.startsWith("/")) "$hostUrl$href" else "$hostUrl/$href"
        } catch (e: Exception) {
            mainUrl + (if (href.startsWith("/")) href else "/$href")
        }
    }
}
