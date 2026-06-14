package com.dubstamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.net.URLDecoder

class IsaidubProvider : MainAPI() {
    override var mainUrl = "https://isaidub.guru"
    override var name = "Isaidub"
    override val hasMainPage = true 
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "ta"

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
        var plotSynopsis = queryParams["s"] ?: "" 
        val yearInt = year.toIntOrNull()

        val targetUrls = if (!failSafeUrl.isNullOrBlank()) {
            failSafeUrl
        } else {
            val dubbedLinks = searchDubbedMovieLinks(title, year)
            if (dubbedLinks.isEmpty()) return null
            dubbedLinks.joinToString(",")
        }

        if (plotSynopsis.isBlank() || plotSynopsis == "No synopsis available.") {
            val (detailedMeta, _) = fetchOmdbMetadata(title, year)
            plotSynopsis = detailedMeta?.Plot?.takeIf { it != "N/A" } ?: "No synopsis available."
        }

        return newMovieLoadResponse(title, url, TvType.Movie, targetUrls) {
            this.posterUrl = omdbPoster
            this.year = yearInt
            this.plot = plotSynopsis 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = data.split(",")
        var foundAnyLinks = false

        coroutineScope {
            urls.map { targetUrl ->
                async {
                    try {
                        if (targetUrl.isNotBlank()) {
                            // Starts the safe, parallel deep crawl
                            if (crawlAllLinks(targetUrl.trim(), "Auto", mutableSetOf(), callback)) {
                                foundAnyLinks = true
                            }
                        }
                    } catch (e: Exception) {
                        // Prevents Cloudstream's JobCancellationException crash
                    }
                }
            }.awaitAll()
        }
        
        return foundAnyLinks
    }

    private suspend fun crawlAllLinks(
        url: String, 
        quality: String, 
        seen: MutableSet<String>, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Prevent infinite loops across nested directories
        if (!seen.add(url) || seen.size > 50) return false
        var foundAny = false

        try {
            // Short timeout so dead ends don't stall the whole search
            val doc = app.get(url, timeout = 10).document

            // --- STEP 1: Look for Download Servers (Following your exact HTML path) ---
            val downloadServers = doc.select("div.dlink a")
            if (downloadServers.isNotEmpty()) {
                coroutineScope {
                    downloadServers.map { server ->
                        async {
                            try {
                                val serverUrl = fixUrl(server.attr("href"), url)
                                val resolvedUrl = resolveServerLink(serverUrl, 0, mutableSetOf())
                                
                                if (resolvedUrl != null) {
                                    val isM3u8 = resolvedUrl.contains(".m3u8", ignoreCase = true)
                                    callback.invoke(
                                        newExtractorLink(
                                            source = this@IsaidubProvider.name,
                                            name = "${this@IsaidubProvider.name} $quality",
                                            url = resolvedUrl,
                                            referer = "$mainUrl/",
                                            quality = Qualities.Unknown.value,
                                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.headers = mapOf(
                                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                                "Accept" to "*/*"
                                            )
                                        }
                                    )
                                    foundAny = true
                                }
                            } catch (e: Exception) {}
                        }
                    }.awaitAll()
                }
                return foundAny // Stop crawling deeper in this branch
            }

            // --- STEP 2: Drill down into resolution/quality/part folders ---
            val folderLinks = doc.select("div.f a, div.bf a")
            
            // Get the base slug to ensure we don't accidentally crawl "Related Movies"
            val baseSlug = url.trimEnd('/').substringAfterLast("/")
                .replace(Regex("-tamil-dubbed-movie.*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("-\\d{4}.*"), "")
                .replace(Regex("-\\(\\d{4}\\).*"), "")
                .replace(Regex("-movie.*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("-part.*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("-hd.*", RegexOption.IGNORE_CASE), "")

            val validNextSteps = mutableListOf<Pair<String, String>>()

            for (link in folderLinks) {
                val href = link.attr("href")
                val text = link.text().lowercase()

                // "Please ignore sample always and just go into other one(HD)"
                if (text.contains("sample") || href.lowercase().contains("sample")) continue
                
                // Prevent going backward or sideways into irrelevant directories
                if (href == "/" || href.contains("?get-page=") || href.contains("/category/") || href.contains("/page/")) continue
                if (!href.contains("/movie/") && !href.contains("/download/") && !href.contains("/view/")) continue

                // Check if the link leads to a valid sub-folder type or belongs to the current movie
                val isQualityFolder = listOf("1080", "720", "640", "480", "360", "320", "hd", "mp4", "original", "part", "bdrip", "bluray", "hdrip", "dvdscr", "scr", "single").any { text.contains(it) }
                val isRelated = baseSlug.length > 2 && href.contains(baseSlug, ignoreCase = true)

                if (isQualityFolder || isRelated) {
                    var newQuality = quality
                    if (text.contains("1080")) newQuality = "1080p"
                    else if (text.contains("720")) newQuality = "720p"
                    else if (text.contains("640") || text.contains("480")) newQuality = "480p"
                    else if (text.contains("360") || text.contains("320")) newQuality = "360p"
                    else if (text.contains("hd") && quality == "Auto") newQuality = "HD"

                    validNextSteps.add(Pair(fixUrl(href, url), newQuality))
                }
            }

            // Fire off the valid steps in parallel (Protected by try/catch)
            coroutineScope {
                validNextSteps.distinctBy { it.first }.map { (nextUrl, nextQuality) ->
                    async {
                        try {
                            if (crawlAllLinks(nextUrl, nextQuality, seen, callback)) {
                                foundAny = true
                            }
                        } catch (e: Exception) {}
                    }
                }.awaitAll()
            }

        } catch (e: Exception) {} // Suppress timeout errors gracefully so other threads can finish

        return foundAny
    }

    private suspend fun resolveServerLink(url: String, depth: Int, seen: MutableSet<String>): String? {
        if (depth > 5 || !seen.add(url)) return null

        // Return instantly if the URL is already the final streaming target
        if (url.contains("download.php", ignoreCase = true) || url.contains("dl.php", ignoreCase = true) || url.endsWith(".mp4", ignoreCase = true) || url.endsWith(".mkv", ignoreCase = true)) {
            return url
        }

        try {
            val response = app.get(url, timeout = 10)
            
            // Return instantly if the server directly responds with a video
            if (response.headers["content-type"]?.contains("video/") == true) return response.url

            val doc = response.document
            val nextServerLinks = doc.select("a:contains(Download), div.dlink a")
            
            // Prioritize finding the PHP/MP4 link in raw text (extremely fast)
            val text = response.text
            val dlPhpMatch = Regex("""https?://[^\s"'<>]*download\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)
            if (dlPhpMatch != null) return dlPhpMatch.value

            val dlPhp2Match = Regex("""https?://[^\s"'<>]*dl\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)
            if (dlPhp2Match != null) return dlPhp2Match.value

            val mp4Match = Regex("""https?://[^\s"'<>]*\.mp4[^"'\s]*""", RegexOption.IGNORE_CASE).find(text)
            if (mp4Match != null) return mp4Match.value
            
            // If not found in text, follow the next button recursively
            for (next in nextServerLinks) {
                val nextHref = next.attr("href")
                val nextUrl = fixUrl(nextHref, url)
                if (nextUrl != url) {
                    val resolved = resolveServerLink(nextUrl, depth + 1, seen)
                    if (resolved != null) return resolved
                }
            }
        } catch (e: Exception) {}
        
        return null
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
