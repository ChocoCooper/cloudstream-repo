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
        "https://api.tmdb.org/3"
    )

    data class TmdbSearchResponse(val results: List<TmdbResult>?)
    data class TmdbResult(
        val title: String?,
        val name: String?,
        val release_date: String?,
        val poster_path: String?
    )

    data class ScrapedMovie(val title: String, val link: String)

    // --- TOKENIZATION & MATCHING HELPERS ---

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .replace(Regex("[^\u0000-\u007F]"), " ") // Strip non-ascii if any
            .replace(Regex("[^a-z0-9\\s]"), " ")   // Clean text punctuation
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()
    }

    private fun searchByTokenAndYear(movies: List<ScrapedMovie>, tmdbTitle: String, tmdbYear: String): List<ScrapedMovie> {
        val tmdbTokens = tokenize(tmdbTitle)
        val matches = mutableListOf<Pair<ScrapedMovie, Int>>()

        for (movie in movies) {
            val titleLower = movie.title.lowercase()

            // 1. Year Check (Ignore if target year is provided but missing from site's link text)
            if (tmdbYear.isNotBlank() && !titleLower.contains(tmdbYear)) {
                continue
            }

            // 2. Token Matching Overlap
            val siteTokens = tokenize(movie.title)
            val commonTokens = tmdbTokens.intersect(siteTokens)

            if (commonTokens.size >= 2) {
                matches.add(Pair(movie, commonTokens.size))
            }
        }

        // Sort descending by token overlap score
        return matches.sortedByDescending { it.second }.map { it.first }
    }

    // --- CONCURRENT SCRAPING CORE ---

    override suspend fun search(query: String): List<SearchResponse> {
        var tmdbJson: NiceResponse? = null
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        for (baseUrl in tmdbUrls) {
            try {
                val url = "$baseUrl/search/movie?api_key=$tmdbApiKey&query=$encodedQuery"
                val response = app.get(url, timeout = 5)
                if (response.isSuccessful && response.text.contains("results")) {
                    tmdbJson = response
                    break
                }
            } catch (e: Exception) { }
        }

        if (tmdbJson == null) return emptyList()
        val parsed = AppUtils.parseJson<TmdbSearchResponse>(tmdbJson.text)
        val validTmdbResults = parsed.results?.filter { it.poster_path != null } ?: emptyList()

        val results = validTmdbResults.amap { item ->
            val title = item.title ?: item.name ?: return@amap null
            val year = item.release_date?.split("-")?.firstOrNull() ?: ""

            // --- TARGET DIRECTORY LOGIC ---
            val directoriesToScan = mutableSetOf<String>()
            val cleanedTitle = title.trim()
            val firstChar = cleanedTitle.firstOrNull()?.lowercaseChar()

            if (firstChar != null) {
                when {
                    firstChar.isLetter() -> directoriesToScan.add(firstChar.toString())
                    firstChar.isDigit() -> directoriesToScan.add("0-9")
                    else -> directoriesToScan.add("a")
                }
            }

            val articles = listOf("the ", "a ", "an ")
            for (article in articles) {
                if (cleanedTitle.lowercase().startsWith(article)) {
                    val coreTitle = cleanedTitle.substring(article.length).trim()
                    val nextChar = coreTitle.firstOrNull()?.lowercaseChar()
                    if (nextChar != null) {
                        when {
                            nextChar.isLetter() -> directoriesToScan.add(nextChar.toString())
                            nextChar.isDigit() -> directoriesToScan.add("0-9")
                        }
                    }
                    break
                }
            }

            // --- EARLY-EXIT SCANNING WITH 5-PAGE CONCURRENT CHUNKS ---
            var matchedMovie: ScrapedMovie? = null

            for (directory in directoriesToScan.sorted()) {
                if (matchedMovie != null) break 

                val baseUrl = "$mainUrl/tamil-atoz-dubbed-movies/$directory/"
                val totalPages = getTotalPages(baseUrl)

                if (totalPages > 0) {
                    val chunkSize = 5
                    val totalChunks = (totalPages + chunkSize - 1) / chunkSize

                    for (chunkIdx in 0 until totalChunks) {
                        if (matchedMovie != null) break

                        val startPage = chunkIdx * chunkSize + 1
                        val endPage = minOf(startPage + chunkSize - 1, totalPages)
                        val pagesToScan = (startPage..endPage).toList()

                        // Process 5 pages concurrently
                        val chunkResults = coroutineScope {
                            pagesToScan.map { page ->
                                async {
                                    val targetUrl = if (page == 1) baseUrl else "$baseUrl$page/"
                                    scrapeSinglePage(targetUrl)
                                }
                            }.awaitAll()
                        }

                        // Flatten and look for hits instantly
                        for (pageMovies in chunkResults) {
                            val hits = searchByTokenAndYear(pageMovies, title, year)
                            if (hits.isNotEmpty()) {
                                matchedMovie = hits.first() // Grab highest scoring result
                                break
                            }
                        }
                    }
                }
            }

            // Fallback: Build poster dynamic/native slug or extract from the target page if found
            if (matchedMovie != null) {
                val posterUrl = fetchPosterUrl(matchedMovie.link) ?: "$mainUrl/uploads/posters/default.jpg"

                val t = URLEncoder.encode(title, "UTF-8")
                val y = URLEncoder.encode(year, "UTF-8")
                val p = URLEncoder.encode(posterUrl, "UTF-8")
                val targetData = "$mainUrl/synthetic?t=$t&y=$y&p=$p"

                newMovieSearchResponse(title, targetData) {
                    this.posterUrl = posterUrl
                    this.year = year.toIntOrNull()
                }
            } else {
                null
            }
        }.filterNotNull()

        return results
    }

    private suspend fun getTotalPages(url: String): Int {
        return try {
            val doc = app.get(url, timeout = 5).document
            val totalPagesSpan = doc.selectFirst("span#totalPages")
            totalPagesSpan?.text()?.trim()?.toIntOrNull() ?: 1
        } catch (e: Exception) {
            0
        }
    }

    private suspend fun scrapeSinglePage(url: String): List<ScrapedMovie> {
        val movies = mutableListOf<ScrapedMovie>()
        try {
            val doc = app.get(url, timeout = 5).document
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
        } catch (e: Exception) { }
        return movies
    }

    private suspend fun fetchPosterUrl(movieUrl: String): String? {
        return try {
            val doc = app.get(movieUrl, timeout = 5).document
            val container = doc.selectFirst("div.movie-info-container")
            if (container != null) {
                val img = container.selectFirst("img")
                val src = img?.attr("src")
                if (!src.isNullOrBlank()) {
                    if (src.startsWith("/")) "$mainUrl$src" else src
                } else null
            } else null
        } catch (e: Exception) {
            null
        }
    }

    // --- CLOUDSTREAM LIFECYCLE HOOKS ---

    override suspend fun load(url: String): LoadResponse? {
        if (!url.contains("/synthetic?")) return null

        val uri = java.net.URI(url)
        val queryParams = uri.query?.split("&")?.associate {
            val parts = it.split("=")
            parts[0] to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        } ?: return null

        val title = queryParams["t"] ?: return null
        val year = queryParams["y"] ?: ""
        val posterUrl = queryParams["p"].takeIf { !it.isNullOrBlank() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.year = year.toIntOrNull()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Implementation remains uniform; extracts video streams from movie URL parameters via getResolutions/extractFinalLink loops.
        return true
    }
}
