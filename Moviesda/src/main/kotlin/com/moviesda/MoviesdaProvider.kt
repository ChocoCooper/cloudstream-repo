package com.moviesda

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.util.Calendar

class MoviesdaProvider : MainAPI() {
    override var mainUrl = "https://moviesda31.com"
    override var name = "Moviesda"
    override val hasMainPage = false // FIXED: Disabled to prevent NotImplementedError crashes on startup
    override var lang = "ta"
    override val supportedTypes = setOf(TvType.Movie)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cleanQuery = query.replace(Regex("\\b(19|20)\\d{2}\\b"), "").trim()
        val slug = cleanQuery.lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), "-")
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)
        
        // 1. Direct URL Guessing
        val yearsToTry = listOf(currentYear, currentYear - 1, currentYear + 1, currentYear - 2)
        for (year in yearsToTry) {
            val directUrl = "$mainUrl/$slug-$year-tamil-movie/"
            val response = app.get(directUrl, headers = mapOf("User-Agent" to userAgent))
            if (response.isSuccessful && response.text.contains("movie")) {
                results.add(newMovieSearchResponse(
                    name = "$cleanQuery ($year)", 
                    url = directUrl, 
                    type = TvType.Movie
                ))
                break 
            }
        }

        // 2. Category Browsing Fallback
        if (results.isEmpty()) {
            val categoriesToCheck = listOf(
                "$mainUrl/tamil-$currentYear-movies/",
                "$mainUrl/tamil-${currentYear - 1}-movies/"
            )

            for (categoryUrl in categoriesToCheck) {
                val doc = app.get(categoryUrl, headers = mapOf("User-Agent" to userAgent)).document
                doc.select("a[href*=-tamil-movie], a[href*=-movie/]").forEach { el ->
                    val href = el.attr("href")
                    val text = el.text().trim()
                    if (href.isNotBlank() && text.contains(cleanQuery, ignoreCase = true) && !href.contains("/tamil-movies/")) {
                        val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                        results.add(newMovieSearchResponse(
                            name = text, 
                            url = fullUrl, 
                            type = TvType.Movie
                        ))
                    }
                }
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val title = doc.selectFirst("title")?.text()?.substringBefore("-")?.trim() ?: "Unknown Movie"
        
        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        drillDownForLinks(data, callback)
        return true
    }

    private suspend fun drillDownForLinks(url: String, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        // Step 1: Check for "original" page link
        val originalLink = doc.selectFirst("a[href*=-original-movie]")?.attr("href")
        if (originalLink != null) {
            val fullOriginalUrl = if (originalLink.startsWith("http")) originalLink else "$mainUrl$originalLink"
            drillDownForLinks(fullOriginalUrl, callback)
            return
        }

        // Step 2: Check for Quality pages
        val qualityLinks = doc.select("a").filter { 
            it.text().contains(Regex("\\b(360p|480p|720p|1080p|4K)\\s*HD\\b", RegexOption.IGNORE_CASE)) 
        }
        if (qualityLinks.isNotEmpty()) {
            qualityLinks.forEach { el ->
                val href = el.attr("href")
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                drillDownForLinks(fullUrl, callback)
            }
            return
        }

        // Step 3: Check for /download/ links
        val downloadLinks = doc.select("a[href*=/download/]")
        if (downloadLinks.isNotEmpty()) {
            downloadLinks.forEach { el ->
                val href = el.attr("href")
                val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                extractFinalDownloadUrl(fullUrl, callback)
            }
        }
    }

    private suspend fun extractFinalDownloadUrl(url: String, callback: (ExtractorLink) -> Unit) {
        val doc = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
      
        doc.select("a").forEach { el ->
            val href = el.attr("href")
            val text = el.text().lowercase()

            if (href.isNotBlank() && !href.contains("moviesda15.com") && !href.startsWith("#") && 
                (text.contains("download") || text.contains("server"))) {
                
                val fullUrl = if (href.startsWith("http")) href else "https:$href"
                var targetUrl = fullUrl

                val fileIdMatch = Regex("/file/(\\d+)").find(targetUrl)
                if (targetUrl.contains("download.moviespage.xyz") && fileIdMatch != null) {
                    targetUrl = "https://play.onestream.watch/stream/page/${fileIdMatch.groupValues[1]}"
                }

                extractFromEmbed(targetUrl, callback)
            }
        }
    }

    private suspend fun extractFromEmbed(embedUrl: String, callback: (ExtractorLink) -> Unit) {
        val response = app.get(embedUrl, headers = mapOf("Referer" to mainUrl))
        val html = response.text

        // 1. Check for standard video tags
        val doc = org.jsoup.Jsoup.parse(html)
        doc.select("video source").forEach { el ->
            val src = el.attr("src")
            if (src.isNotBlank()) {
                val isM3u8 = src.contains(".m3u8")
                callback.invoke(newExtractorLink(
                    source = this.name,
                    name = "Moviesda Direct",
                    url = src,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P720.value
                })
                return
            }
        }

        // 2. Unpack Packer obfuscation if present
        val unpackedHtml = getAndUnpack(html).ifBlank { html }

        // 3. Regex fallback matching your JS patterns
        val patterns = listOf(
            Regex("[\"']hls[2-4][\"']\\s*:\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
            Regex("https?://[^\\s\"']+\\.m3u8[^\\s\"']*", RegexOption.IGNORE_CASE),
            Regex("https?://[^\\s\"']+\\.mp4[^\\s\"']*", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(unpackedHtml)
            if (match != null) {
                var videoUrl = match.groupValues.lastOrNull() ?: match.value
                videoUrl = videoUrl.replace("\\", "")
                
                if (videoUrl.contains("google.com") || videoUrl.contains("youtube.com")) continue
                
                val isM3u8 = videoUrl.contains(".m3u8")
                callback.invoke(newExtractorLink(
                    source = this.name,
                    name = "Moviesda Embed",
                    url = videoUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P720.value
                })
                break
            }
        }
    }
}
