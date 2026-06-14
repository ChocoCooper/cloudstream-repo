package com.dubstamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

    // --- REVERTED TO OLD WORKING CRAWLING LOGIC EXACTLY ---

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
                            name = "${this.name} (Auto)",
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
                                name = "${this.name} $qualityName",
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

    internal suspend fun getResolutions(pageUrl: String, depth: Int = 0, maxDepth: Int = 3): List<ResolutionNode> {
        if (depth > maxDepth) return emptyList()

        val foundResolutions = mutableListOf<ResolutionNode>()
        val folderPages = mutableListOf<String>()

        try {
            val doc = scrapeSemaphore.withPermit { app.get(pageUrl, timeout = 15).document }
            
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

    internal suspend fun extractFinalLink(url: String, depth: Int, seen: MutableSet<String>): String? {
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
