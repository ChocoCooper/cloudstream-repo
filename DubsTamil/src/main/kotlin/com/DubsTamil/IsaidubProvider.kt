package com.dubstamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

        // We run sequentially to prevent Cloudstream from timing out with too many parallel connections
        for (targetUrl in urls) {
            if (targetUrl.isNotBlank()) {
                crawlForLinks(targetUrl.trim(), "Auto", mutableSetOf(), callback) { found ->
                    if (found) foundAnyLinks = true
                }
            }
        }
        
        return foundAnyLinks
    }

    /**
     * Crawls through Isaidub's nested HTML exactly as requested:
     * <div class="f"> <a> -> <div class="f"> <a> -> <div class="bf"> -> <div class="dlink">
     */
    private suspend fun crawlForLinks(
        url: String, 
        currentQuality: String, 
        seen: MutableSet<String>, 
        callback: (ExtractorLink) -> Unit,
        onFound: (Boolean) -> Unit
    ) {
        // Prevent infinite loops and crawling too many irrelevant pages
        if (seen.size > 30 || !seen.add(url)) return 
        
        try {
            val doc = app.get(url, timeout = 15).document
            
            // --- STEP 1: Check if we are at the Final Download Page ---
            // HTML Path: <body> -> <div class="container"> -> <div class="bf"> -> <div class="songinfo"> -> <div class="download"> -> <div class="dlink"> -> <a>
            val dlinks = doc.select("div.bf div.songinfo div.download div.dlink a")
            
            if (dlinks.isNotEmpty()) {
                for (dlink in dlinks) {
                    val serverUrl = fixUrl(dlink.attr("href"), url)
                    
                    // Resolve "Download Server 1" to get the final download.php or .mp4 link
                    val finalUrl = resolveServerLink(serverUrl)
                    if (finalUrl != null) {
                        val isM3u8 = finalUrl.contains(".m3u8", ignoreCase = true)
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} $currentQuality",
                                url = finalUrl,
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
                        onFound(true)
                    }
                }
                return // We found the download links, stop crawling deeper on this branch
            }
            
            // --- STEP 2: Navigate deeper into movie sub-folders ---
            // HTML Path: <body> -> <div class="container"> -> <div class="f"> -> <a href="...">
            val folders = doc.select("div.f a")
            
            for (folder in folders) {
                val href = folder.attr("href")
                val name = folder.text().lowercase()
                
                // IGNORE SAMPLES ALWAYS
                if (name.contains("sample") || href.lowercase().contains("sample")) continue
                
                // Prevent going backward to pagination or home page
                if (href == "/" || href.contains("?get-page=")) continue
                
                // Track quality based on the folder name
                var newQuality = currentQuality
                if (name.contains("1080")) newQuality = "1080p"
                else if (name.contains("720")) newQuality = "720p"
                else if (name.contains("640") || name.contains("480")) newQuality = "480p"
                else if (name.contains("360") || name.contains("320")) newQuality = "360p"
                else if (name.contains("hd") && currentQuality == "Auto") newQuality = "HD"
                else if (name.contains("bdrip") || name.contains("bluray")) newQuality = "BDRip"
                else if (name.contains("hdrip")) newQuality = "HDRip"
                else if (name.contains("original")) newQuality = "Original"

                val nextUrl = fixUrl(href, url)
                crawlForLinks(nextUrl, newQuality, seen, callback, onFound)
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Follows the "Download Server 1" link until it finds the actual download.php or .mp4 link
     */
    private suspend fun resolveServerLink(url: String, depth: Int = 0): String? {
        if (depth > 3) return null // Prevent endless redirects
        
        // If the URL is already the final download format
        if (url.contains("download.php", ignoreCase = true) || url.contains("dl.php", ignoreCase = true) || url.endsWith(".mp4", ignoreCase = true) || url.endsWith(".mkv", ignoreCase = true)) {
            return url
        }
        
        try {
            val response = app.get(url, timeout = 15)
            
            // If the server directly returned a video file
            if (response.headers["content-type"]?.contains("video/") == true) return response.url
            
            val doc = response.document
            
            // Sometimes the server link takes you to ANOTHER page with a "Download" button
            val nextBtn = doc.selectFirst("div.download div.dlink a, a:contains(Download)")
            if (nextBtn != null) {
                val nextUrl = fixUrl(nextBtn.attr("href"), url)
                if (nextUrl != url) {
                    return resolveServerLink(nextUrl, depth + 1) // Recursively follow the button
                }
            }
            
            // Fallback: Check if the raw HTML contains the download.php link
            val text = response.text
            val dlPhpMatch = Regex("""https?://[^\s"'<>]*download\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)
            if (dlPhpMatch != null) return dlPhpMatch.value
            
            val mp4Match = Regex("""https?://[^\s"'<>]*\.mp4[^"'\s]*""", RegexOption.IGNORE_CASE).find(text)
            if (mp4Match != null) return mp4Match.value
            
        } catch (e: Exception) {}
        
        return null
    }

    /**
     * Safely constructs absolute URLs whether they are relative paths or missing domains
     */
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
