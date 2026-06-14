package com.dubstamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup
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
                    // Start the unified deep crawl from the base URL
                    val finalLinks = findDownloadLinks(targetUrl.trim())
                    
                    // Deduplicate identical links found via different folder paths
                    val uniqueLinks = finalLinks.distinctBy { it.second }
                    
                    uniqueLinks.forEach { (label, url) ->
                        val isM3u8 = url.contains(".m3u8", ignoreCase = true)
                        val type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        
                        callback.invoke(
                            newExtractorLink(
                                source = this@IsaidubProvider.name,
                                name = "${this@IsaidubProvider.name} $label",
                                url = url,
                                type = type
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
            }.awaitAll()
        }
        return foundAnyLinks
    }

    // A unified, recursive deep-crawler that ignores layout changes and navigates straight to the video links
    internal suspend fun findDownloadLinks(url: String, currentLabel: String = "Auto", depth: Int = 0, seen: MutableSet<String> = mutableSetOf()): List<Pair<String, String>> {
        // Stop if we go too deep or hit a loop
        if (depth > 6 || !seen.add(url)) return emptyList()

        try {
            val res = scrapeSemaphore.withPermit { app.get(url, timeout = 15) }
            
            // If we somehow hit a raw video file right away, return it
            if (res.headers["content-type"]?.contains("video/") == true) {
                return listOf(Pair(currentLabel, res.url))
            }

            val text = res.text
            
            // Fast Regex Check for final destinations on the page
            val dlPhpMatch = Regex("""https?://[^\s"'<>]*download\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)
            val dlPhp2Match = Regex("""https?://[^\s"'<>]*dl\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)
            val m3u8Match = Regex("""https?://[^\s"'<>]*\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)
            val mp4Match = Regex("""https?://[^\s"'<>]*\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)

            if (dlPhpMatch != null) return listOf(Pair(currentLabel, dlPhpMatch.value))
            if (dlPhp2Match != null) return listOf(Pair(currentLabel, dlPhp2Match.value))
            if (m3u8Match != null) return listOf(Pair(currentLabel, m3u8Match.value))
            if (mp4Match != null) return listOf(Pair(currentLabel, mp4Match.value))

            val doc = Jsoup.parse(text)
            val validNextSteps = mutableListOf<Pair<String, String>>()

            for (a in doc.select("a[href]")) {
                val href = a.attr("href")
                val linkText = a.text().lowercase().trim()

                // Skip junk
                if (linkText.contains("sample") || href.lowercase().contains("sample")) continue
                if (href.contains("?get-page=") || href.contains("/page/") || href == "/" || href == mainUrl) continue
                if (href.contains("/category/")) continue 

                val fullUrl = when {
                    href.startsWith("http") -> href
                    href.startsWith("//") -> "https:$href"
                    else -> "https://${URI(url).host}$href"
                }
                
                if (fullUrl == url) continue // Prevent self-loop

                // Condition 1: It leads directly to a download page
                if (href.lowercase().contains("/download/") || href.lowercase().contains("/view/") || href.lowercase().contains("/file/")) {
                    validNextSteps.add(Pair(fullUrl, currentLabel))
                }
                // Condition 2: It leads deeper into a movie folder
                else if (href.contains("/movie/")) {
                    // Check if the folder looks like a resolution, part, or rip
                    val isResolutionOrPart = listOf("360", "480", "640", "720", "1080", "hd", "mp4", "part", "rip", "bluray", "dvd", "single").any { linkText.contains(it) }
                    
                    // Check if the child folder shares a naming convention with the current folder (avoids crawling "Related Movies")
                    val parentBase = url.trimEnd('/').substringAfterLast("/").replace(Regex("[^a-zA-Z0-9]"), "")
                    val childBase = fullUrl.trimEnd('/').substringAfterLast("/").replace(Regex("[^a-zA-Z0-9]"), "")
                    val isRelatedByUrl = parentBase.isNotBlank() && (childBase.contains(parentBase) || parentBase.contains(childBase) || (parentBase.length > 5 && childBase.take(8) == parentBase.take(8)))

                    if (isResolutionOrPart || isRelatedByUrl) {
                        var newLabel = currentLabel
                        if (linkText.contains("1080")) newLabel = "1080p"
                        else if (linkText.contains("720")) newLabel = "720p"
                        else if (linkText.contains("640") || linkText.contains("480")) newLabel = "480p"
                        else if (linkText.contains("360") || linkText.contains("320")) newLabel = "360p"
                        else if (linkText.contains("hd")) newLabel = "HD"

                        validNextSteps.add(Pair(fullUrl, newLabel))
                    }
                }
            }

            // Fire off all valid deep crawls in parallel to prevent timeouts
            return coroutineScope {
                validNextSteps.distinctBy { it.first }.map { (nextUrl, nextLabel) ->
                    async {
                        findDownloadLinks(nextUrl, nextLabel, depth + 1, seen)
                    }
                }.awaitAll().flatten()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }
    }
}
