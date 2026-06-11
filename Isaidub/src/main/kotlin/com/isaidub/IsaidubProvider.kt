package com.isaidub // Adjust package name to match your repository

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

class IsaidubProvider : MainAPI() {
    override var mainUrl = "https://isaidub.guru"
    override var name = "Isaidub"
    override val hasMainPage = false // Keeps the homescreen empty
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "ta"

    private val tmdbApiKey = "1b3113663c9004682ed61086cf967c44"
    
    // TMDB Fallback array: Official -> Mirror -> Proxy
    private val tmdbUrls = listOf(
        "https://api.themoviedb.org/3",
        "https://api.tmdb.org/3",
        "https://tmdb-proxy.cubecity.cloud/3"
    )

    // Data classes for TMDB JSON parsing
    data class TmdbSearchResponse(val results: List<TmdbResult>?)
    data class TmdbResult(
        val title: String?,
        val name: String?,
        val release_date: String?,
        val poster_path: String?
    )

    private fun toSlug(text: String): String {
        return text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

    override suspend fun search(query: String): List<SearchResponse> {
        var tmdbJson: NiceResponse? = null
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        // Try TMDB endpoints in order until one succeeds
        for (baseUrl in tmdbUrls) {
            try {
                val url = "$baseUrl/search/movie?api_key=$tmdbApiKey&query=$encodedQuery"
                val response = app.get(url, timeout = 5)
                if (response.isSuccessful && response.text.contains("results")) {
                    tmdbJson = response
                    break
                }
            } catch (e: Exception) {
                // Ignore and try the next mirror
            }
        }

        if (tmdbJson == null) return emptyList()

        val results = arrayListOf<SearchResponse>()
        val parsed = AppUtils.parseJson<TmdbSearchResponse>(tmdbJson.text)

        parsed.results?.forEach { item ->
            val title = item.title ?: item.name ?: return@forEach
            val year = item.release_date?.split("-")?.firstOrNull() ?: ""
            val posterUrl = if (item.poster_path != null) "https://image.tmdb.org/t/p/w500${item.poster_path}" else ""

            // Pack the TMDB data into a custom string so load() and loadLinks() can use it
            val targetData = "$title||$year||$posterUrl"

            results.add(newMovieSearchResponse(title, targetData) {
                this.posterUrl = posterUrl
                this.year = year.toIntOrNull()
            })
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("||")
        if (parts.size < 2) return null
        
        val title = parts[0]
        val year = parts[1]
        val posterUrl = if (parts.size > 2 && parts[2].isNotBlank()) parts[2] else null

        // Pass the packed data straight to loadLinks via the url parameter
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
        val parts = data.split("||")
        val title = parts[0]
        val year = parts[1]

        val targetUrl = findIsaidubMoviePage(title, year) ?: return false
        val resolutions = getResolutions(targetUrl)

        resolutions.forEach { res ->
            val finalLink = extractFinalLink(res.url, 0, mutableSetOf())
            if (finalLink != null) {
                val isM3u8 = finalLink.contains(".m3u8", ignoreCase = true)
                
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = res.label,
                        url = finalLink,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    )
                )
            }
        }
        return true
    }

    private suspend fun findIsaidubMoviePage(title: String, year: String): String? {
        val cleanTitle = title.replace(" ", "")
        val slugsToTest = listOfNotNull(
            toSlug(title).takeIf { it.isNotBlank() },
            toSlug(cleanTitle).takeIf { it.isNotBlank() }
        ).distinct()

        val suffixes = listOf("-tamil-dubbed-movie", "-tamil-dubbed-web-series")
        val guesses = mutableListOf<String>()

        // 1. Slug Guessing Generation
        slugsToTest.forEach { s ->
            suffixes.forEach { suffix ->
                if (year.isNotBlank()) {
                    guesses.add("$s-$year$suffix")
                    guesses.add("$s-$year-720p-hd$suffix")
                }
                guesses.add("$s$suffix")
                guesses.add("$s-720p-hd$suffix")
            }
        }

        // Test Slugs
        for (guess in guesses.distinct()) {
            val url = "$mainUrl/movie/$guess/"
            try {
                val res = app.get(url, timeout = 5)
                if (res.isSuccessful && res.code == 200) {
                    return url
                }
            } catch (e: Exception) { }
        }

        // 2. Category Fallback
        val categories = mutableListOf("$mainUrl/")
        if (year.isNotBlank()) categories.add("$mainUrl/tamil-$year-dubbed-movies/")

        val cleanLetters = title.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (cleanLetters.isNotEmpty() && cleanLetters[0].isLetter()) {
            val firstChar = cleanLetters[0]
            categories.add("$mainUrl/tamil-atoz-dubbed-movies/$firstChar/")
            categories.add("$mainUrl/tamil-atoz-dubbed-movies/$firstChar/2/")
        }

        for (catUrl in categories) {
            try {
                val res = app.get(catUrl, timeout = 5).document
                for (a in res.select("a[href]")) {
                    val href = a.attr("href")
                    val text = a.text().trim().ifBlank { a.selectFirst("img")?.attr("alt") ?: "" }

                    if (href.contains("/movie/") || href.contains("-dubbed-movie")) {
                        val normalizedText = text.lowercase().replace(" ", "")
                        val match = slugsToTest.any { href.contains(it) || normalizedText.contains(it) }
                        if (match) {
                            return if (href.startsWith("http")) href else "$mainUrl$href"
                        }
                    }
                }
            } catch (e: Exception) { }
        }
        return null
    }

    data class ResolutionNode(val label: String, val url: String)

    private suspend fun getResolutions(pageUrl: String, depth: Int = 0, maxDepth: Int = 2): List<ResolutionNode> {
        if (depth > maxDepth) return emptyList()

        val foundResolutions = mutableListOf<ResolutionNode>()
        val folderPages = mutableListOf<String>()

        try {
            val doc = app.get(pageUrl, timeout = 8).document
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

            // If we hit an intermediate folder (like "Original") instead of files, crawl deeper
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
            val res = app.get(url, timeout = 8)
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
