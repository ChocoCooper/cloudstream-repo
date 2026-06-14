package com.dubstamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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

        for (targetUrl in urls) {
            if (targetUrl.isNotBlank()) {
                // Starts the deep crawl following your exact HTML path
                val found = crawlForLinks(targetUrl.trim(), "Auto", mutableSetOf(), callback)
                if (found) foundAnyLinks = true
            }
        }
        
        return foundAnyLinks
    }

    private suspend fun crawlForLinks(
        url: String, 
        currentQuality: String, 
        seen: MutableSet<String>, 
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Safeguard to prevent endless loops
        if (seen.size > 25 || !seen.add(url)) return false 
        var foundAny = false
        
        try {
            val response = app.get(url, timeout = 15)
            val doc = response.document
            
            // --- STEP 1: Are we at the final Download Page? ---
            // Checking: <body> -> <div class="container"> -> <div class="bf"> -> <div class="songinfo"> -> <div class="download"> -> <div class="dlink"> -> <a>
            val dlinks = doc.select("div.bf div.songinfo div.download div.dlink a")
            
            if (dlinks.isNotEmpty()) {
                for (dlink in dlinks) {
                    val serverUrl = fixUrl(dlink.attr("href"), url)
                    
                    // Trace the server button to the final mp4/php link
                    val finalUrl = resolveServerLink(serverUrl, 0, mutableSetOf())
                    if (finalUrl != null) {
                        val isM3u8 = finalUrl.contains(".m3u8", ignoreCase = true)
                        
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} $currentQuality",
                                url = finalUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                // Corrected: referer and quality are properly inside the builder block now!
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                                this.headers = mapOf(
                                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                                    "Accept" to "*/*"
                                )
                            }
                        )
                        foundAny = true
                    }
                }
                // If we found server links, we don't need to dig deeper in this branch
                if (foundAny) return true 
            }
            
            // --- STEP 2: Navigate deeper into movie folders ---
            // Checking: <body> -> <div class="container"> -> <div class="f"> -> <a>
            val folders = doc.select("div.f a, div.bf a")
            
            for (folder in folders) {
                val href = folder.attr("href")
                val text = folder.text().lowercase()
                
                // CRUCIAL: "Please ignore sample always and just go into other one(HD)"
                if (text.contains("sample") || href.lowercase().contains("sample")) continue
                
                // Ignore site navigation links to prevent crawling sideways
                if (href == "/" || href.contains("?get-page=") || href.contains("/category/") || href.contains("/page/")) continue
                if (!href.contains("/movie/") && !href.contains("/download/")) continue 
                
                // Map the quality name cleanly as it dives deeper
                var newQuality = currentQuality
                if (text.contains("1080")) newQuality = "1080p"
                else if (text.contains("720")) newQuality = "720p"
                else if (text.contains("640") || text.contains("480")) newQuality = "480p"
                else if (text.contains("360") || text.contains("320")) newQuality = "360p"
                else if (text.contains("hd") && currentQuality == "Auto") newQuality = "HD"
                else if (text.contains("bdrip") || text.contains("bluray")) newQuality = "BDRip"
                else if (text.contains("dvd") || text.contains("scr")) newQuality = "DVDSrc"
                else if (text.contains("original")) newQuality = "Original"

                val nextUrl = fixUrl(href, url)
                
                // Recursively hop to the next page
                if (crawlForLinks(nextUrl, newQuality, seen, callback)) {
                    foundAny = true
                }
            }
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return foundAny
    }

    private suspend fun resolveServerLink(url: String, depth: Int, seen: MutableSet<String>): String? {
        if (depth > 4 || !seen.add(url)) return null
        
        // Return instantly if the URL is already the final streaming target
        if (url.contains("download.php", ignoreCase = true) || url.contains("dl.php", ignoreCase = true) || url.endsWith(".mp4", ignoreCase = true) || url.endsWith(".mkv", ignoreCase = true)) {
            return url
        }
        
        try {
            val response = app.get(url, timeout = 15)
            
            // Return instantly if the server directly responds with a video content-type
            if (response.headers["content-type"]?.contains("video/") == true) return response.url
            
            val text = response.text
            
            // Check raw HTML for the download string (Extremely fast, bypasses DOM parsing)
            val dlPhpMatch = Regex("""https?://[^\s"'<>]*download\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)
            if (dlPhpMatch != null) return dlPhpMatch.value
            
            val dlPhp2Match = Regex("""https?://[^\s"'<>]*dl\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(text)
            if (dlPhp2Match != null) return dlPhp2Match.value

            val mp4Match = Regex("""https?://[^\s"'<>]*\.mp4[^"'\s]*""", RegexOption.IGNORE_CASE).find(text)
            if (mp4Match != null) return mp4Match.value
            
            // "sometimes it brings to another page rather than final download link so do this untill get the actual download link"
            val doc = response.document
            val nextBtn = doc.selectFirst("a:contains(Download), div.dlink a")
            if (nextBtn != null) {
                val nextUrl = fixUrl(nextBtn.attr("href"), url)
                if (nextUrl != url) {
                    return resolveServerLink(nextUrl, depth + 1, seen)
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
