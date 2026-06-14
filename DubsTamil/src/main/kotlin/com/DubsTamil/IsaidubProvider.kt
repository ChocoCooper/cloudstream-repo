package com.dubstamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
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
                    if (targetUrl.isNotBlank()) {
                        // Starts the highly targeted, 3-step crawler
                        if (processMoviePage(targetUrl.trim(), callback)) {
                            foundAnyLinks = true
                        }
                    }
                }
            }.awaitAll()
        }
        
        return foundAnyLinks
    }

    private fun isFinalDownloadUrl(url: String): Boolean {
        val lowerUrl = url.lowercase()
        if (listOf(".mp4", ".mkv", ".avi", ".mov", ".webm").any { lowerUrl.endsWith(it) }) return true
        if (lowerUrl.contains("download.php?dl=")) return true
        if (listOf("dubpage.xyz", "dubmv.xyz", "dub.uptodub.ch").any { lowerUrl.contains(it) }) return true
        return false
    }

    // STEP 1: Movie Page -> Extracts Quality Folders
    private suspend fun processMoviePage(url: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        try {
            val doc = app.get(url, timeout = 10).document
            
            val qualityLinks = doc.select("div.f a, div.bf a").filter {
                val text = it.text().lowercase()
                val href = it.attr("href").lowercase()
                !text.contains("sample") && !href.contains("sample") && 
                !href.endsWith("-movie/") && !href.endsWith("-movies/") && !href.endsWith("-dubbed/") &&
                !href.contains("?get-page=") && !href.contains("/page/")
            }

            coroutineScope {
                qualityLinks.map { qLink ->
                    async {
                        val qUrl = fixUrl(qLink.attr("href"), url)
                        val qName = qLink.text().lowercase()
                        
                        var quality = "Auto"
                        if (qName.contains("1080")) quality = "1080p"
                        else if (qName.contains("720")) quality = "720p"
                        else if (qName.contains("640") || qName.contains("480")) quality = "480p"
                        else if (qName.contains("360") || qName.contains("320")) quality = "360p"
                        else if (qName.contains("hd")) quality = "HD"

                        if (processQualityPage(qUrl, quality, callback)) found = true
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {}
        return found
    }

    // STEP 2: Quality Folder -> Extracts Single Part Folders
    private suspend fun processQualityPage(url: String, quality: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        try {
            val doc = app.get(url, timeout = 10).document
            
            // Check if this page directly holds the download servers
            val servers = doc.select("div.dlink a, div.download a, a").filter { 
                it.text().contains("download server", true) || (it.text().contains("download", true) && !it.text().contains("http"))
            }
            if (servers.isNotEmpty()) {
                return processDownloadPage(url, quality, callback)
            }

            // Otherwise, drill down into the file folders
            val partLinks = doc.select("div.f a, div.bf a").filter {
                val text = it.text().lowercase()
                val href = it.attr("href").lowercase()
                !text.contains("sample") && !href.contains("sample") &&
                !href.endsWith("-movie/") && !href.contains("?get-page=")
            }

            coroutineScope {
                partLinks.map { pLink ->
                    async {
                        val pUrl = fixUrl(pLink.attr("href"), url)
                        if (processDownloadPage(pUrl, quality, callback)) found = true
                    }
                }.awaitAll()
            }
        } catch (e: Exception) {}
        return found
    }

    // STEP 3: Single Part Folder -> Extracts Download Servers
    private suspend fun processDownloadPage(url: String, quality: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        try {
            val doc = app.get(url, timeout = 10).document
            val servers = doc.select("div.dlink a, div.download a, a").filter {
                val text = it.text().lowercase()
                text.contains("download server") || (text.contains("download") && !text.contains("http"))
            }

            if (servers.isNotEmpty()) {
                coroutineScope {
                    // Only process the first 2 servers to save time and prevent timeouts
                    servers.take(2).map { sLink ->
                        async {
                            val sUrl = fixUrl(sLink.attr("href"), url)
                            val finalUrl = resolveServerLink(sUrl)
                            if (finalUrl != null) {
                                val isM3u8 = finalUrl.contains(".m3u8", ignoreCase = true)
                                
                                // FIXED: Properties are now correctly placed inside the block!
                                callback.invoke(
                                    newExtractorLink(
                                        source = this@IsaidubProvider.name,
                                        name = "${this@IsaidubProvider.name} $quality",
                                        url = finalUrl,
                                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "$mainUrl/"
                                        this.quality = Qualities.Unknown.value
                                        this.headers = mapOf(
                                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                            "Accept" to "*/*"
                                        )
                                    }
                                )
                                found = true
                            }
                        }
                    }.awaitAll()
                }
            } else {
                // Extreme Failsafe: if there's one more unexpected layer, attempt to click it
                val fallback = doc.select("div.f a, div.bf a").firstOrNull { 
                    !it.text().lowercase().contains("sample") 
                }
                if (fallback != null) {
                    val fallbackUrl = fixUrl(fallback.attr("href"), url)
                    val finalUrl = resolveServerLink(fallbackUrl)
                    if (finalUrl != null) {
                        val isM3u8 = finalUrl.contains(".m3u8", ignoreCase = true)
                        callback.invoke(
                            newExtractorLink(
                                source = this@IsaidubProvider.name,
                                name = "${this@IsaidubProvider.name} $quality",
                                url = finalUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                    "Accept" to "*/*"
                                )
                            }
                        )
                        found = true
                    }
                }
            }
        } catch (e: Exception) {}
        return found
    }

    private suspend fun resolveServerLink(url: String, depth: Int = 0): String? {
        if (depth > 2) return null
        if (isFinalDownloadUrl(url)) return url
        
        try {
            val response = app.get(url, timeout = 5)
            if (response.headers["content-type"]?.contains("video/") == true) return response.url
            
            // Ultra-fast regex bypass for the final download link
            val text = response.text
            val directMatch = Regex("""https?://[^\s"'<>]*download\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)
                ?: Regex("""https?://[^\s"'<>]*dl\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)
                ?: Regex("""https?://[^\s"'<>]*\.mp4[^"'\s]*""", RegexOption.IGNORE_CASE).find(text)
                
            if (directMatch != null) return directMatch.value
            
            // If regex failed, click the next Download button
            val doc = response.document
            val nextServers = doc.select("a").filter { it.text().contains("Download", true) || it.parent()?.hasClass("dlink") == true }
            for (next in nextServers) {
                val nextUrl = fixUrl(next.attr("href"), url)
                if (nextUrl != url) {
                    val resolved = resolveServerLink(nextUrl, depth + 1)
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
