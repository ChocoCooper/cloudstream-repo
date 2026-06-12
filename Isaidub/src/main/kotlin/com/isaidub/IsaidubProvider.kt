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
    override val hasMainPage = false
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "ta"

    private val tmdbApiKey = "1b3113663c9004682ed61086cf967c44"
    
    // TMDB Fallback array: Official -> Mirror -> Proxy
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

    private fun toSlug(text: String): String {
        return text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    }

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
            
            // STRICT CHECK: Does it exist on Isaidub?
            val isaidubUrl = findIsaidubMoviePage(title, year)
            
            if (isaidubUrl != null) {
                // Generate the NATIVE Isaidub poster directly from the verified URL slug
                val slug = isaidubUrl.trimEnd('/').substringAfterLast("/")
                val cleanSlug = slug.replace("-tamil-dubbed-movie", "").replace("-tamil-dubbed-web-series", "")
                val sitePoster = "$mainUrl/uploads/posters/$cleanSlug.jpg"

                val t = URLEncoder.encode(title, "UTF-8")
                val y = URLEncoder.encode(year, "UTF-8")
                val p = URLEncoder.encode(sitePoster, "UTF-8")
                
                val targetData = "$mainUrl/synthetic?t=$t&y=$y&p=$p"

                newMovieSearchResponse(title, targetData) {
                    this.posterUrl = sitePoster // Using Native Poster
                    this.year = year.toIntOrNull()
                }
            } else {
                null
            }
        }.filterNotNull()

        return results
    }

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
        val uri = java.net.URI(data)
        val queryParams = uri.query?.split("&")?.associate {
            val parts = it.split("=")
            parts[0] to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
        } ?: return false

        val title = queryParams["t"] ?: return false
        val year = queryParams["y"] ?: ""

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
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                            "Accept" to "*/*",
                            "Connection" to "keep-alive"
                        )
                    }
                )
            }
        }
        return true
    }

    // Intelligent Fuzzy Matcher anchored by Release Year
    private fun isFuzzyMatch(tmdbTitle: String, isaidubText: String, year: String): Boolean {
        val cleanTmdbRaw = tmdbTitle.lowercase().replace(Regex("[^a-z0-9]"), "")
        val cleanIsaidubRaw = isaidubText.lowercase().replace(Regex("[^a-z0-9]"), "")
        
        if (cleanIsaidubRaw.contains(cleanTmdbRaw) || cleanTmdbRaw.contains(cleanIsaidubRaw)) return true

        if (year.isNotBlank() && isaidubText.contains(year)) {
            val normTmdb = tmdbTitle.lowercase()
                .replace(Regex("part ii\\b"), "2")
                .replace(Regex("part iii\\b"), "3")
                .replace(Regex("part iv\\b"), "4")
                .replace("judgment", "judgement")
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("^(the|a|an)\\s+"), "")
                .trim()

            val normIsaidub = isaidubText.lowercase()
                .replace(year, "")
                .replace(Regex("[^a-z0-9\\s]"), " ")
                .replace(Regex("^(the|a|an)\\s+"), "")
                .trim()

            val tmdbTokens = normTmdb.split(Regex("\\s+")).filter { it.isNotBlank() }
            val isaidubTokens = normIsaidub.split(Regex("\\s+")).filter { it.isNotBlank() }

            if (tmdbTokens.isEmpty() || isaidubTokens.isEmpty()) return false

            val matchCount = tmdbTokens.count { isaidubTokens.contains(it) }
            val matchPercentage = matchCount.toDouble() / tmdbTokens.size

            return if (tmdbTokens.size <= 2) {
                matchPercentage == 1.0
            } else {
                val significantMatches = tmdbTokens.filter { isaidubTokens.contains(it) && it.length > 2 }
                matchPercentage >= 0.6 && significantMatches.isNotEmpty()
            }
        }
        return false
    }

    private suspend fun findIsaidubMoviePage(title: String, year: String): String? {
        val cleanTitle = title.replace(" ", "")
        
        val advancedNormalizedTitle = title.lowercase()
            .replace(Regex("part ii\\b"), "2")
            .replace(Regex("part iii\\b"), "3")
            .replace(Regex("part iv\\b"), "4")
            .replace("judgment", "judgement")
            .trim()

        val slugsToTest = listOfNotNull(
            toSlug(title).takeIf { it.isNotBlank() },
            toSlug(cleanTitle).takeIf { it.isNotBlank() },
            toSlug(advancedNormalizedTitle).takeIf { it.isNotBlank() }
        ).distinct()

        val suffixes = listOf("-tamil-dubbed-movie", "-tamil-dubbed-web-series")
        val guesses = mutableListOf<String>()

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

        // 1. FAST PATH: Test Slugs
        for (guess in guesses.distinct()) {
            val url = "$mainUrl/movie/$guess/"
            try {
                val res = app.get(url, timeout = 5)
                if (res.isSuccessful && res.code == 200) {
                    return url
                }
            } catch (e: Exception) { }
        }

        // 2. DEEP PATH: Dynamic Pagination Category Fallback
        val baseCategories = mutableListOf<String>()
        if (year.isNotBlank()) {
            baseCategories.add("$mainUrl/tamil-$year-dubbed-movies/")
        }

        val firstLetterTitle = title.lowercase().replace(Regex("^(the|a|an)\\s+"), "").replace(Regex("[^a-z0-9]"), "")
        if (firstLetterTitle.isNotEmpty() && firstLetterTitle[0].isLetter()) {
            val firstChar = firstLetterTitle[0]
            baseCategories.add("$mainUrl/tamil-atoz-dubbed-movies/$firstChar/")
        }

        for (baseCatUrl in baseCategories) {
            var currentPage = 1
            var maxPage = 1

            while (currentPage <= maxPage) {
                val url = if (currentPage == 1) baseCatUrl else "$baseCatUrl?get-page=$currentPage"
                
                try {
                    val res = app.get(url, timeout = 5).document
                    
                    // Check if movie exists on this page
                    for (a in res.select("a[href]")) {
                        val href = a.attr("href")
                        val text = a.text().trim().ifBlank { a.selectFirst("img")?.attr("alt") ?: "" }

                        if (href.contains("/movie/") || href.contains("-dubbed-movie")) {
                            val isFuzzy = isFuzzyMatch(title, text, year)
                            val isSlugMatch = slugsToTest.any { href.contains(it) }

                            if (isFuzzy || isSlugMatch) {
                                return if (href.startsWith("http")) href else "$mainUrl$href"
                            }
                        }
                    }

                    // Dynamically extract the last existing page number from the pagination block
                    if (currentPage == 1) {
                        res.select("a[href]").forEach { a ->
                            val href = a.attr("href")
                            val text = a.text().trim()
                            
                            if (href.contains("?get-page=") || href.contains("/page/")) {
                                val num = Regex("""(?:get-page=|page/)(\d+)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                                if (num != null && num > maxPage && num <= 25) { // Cap at 25 to prevent infinite loops
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
                
                currentPage++
            }
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
