package com.isaidub // Correct package for your build

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import kotlin.random.Random

class IsaidubProvider : MainAPI() {
    override var mainUrl = "https://isaidub.guru"
    override var name = "Isaidub"
    override val hasMainPage = true 
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "ta"

    // --- OPTIMIZATION: CONCURRENCY LIMITERS ---
    private val omdbSemaphore = Semaphore(5)
    private val scrapeSemaphore = Semaphore(5)

    // --- OPTIMIZATION: IN-MEMORY CACHE ---
    private val pageCache = mutableMapOf<String, Pair<Long, Pair<List<ScrapedMovie>, Int>>>()
    private val CACHE_DURATION = 5 * 60 * 1000L // 5 minutes

    // --- OPTIMIZATION: SMART KEY EVICTION ---
    private val baseOmdbKeys = listOf(
        "4b447405", "eb0c0475", "7776cbde", "ff28f90b",
        "6c3a2d45", "b07b58c8", "ad04b643", "a95b5205",
        "777d9323", "2c2c3314", "b5cff164", "89a9f57d",
        "73a9858a", "efbd8357"
    )
    private var activeOmdbKeys = baseOmdbKeys.toMutableList()

    data class OmdbSearchResponse(val Search: List<OmdbSearchResult>?, val Response: String?)
    data class OmdbSearchResult(val Title: String?, val Year: String?, val Poster: String?)
    data class OmdbTitleResponse(val Title: String?, val Year: String?, val Poster: String?, val Plot: String?, val Response: String?)

    data class ScrapedMovie(val title: String, val link: String)
    data class ResolutionNode(val label: String, val url: String)

    private fun getRandomApiKey(): String {
        if (activeOmdbKeys.isEmpty()) activeOmdbKeys.addAll(baseOmdbKeys)
        return activeOmdbKeys[Random.nextInt(activeOmdbKeys.size)]
    }

    private fun removeDeadKey(key: String) {
        activeOmdbKeys.remove(key)
    }

    // --- TOKENIZATION & MATCHING HELPERS ---

    private fun normalizeTitle(title: String): String {
        var text = title.lowercase().trim()
        text = text.replace("&", "and")
        text = text.replace("judgment", "judgement")
        text = text.replace("_", " ")

        val romanMap = mapOf(
            "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5",
            "vi" to "6", "vii" to "7", "viii" to "8", "ix" to "9"
        )
        romanMap.forEach { (roman, digit) ->
            text = text.replace(Regex("\\b(part|vol|chapter|volume)\\s+$roman\\b"), digit)
        }
        return text
    }

    private fun tokenize(text: String, yearToRemove: String = ""): Set<String> {
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

    private fun searchByTokenAndYear(movies: List<ScrapedMovie>, queryTitle: String, queryYear: String): List<ScrapedMovie> {
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
            
            val response = omdbSemaphore.withPermit { app.get(url, timeout = 3) }

            if (response.code == 401 || response.text.contains("Limit reached", ignoreCase = true) || response.text.contains("Invalid API key", ignoreCase = true)) {
                removeDeadKey(apiKey)
            } else if (response.isSuccessful && response.text.contains("\"Response\":\"True\"")) {
                val parsed = AppUtils.parseJson<OmdbTitleResponse>(response.text)
                if (parsed.Poster != null && parsed.Poster != "N/A") {
                    return Pair(parsed, extractedYear)
                }
            }
        } catch (e: Exception) { }
        return Pair(null, extractedYear) 
    }

