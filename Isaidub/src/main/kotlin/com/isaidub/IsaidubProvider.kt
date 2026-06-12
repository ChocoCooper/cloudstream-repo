package com.isaidub // Adjust package name to match your repository

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

class IsaidubProvider : MainAPI() {
    override var mainUrl = "https://isaidub.guru"
    override var name = "Isaidub"
    override val hasMainPage = false
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "ta"

    private val tmdbApiKey = "1b3113663c9004682ed61086cf967c44"
    
    private val tmdbUrls = listOf(
        "https://api.themoviedb.org/3",
        "https://api.tmdb.org/3",
        "https://tmdb-proxy.cubecity.cloud/3"
    )

    data class TmdbSearchResponse(val results: List<TmdbResult>?)
    data class TmdbResult(
        val title: String?,
        val name: String?,
        val release_date: String?,
        val poster_path: String?
    )

    data class ScrapedMovie(val title: String, val link: String)
    data class ResolutionNode(val label: String, val url: String)

    // --- TOKENIZATION & MATCHING HELPERS ---

    private fun normalizeTitle(title: String): String {
        var text = title.lowercase().trim()
        text = text.replace("&", "and")
        text = text.replace("judgment", "judgement")

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
        if (yearToRemove.isNotBlank()) {
            cleanText = cleanText.replace(yearToRemove, "")
        }
        
        return cleanText
            .replace(Regex("[^\u0000-\u007F]"), " ") 
            .replace(Regex("[^a-z0-9\\s]"), " ")   
            .replace(Regex("^(the|a|an)\\s+"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun searchByTokenAndYear(movies: List<ScrapedMovie>, tmdbTitle: String, tmdbYear: String): List<ScrapedMovie> {
        val tmdbTokens = tokenize(normalizeTitle(tmdbTitle))
        val matches = mutableListOf<Pair<ScrapedMovie, Int>>()

        for (movie in movies) {
            val titleLower = movie.title.lowercase()

            if (tmdbYear.isNotBlank() && !titleLower.contains(tmdbYear)) {
                continue
            }

            val siteTokens = tokenize(normalizeTitle(movie.title), yearToRemove = tmdbYear)
            val commonTokens = tmdbTokens.intersect(siteTokens)

            val matchPercentage = commonTokens.size.toDouble() / tmdbTokens.size

            val isMatch = if (tmdbTokens.size <= 2) {
                matchPercentage == 1.0
            } else {
                val significantMatches = tmdbTokens.filter { siteTokens.contains(it) && it.length > 2 }
                matchPercentage >= 0.6 && significantMatches.isNotEmpty()
            }

            if (isMatch) {
                matches.add(Pair(movie, commonTokens.size))
            }
        }
        return matches.sortedByDescending { it.second }.map { it.first }
    }

    // --- PHASE 1: SEARCH (TMDB METADATA ONLY + FILTERING) ---

    override suspend fun search(query: String): List<SearchResponse> {
        var tmdbJson: NiceResponse? = null
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        for (baseUrl in tmdbUrls) {
            try {
                val url = "$baseUrl/search/movie?api_key=$tmdbApiKey&query=$encodedQuery"
                val response = app.get(url, timeout = 15)
                if (response.isSuccessful && response.text.contains("results")) {
                    tmdbJson = response
                    break
                }
            } catch (e: Exception) { }
        }

        if (tmdbJson == null) return emptyList()
        val parsed = AppUtils.parseJson<TmdbSearchResponse>(tmdbJson.text)
        
        val currentDate = "2026-06-12"
        val validTmdbResults = parsed.results?.filter { item ->
            val hasPoster = !item.poster_path.isNullOrBlank()
            val releaseDate = item.release_date ?: ""
            val isReleased = releaseDate.isNotBlank() && releaseDate <= currentDate
            hasPoster && isReleased
        } ?: emptyList()

        return validTmdbResults.map { item ->
            val title = item.title ?: item.name ?: ""
            val year = item.release_date?.split("-")?.firstOrNull() ?: ""
            val fullPoster = "https://image.tmdb.org/t/p/w500${item.poster_path}"

            val t = URLEncoder.encode(title, "UTF-8")
            val y = URLEncoder.encode(year, "UTF-8")
            val p = URLEncoder.encode(fullPoster, "UTF-8")
            
            val targetData = "$mainUrl/synthetic_meta?t=$t&y=$y&p=$p"

            newMovieSearchResponse(title, targetData) {
                this.posterUrl = fullPoster
                this.year = year.toIntOrNull()
            }
        }
    }

    // --- PHASE 2: LOAD (DEEP CONCURRENT CRAWLING ON CLICK) ---

    override suspend fun load(url: String): LoadResponse? {
        if (!url.contains("/synthetic_meta?")) return null

        val uri = java.net.URI(url)
        val queryParams = uri.query?.split("&")?.associate {
            val parts = it.split("=")
            parts[0] to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        } ?: return null

        val title = queryParams["t"] ?: return null
        val year = queryParams["y"] ?: ""
        val fallbackPoster = queryParams["p"]
        val yearInt = year.toIntOrNull()

        val urlsToScan = mutableListOf<String>()

        if (yearInt != null && yearInt in 1980..2026) {
            urlsToScan.add("$mainUrl/tamil-$yearInt-dubbed-movies/")
        }

        val cleanedTitle = title.trim()
        val firstChar = cleanedTitle.firstOrNull()?.lowercaseChar()
        if (firstChar != null) {
            when {
                firstChar.isLetter() -> urlsToScan.add("$mainUrl/tamil-atoz-dubbed-movies/$firstChar/")
                firstChar.isDigit() -> urlsToScan.add("$mainUrl/tamil-atoz-dubbed-movies/0-9/")
                else -> urlsToScan.add("$mainUrl/tamil-atoz-dubbed-movies/a/")
            }
        }

        val articles = listOf("the ", "a ", "an ")
        for (article in articles) {
            if (cleanedTitle.lowercase().startsWith(article)) {
                val coreTitle = cleanedTitle.substring(article.length).trim()
                val nextChar = coreTitle.firstOrNull()?.lowercaseChar()
                if (nextChar != null) {
                    when {
                        nextChar.isLetter() -> urlsToScan.add("$mainUrl/tamil-atoz-dubbed-movies/$nextChar/")
                        nextChar.isDigit() -> urlsToScan.add("$mainUrl/tamil-atoz-dubbed-movies/0-9/")
                    }
                }
                break
            }
        }

        // List to hold all potential duplicate movie entries
        val matchedMovies = mutableListOf<ScrapedMovie>()

        for (targetBaseUrl in urlsToScan.distinct()) {
            if (matchedMovies.isNotEmpty()) break 

            val (page1Movies, totalPages) = scrapePageAndGetTotal(targetBaseUrl)
            
            val page1Hits = searchByTokenAndYear(page1Movies, title, year)
            if (page1Hits.isNotEmpty()) {
                matchedMovies.addAll(page1Hits)
                break // Break out to ensure we aggregate all duplicates found on this page
            }

            if (totalPages > 1) {
                val chunkSize = 5
                val totalChunks = (totalPages - 1 + chunkSize - 1) / chunkSize 

                for (chunkIdx in 0 until totalChunks) {
                    if (matchedMovies.isNotEmpty()) break

                    val startPage = chunkIdx * chunkSize + 2 
                    val endPage = minOf(startPage + chunkSize - 1, totalPages)
                    val pagesToScan = (startPage..endPage).toList()

                    val chunkResults = coroutineScope {
                        pagesToScan.map { page ->
                            async {
                                val targetUrl = "$targetBaseUrl?get-page=$page"
                                scrapePageAndGetTotal(targetUrl).first
                            }
                        }.awaitAll()
                    }

                    for (pageMovies in chunkResults) {
                        val hits = searchByTokenAndYear(pageMovies, title, year)
                        if (hits.isNotEmpty()) {
                            matchedMovies.addAll(hits)
                        }
                    }
                    if (matchedMovies.isNotEmpty()) break
                }
            }
        }

        if (matchedMovies.isEmpty()) return null

        // Deduplicate links just in case, and combine them using a comma separator
        val finalMoviePages = matchedMovies.map { it.link }.distinct()
        val combinedUrls = finalMoviePages.joinToString(",")
        val finalPoster = fetchPosterUrl(finalMoviePages.first()) ?: fallbackPoster

        // Pass the comma-separated string to loadLinks via the URL parameter
        return newMovieLoadResponse(title, combinedUrls, TvType.Movie, combinedUrls) {
            this.posterUrl = finalPoster
            this.year = yearInt
        }
    }

    private suspend fun scrapePageAndGetTotal(url: String): Pair<List<ScrapedMovie>, Int> {
        val movies = mutableListOf<ScrapedMovie>()
        var maxPage = 1
        try {
            val doc = app.get(url, timeout = 15).document
            
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
                        if (num != null && num > maxPage && num <= 25) { 
                            maxPage = num
                        }
                    } else if (text.toIntOrNull() != null) {
                        val num = text.toIntOrNull()
                        if (num != null && num > maxPage && num <= 25 && href.length < 50) {
                            maxPage = num
                        }
                    }
                }
            }
        } catch (e: Exception) { }
        return Pair(movies, maxPage)
    }

