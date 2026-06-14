package com.dubstamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.net.URLDecoder

class IsaidubProvider : MainAPI() {
    override var mainUrl = "https://isaidub.guru"
    override var name = "DubsTamil"
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
                    } catch (e: Exception) { }
                }
            }.awaitAll()
        }
        
        return foundAnyLinks
    }

    // Exact check translated from your Python script: is_final_download_url
    private fun isFinalDownloadUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        if (listOf(".mp4", ".mkv", ".avi", ".mov", ".webm").any { lowerUrl.endsWith(it) }) return true
        if (lowerUrl.contains("download.php?dl=")) return true
        if (listOf("dubpage.xyz", "dubmv.xyz", "dub.uptodub.ch").any { lowerUrl.contains(it) }) return true
        return false
    }

    private suspend fun crawlAllLinks(
        url: String, 
        quality: String, 
        seen: MutableSet<String>, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (seen.size > 20 || !seen.add(url)) return false
        var foundAny = false

        // Base case: Did we hit a final download URL?
        if (isFinalDownloadUrl(url)) {
            val isM3u8 = url.contains(".m3u8", ignoreCase = true)
            callback.invoke(
                newExtractorLink(
                    source = this@IsaidubProvider.name,
                    name = "${this@IsaidubProvider.name} $quality",
                    url = url,
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
            return true
        }

        try {
            // Protected by the semaphore we imported!
            val doc = scrapeSemaphore.withPermit { app.get(url, timeout = 15).document }

            // Translated from your Python: extract_download_links (Look for Download Server buttons)
            val downloadServers = doc.select("a").filter { it.text().contains("download server", ignoreCase = true) }
            
            if (downloadServers.isNotEmpty()) {
                coroutineScope {
                    downloadServers.map { server ->
                        async {
                            val serverUrl = fixUrl(server.attr("href"), url)
                            if (crawlAllLinks(serverUrl, quality, seen, callback)) {
                                foundAny = true
                            }
                        }
                    }.awaitAll()
                }
                return foundAny
            }

            // Translated from your Python: extract_isaidub_links (Look for Quality/Part Folders)
            val folderLinks = doc.select("div.f a, div.bf a")
            val validNextSteps = mutableListOf<Pair<String, String>>()

            for (link in folderLinks) {
                val href = link.attr("href")
                val text = link.text().lowercase()

                if (text.contains("sample") || href.lowercase().contains("sample")) continue
                if (href == "/" || href.contains("?get-page=") || href.contains("/category/") || href.contains("/page/")) continue

                // Filter out related movies to prevent endless loops
                val childSlug = href.trimEnd('/').substringAfterLast("/")
                val isRelatedMovie = childSlug.endsWith("-tamil-dubbed-movie") || childSlug.endsWith("-tamil-dubbed")
                if (isRelatedMovie) continue

                var newQuality = quality
                if (text.contains("1080")) newQuality = "1080p"
                else if (text.contains("720")) newQuality = "720p"
                else if (text.contains("640") || text.contains("480")) newQuality = "480p"
                else if (text.contains("360") || text.contains("320")) newQuality = "360p"
                else if (text.contains("hd") && quality == "Auto") newQuality = "HD"

                validNextSteps.add(Pair(fixUrl(href, url), newQuality))
            }

            coroutineScope {
                validNextSteps.distinctBy { it.first }.map { (nextUrl, nextQuality) ->
                    async {
                        if (crawlAllLinks(nextUrl, nextQuality, seen, callback)) {
                            foundAny = true
                        }
                    }
                }.awaitAll()
            }

        } catch (e: Exception) { }

        return foundAny
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