    private suspend fun searchIsaidubMovieLinks(title: String, year: String): List<String> {
        val targets = mutableListOf<String>()
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

    private suspend fun fetchSectionItems(targetBaseUrl: String, sectionYear: String = ""): List<SearchResponse> {
        val listItems = mutableListOf<SearchResponse>()
        var currentPage = 1
        val maxPagesToScrape = 3

        while (listItems.size < 6 && currentPage <= maxPagesToScrape) {
            val targetUrl = if (currentPage == 1) targetBaseUrl else "$targetBaseUrl?get-page=$currentPage"
            try {
                val doc = scrapeSemaphore.withPermit { app.get(targetUrl, timeout = 15).document }
                val validMovieLinks = mutableListOf<Pair<String, String>>()
                
                for (a in doc.select("div.f a")) {
                    val title = a.text().trim()
                    var link = a.attr("href")
                    if (link.startsWith("/")) link = "$mainUrl$link"
                    
                    val lowerTitle = title.lowercase()
                    val lowerLink = link.lowercase()
                    
                    if (lowerTitle.contains("web series") || lowerLink.contains("web-series") ||
                        lowerTitle.contains("season") || lowerTitle.contains("episode")) continue
                    
                    validMovieLinks.add(Pair(title, link))
                }

                if (validMovieLinks.isEmpty()) break

                val responses = coroutineScope {
                    validMovieLinks.map { (title, link) ->
                        async {
                            val cleanTitle = title.replace("isaiDub.me", "").replace("-", " ").trim()
                            val (omdbMatch, resolvedYear) = fetchOmdbMetadata(cleanTitle, sectionYear)
                            
                            val omdbPoster = omdbMatch?.Poster?.takeIf { it != "N/A" }
                            val plotSynopsis = omdbMatch?.Plot?.takeIf { it != "N/A" } ?: "No synopsis available."
                            
                            if (omdbPoster == null) null else {
                                val t = URLEncoder.encode(cleanTitle, "UTF-8")
                                val y = URLEncoder.encode(resolvedYear, "UTF-8")
                                val p = URLEncoder.encode(omdbPoster, "UTF-8")
                                val u = URLEncoder.encode(link, "UTF-8")
                                val s = URLEncoder.encode(plotSynopsis, "UTF-8")
                                
                                val targetData = "$mainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=$u&s=$s"

                                newMovieSearchResponse(cleanTitle, targetData) {
                                    this.posterUrl = omdbPoster
                                    this.year = resolvedYear.toIntOrNull()
                                }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                for (res in responses) {
                    if (listItems.size < 6 && listItems.none { it.name == res.name }) {
                        listItems.add(res)
                    }
                }

                val totalPagesSpan = doc.selectFirst("span#totalPages")
                val maxPageStr = totalPagesSpan?.text()?.trim()?.toIntOrNull()
                if (maxPageStr != null && currentPage >= maxPageStr) break
                
            } catch (e: Exception) { break }
            currentPage++
        }
        return listItems
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homePageLists = mutableListOf<HomePageList>()
        
        try {
            val yearlyDoc = scrapeSemaphore.withPermit { app.get("$mainUrl/tamil-yearly-dubbed-movies/", timeout = 15).document }
            var latestYearUrl = ""
            var latestYear = ""
            
            for (a in yearlyDoc.select("a[href]")) {
                val href = a.attr("href")
                if (href.contains(Regex("tamil-\\d{4}-dubbed-movies"))) {
                    latestYearUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                    latestYear = Regex("\\d{4}").find(href)?.value ?: ""
                    break
                }
            }

            coroutineScope {
                val newMoviesDeferred = async { if (latestYearUrl.isNotEmpty()) fetchSectionItems(latestYearUrl, latestYear) else emptyList() }
                val actionDeferred = async { fetchSectionItems("$mainUrl/tamil-action-dubbed-movies/") }
                val comedyDeferred = async { fetchSectionItems("$mainUrl/tamil-comedy-dubbed-movies/") }
                val horrorDeferred = async { fetchSectionItems("$mainUrl/tamil-horror-dubbed-movies/") }
                val familyDeferred = async { fetchSectionItems("$mainUrl/tamil-family-dubbed-movies/") }

                val newMoviesList = newMoviesDeferred.await()
                if (newMoviesList.isNotEmpty()) {
                    homePageLists.add(HomePageList("New Tamil Dubbed Movies", newMoviesList, isHorizontalImages = false))
                }
                
                val sections = listOf(
                    Pair("Tamil Dubbed Action Movies", actionDeferred.await()),
                    Pair("Tamil Dubbed Comedy Movies", comedyDeferred.await()),
                    Pair("Tamil Dubbed Horror Movies", horrorDeferred.await()),
                    Pair("Tamil Dubbed Family Movies", familyDeferred.await())
                )

                for ((title, listData) in sections) {
                    if (listData.isNotEmpty()) {
                        homePageLists.add(HomePageList(title, listData, isHorizontalImages = false))
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(homePageLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        var omdbJson: NiceResponse? = null
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        try {
            val apiKey = getRandomApiKey()
            val url = "https://www.omdbapi.com/?apikey=$apiKey&s=$encodedQuery&type=movie"
            val response = omdbSemaphore.withPermit { app.get(url, timeout = 3) }

            if (response.code == 401 || response.text.contains("Limit reached", ignoreCase = true) || response.text.contains("Invalid API key", ignoreCase = true)) {
                removeDeadKey(apiKey)
            } else if (response.isSuccessful && response.text.contains("\"Response\":\"True\"")) {
                omdbJson = response
            }
        } catch (e: Exception) { }

        if (omdbJson == null) return emptyList()
        val parsed = AppUtils.parseJson<OmdbSearchResponse>(omdbJson.text)
        
        val validOmdbResults = parsed.Search?.filter { item ->
            !item.Poster.isNullOrBlank() && item.Poster != "N/A"
        } ?: emptyList()

        return coroutineScope {
            validOmdbResults.map { item ->
                async {
                    val title = item.Title ?: ""
                    val year = item.Year?.replace(Regex("[^0-9]"), "") ?: "" 
                    val fullPoster = item.Poster ?: ""

                    val isaidubLinks = searchIsaidubMovieLinks(title, year)
                    val combinedLinks = isaidubLinks.joinToString(",")

                    if (combinedLinks.isNotBlank()) {
                        val (detailedMeta, _) = fetchOmdbMetadata(title, year)
                        val plotSynopsis = detailedMeta?.Plot?.takeIf { it != "N/A" } ?: "No synopsis available."

                        val t = URLEncoder.encode(title, "UTF-8")
                        val y = URLEncoder.encode(year, "UTF-8")
                        val p = URLEncoder.encode(fullPoster, "UTF-8")
                        val s = URLEncoder.encode(plotSynopsis, "UTF-8") 
                        val u = URLEncoder.encode(combinedLinks, "UTF-8")
                        
                        val targetData = "$mainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=$u&s=$s"

                        newMovieSearchResponse(title, targetData) {
                            this.posterUrl = fullPoster
                            this.year = year.toIntOrNull()
                        }
                    } else {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // Hero Section & Watch History bypass (Handles old/raw URLs seamlessly)
        if (!url.contains("/synthetic_meta?")) {
            val rawName = url.trimEnd('/').substringAfterLast("/").replace("-", " ").replace(Regex("tamil.*", RegexOption.IGNORE_CASE), "").trim()
            val (omdbMatch, resolvedYear) = fetchOmdbMetadata(rawName)
            
            return newMovieLoadResponse(omdbMatch?.Title ?: rawName, url, TvType.Movie, url) {
                this.posterUrl = omdbMatch?.Poster?.takeIf { it != "N/A" }
                this.year = resolvedYear.toIntOrNull()
                this.plot = omdbMatch?.Plot?.takeIf { it != "N/A" } ?: "No synopsis available."
            }
        }

        val uri = java.net.URI(url)
        val queryParams = uri.query?.split("&")?.associate {
            val parts = it.split("=")
            parts[0] to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        } ?: return null

        val title = queryParams["t"] ?: return null
        val year = queryParams["y"] ?: ""
        val omdbPoster = queryParams["p"] ?: "$mainUrl/uploads/posters/default.jpg"
        val failSafeUrl = queryParams["url"] 
        val plotSynopsis = queryParams["s"] ?: "" 
        val yearInt = year.toIntOrNull()

        if (!failSafeUrl.isNullOrBlank()) {
            return newMovieLoadResponse(title, url, TvType.Movie, failSafeUrl) {
                this.posterUrl = omdbPoster
                this.year = yearInt
                this.plot = plotSynopsis 
            }
        }

        val isaidubLinks = searchIsaidubMovieLinks(title, year)
        if (isaidubLinks.isEmpty()) return null
        val combinedUrls = isaidubLinks.joinToString(",")

        return newMovieLoadResponse(title, url, TvType.Movie, combinedUrls) {
            this.posterUrl = omdbPoster
            this.year = yearInt
            this.plot = plotSynopsis 
        }
    }

    private suspend fun scrapePageAndGetTotal(url: String): Pair<List<ScrapedMovie>, Int> {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = data.split(",")
        var foundAnyLinks = false

        urls.forEach { targetUrl ->
            val resolutions = getResolutions(targetUrl.trim())

            if (resolutions.isEmpty()) {
                val finalLink = extractFinalLink(targetUrl.trim(), 0, mutableSetOf())
                if (finalLink != null) {
                    val isM3u8 = finalLink.contains(".m3u8", ignoreCase = true)
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "Isaidub (Auto)",
                            url = finalLink,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundAnyLinks = true
                }
            } else {
                resolutions.forEach { res ->
                    val finalLink = extractFinalLink(res.url, 0, mutableSetOf())
                    if (finalLink != null) {
                        val isM3u8 = finalLink.contains(".m3u8", ignoreCase = true)
                        
                        val lowerLabel = res.label.lowercase()
                        val qualityName = when {
                            lowerLabel.contains("1080") -> "(1080p)"
                            lowerLabel.contains("720") -> "(720p)"
                            lowerLabel.contains("640") || lowerLabel.contains("360") -> "(640x360)"
                            lowerLabel.contains("480") || lowerLabel.contains("320") -> "(480x320)"
                            else -> "(HD)"
                        }
                        
                        val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "Isaidub $qualityName",
                                url = finalLink,
                                type = linkType
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                    "Accept" to "*/*",
                                    "Connection" to "keep-alive"
                                )
                            }
                        )
                        foundAnyLinks = true
                    }
                }
            }
        }
        return foundAnyLinks
    }

    private suspend fun getResolutions(pageUrl: String, depth: Int = 0, maxDepth: Int = 3): List<ResolutionNode> {
        if (depth > maxDepth) return emptyList()

        val foundResolutions = mutableListOf<ResolutionNode>()
        val folderPages = mutableListOf<String>()

        try {
            val doc = scrapeSemaphore.withPermit { app.get(pageUrl, timeout = 15).document }
            
            // Reverted back to grabbing standard links, safely filtering out unrelated paths
            for (a in doc.select("a[href]")) {
                val href = a.attr("href")
                val text = a.text().trim()
                val textLower = text.lowercase()

                if (href.contains("sample", true) || textLower.contains("sample")) continue
                if (href.contains("/movie/page/") || href.contains("?get-page=")) continue

                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                if (fullUrl == pageUrl) continue

                if (href.contains("/movie/")) {
                    val isResolution = listOf("360", "480", "640", "720", "1080", "hd", "mp4").any { textLower.contains(it) }
                    
                    if (isResolution) {
                        if (foundResolutions.none { it.url == fullUrl }) {
                            foundResolutions.add(ResolutionNode(text, fullUrl))
                        }
                    } else {
                        if (!folderPages.contains(fullUrl)) {
                            folderPages.add(fullUrl)
                        }
                    }
                }
            }

            if (foundResolutions.isEmpty() && folderPages.isNotEmpty()) {
                // Smart Filter: Ensure we only crawl subfolders representing THIS movie (avoids Related Movies section)
                val cleanBase = pageUrl.trimEnd('/').substringAfterLast("/").replace(".html", "")
                val validFolders = folderPages.filter { it.contains(cleanBase, ignoreCase = true) }
                
                for (folderUrl in validFolders) {
                    val nested = getResolutions(folderUrl, depth + 1, maxDepth)
                    for (nr in nested) {
                        if (foundResolutions.none { it.url == nr.url }) {
                            foundResolutions.add(nr)
                        }
                    }
                }
            }
        } catch (e: Exception) { }

        return foundResolutions
    }

    private suspend fun extractFinalLink(url: String, depth: Int, seen: MutableSet<String>): String? {
        if (seen.contains(url) || depth > 6) return null
        seen.add(url)

        try {
            val res = scrapeSemaphore.withPermit { app.get(url, timeout = 15) }
            if (res.headers["content-type"]?.contains("video/") == true) {
                return res.url
            }

            val text = res.text

            val dlPhpMatch = Regex("""https?://[^\s"'<>]*download\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)
            val m3u8Match = Regex("""https?://[^\s"'<>]*\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)
            val mp4Match = Regex("""https?://[^\s"'<>]*\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)

            if (dlPhpMatch != null) return dlPhpMatch.value
            if (m3u8Match != null) return m3u8Match.value
            if (mp4Match != null) return mp4Match.value

            val doc = Jsoup.parse(text)
            val validPaths = listOf("/download/", "/view/", "/file/", "download.php", "dl.php")

            for (a in doc.select("a[href]")) {
                val href = a.attr("href")
                val linkText = a.text().lowercase()

                if (linkText.contains("sample") || href.lowercase().contains("sample")) continue

                val fullUrl = when {
                    href.startsWith("http") -> href
                    href.startsWith("//") -> "https:$href"
                    else -> {
                        val uri = URI(url)
                        "https://${uri.host}$href"
                    }
                }

                if (validPaths.any { fullUrl.lowercase().contains(it) }) {
                    val finalUrl = extractFinalLink(fullUrl, depth + 1, seen)
                    if (finalUrl != null) return finalUrl
                }
            }
        } catch (e: Exception) { }

        return null
    }
}
