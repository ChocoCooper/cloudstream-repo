package com.dubstamil

import android.util.Log
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

    private val tag = "Isaidub"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return getSharedHomePageData(page, request)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return getSharedSearchData(query)
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d(tag, "load called with url: $url")
        if (!url.contains("/synthetic_meta?")) {
            // This is a direct movie page URL
            val rawName = url.trimEnd('/').substringAfterLast("/").replace("-", " ").replace(Regex("tamil.*", RegexOption.IGNORE_CASE), "").trim()
            val (omdbMatch, resolvedYear) = fetchOmdbMetadata(rawName)

            // Scrape quality links from the movie page
            val qualityUrls = getQualityLinks(url)
            if (qualityUrls.isEmpty()) {
                Log.e(tag, "No quality links found on movie page: $url")
                return null
            }
            // Join quality URLs with comma for CloudStream to present quality selection
            val qualityList = qualityUrls.joinToString(",")
            Log.d(tag, "Quality URLs: $qualityList")

            return newMovieLoadResponse(omdbMatch?.Title ?: rawName, url, TvType.Movie, qualityList) {
                this.posterUrl = omdbMatch?.Poster?.takeIf { it != "N/A" }
                this.year = resolvedYear.toIntOrNull()
                this.plot = omdbMatch?.Plot?.takeIf { it != "N/A" } ?: "No synopsis available."
            }
        }

        // Synthetic meta URL handling (for search results)
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

    private suspend fun getQualityLinks(moviePageUrl: String): List<String> {
        Log.d(tag, "getQualityLinks for $moviePageUrl")
        try {
            val doc = scrapeSemaphore.withPermit { app.get(moviePageUrl, timeout = 15).document }
            val qualityLinks = mutableListOf<String>()
            for (div in doc.select("div.f")) {
                val a = div.selectFirst("a")
                if (a != null) {
                    val text = a.text()
                    if (text.contains("sample", ignoreCase = true)) continue
                    val href = a.attr("href")
                    val fullUrl = fixUrl(href, moviePageUrl)
                    qualityLinks.add(fullUrl)
                    Log.d(tag, "Found quality: $text -> $fullUrl")
                }
            }
            return qualityLinks
        } catch (e: Exception) {
            Log.e(tag, "Error getting quality links", e)
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(tag, "loadLinks called with data: $data")
        val urls = data.split(",")
        var foundAnyLinks = false

        coroutineScope {
            urls.map { targetUrl ->
                async {
                    try {
                        if (targetUrl.isNotBlank()) {
                            Log.d(tag, "Starting crawl for: $targetUrl")
                            if (crawlAllLinks(targetUrl.trim(), "Auto", mutableSetOf(), callback)) {
                                foundAnyLinks = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(tag, "Error crawling $targetUrl", e)
                    }
                }
            }.awaitAll()
        }

        Log.d(tag, "loadLinks finished, foundAnyLinks=$foundAnyLinks")
        return foundAnyLinks
    }

    private fun isFinalDownloadUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        if (listOf(".mp4", ".mkv", ".avi", ".mov", ".webm").any { lowerUrl.endsWith(it) }) {
            Log.d(tag, "Final URL (video file): $url")
            return true
        }
        if (lowerUrl.contains("download.php?dl=")) {
            Log.d(tag, "Final URL (download.php): $url")
            return true
        }
        if (listOf("dubpage.xyz", "dubmv.xyz", "dub.uptodub.ch").any { lowerUrl.contains(it) }) {
            Log.d(tag, "External download page: $url")
            return false // Not final, these are intermediate
        }
        return false
    }

    private suspend fun crawlAllLinks(
        url: String,
        quality: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (seen.size > 20 || !seen.add(url)) {
            Log.d(tag, "Crawl stopping: seen size ${seen.size}, already visited $url")
            return false
        }
        Log.d(tag, "Crawling: $url (quality=$quality)")

        // Check if we have a final video URL
        if (isFinalDownloadUrl(url)) {
            val isM3u8 = url.contains(".m3u8", ignoreCase = true)
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "$name $quality",
                    url = url,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.headers = mapOf(
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                        "Accept" to "*/*",
                        "Referer" to mainUrl
                    )
                }
            )
            return true
        }

        try {
            val doc = scrapeSemaphore.withPermit { app.get(url, timeout = 15).document }

            // 1. Look for "Download Server" links (priority)
            val downloadServers = doc.select("a").filter { 
                it.text().contains("download server", ignoreCase = true) 
            }
            if (downloadServers.isNotEmpty()) {
                Log.d(tag, "Found ${downloadServers.size} download server links")
                var found = false
                coroutineScope {
                    downloadServers.map { server ->
                        async {
                            val serverUrl = fixUrl(server.attr("href"), url)
                            Log.d(tag, "Following download server: ${server.text()} -> $serverUrl")
                            if (crawlAllLinks(serverUrl, quality, seen, callback)) {
                                found = true
                            }
                        }
                    }.awaitAll()
                }
                if (found) return true
            }

            // 2. Otherwise, extract all folder/quality links from div.f or div.bf
            val folderLinks = doc.select("div.f a, div.bf a")
            val validNextSteps = mutableListOf<Pair<String, String>>()

            for (link in folderLinks) {
                val href = link.attr("href")
                val text = link.text().lowercase()

                if (text.contains("sample") || href.lowercase().contains("sample")) continue
                if (href == "/" || href.contains("?get-page=") || href.contains("/category/") || href.contains("/page/")) continue

                val childSlug = href.trimEnd('/').substringAfterLast("/")
                val isRelatedMovie = childSlug.endsWith("-tamil-dubbed-movie") || childSlug.endsWith("-tamil-dubbed")
                if (isRelatedMovie) continue

                var newQuality = quality
                when {
                    text.contains("1080") -> newQuality = "1080p"
                    text.contains("720") -> newQuality = "720p"
                    text.contains("640") || text.contains("480") -> newQuality = "480p"
                    text.contains("360") || text.contains("320") -> newQuality = "360p"
                    text.contains("hd") && quality == "Auto" -> newQuality = "HD"
                }

                val fullUrl = fixUrl(href, url)
                validNextSteps.add(Pair(fullUrl, newQuality))
                Log.d(tag, "Found folder link: ${link.text()} -> $fullUrl (quality=$newQuality)")
            }

            if (validNextSteps.isNotEmpty()) {
                var found = false
                coroutineScope {
                    validNextSteps.distinctBy { it.first }.map { (nextUrl, nextQuality) ->
                        async {
                            if (crawlAllLinks(nextUrl, nextQuality, seen, callback)) {
                                found = true
                            }
                        }
                    }.awaitAll()
                }
                return found
            }

            Log.d(tag, "No further links found on $url")
        } catch (e: Exception) {
            Log.e(tag, "Error crawling $url", e)
        }

        return false
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