    private suspend fun fetchPosterUrl(movieUrl: String): String? {
        return try {
            val doc = app.get(movieUrl, timeout = 15).document
            val container = doc.selectFirst("div.movie-info-container")
            val img = container?.selectFirst("img") ?: doc.selectFirst("img[src*=poster]")
            val src = img?.attr("src")
            if (!src.isNullOrBlank()) {
                if (src.startsWith("/")) "$mainUrl$src" else src
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // --- PHASE 3: CRAWL AND RESOLVE MEDIA STREAM LINKS ---

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Data can now contain multiple comma-separated URLs
        val urls = data.split(",")
        var foundAnyLinks = false

        urls.forEach { targetUrl ->
            val resolutions = getResolutions(targetUrl.trim())

            resolutions.forEach { res ->
                val finalLink = extractFinalLink(res.url, 0, mutableSetOf())
                if (finalLink != null) {
                    val isM3u8 = finalLink.contains(".m3u8", ignoreCase = true)
                    
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = res.label,
                            url = finalLink,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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
        return foundAnyLinks
    }

    private suspend fun getResolutions(pageUrl: String, depth: Int = 0, maxDepth: Int = 2): List<ResolutionNode> {
        if (depth > maxDepth) return emptyList()

        val foundResolutions = mutableListOf<ResolutionNode>()
        val folderPages = mutableListOf<String>()

        try {
            val doc = app.get(pageUrl, timeout = 15).document
            for (a in doc.select("a[href]")) {
                val href = a.attr("href")
                val text = a.text().trim()

                if (href.contains("/movie/")) {
                    if (text.lowercase().contains("sample") || href == pageUrl || href.contains("/movie/page/")) continue

                    val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                    val textLower = text.lowercase()

                    val isResolution = listOf("360", "480", "640", "720", "1080", "hd", "mp4").any { textLower.contains(it) }
                    val isFolder = listOf("original", "single", "full", "bdprint", "dvd").any { textLower.contains(it) }

                    if (isResolution) {
                        if (foundResolutions.none { it.url == fullUrl }) {
                            foundResolutions.add(ResolutionNode(text, fullUrl))
                        }
                    } else if (isFolder) {
                        if (!folderPages.contains(fullUrl)) {
                            folderPages.add(fullUrl)
                        }
                    }
                }
            }

            if (foundResolutions.isEmpty() && folderPages.isNotEmpty()) {
                for (folderUrl in folderPages) {
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
            val res = app.get(url, timeout = 15)
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
            val validPaths = listOf("/download/", "/view/", "/file/", "download.php")

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
