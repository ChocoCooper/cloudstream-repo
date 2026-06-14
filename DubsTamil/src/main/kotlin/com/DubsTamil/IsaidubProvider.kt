package com.dubstamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

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
        // If it's a synthetic meta URL, extract parameters
        if (url.contains("/synthetic_meta?")) {
            val uri = java.net.URI(url)
            val params = uri.query?.split("&")?.associate {
                val parts = it.split("=")
                parts[0] to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            } ?: return null
            val title = params["t"] ?: return null
            val year = params["y"] ?: ""
            val poster = params["p"] ?: ""
            val synopsis = params["s"] ?: ""
            val failSafeUrl = params["url"] ?: ""

            // If failSafeUrl is present, use it directly; otherwise search
            val targetUrls = if (failSafeUrl.isNotBlank()) {
                failSafeUrl
            } else {
                val dubbedLinks = searchDubbedMovieLinks(title, year)
                if (dubbedLinks.isEmpty()) return null
                dubbedLinks.joinToString(",")
            }

            return newMovieLoadResponse(title, url, TvType.Movie, targetUrls) {
                this.posterUrl = poster.takeIf { it.isNotBlank() && it != "N/A" }
                this.year = year.toIntOrNull()
                this.plot = synopsis.takeIf { it.isNotBlank() } ?: "No synopsis available."
            }
        }

        // Otherwise, assume it's a direct movie page URL
        val doc = app.get(url).document
        val title = doc.select("div#movie-info .movie-info span").firstOrNull()?.text() ?: "Unknown"
        val yearRegex = Regex("""\b(19|20)\d{2}\b""")
        val year = yearRegex.find(title)?.value ?: ""
        
        // Extract quality links from div.f
        val qualityLinks = doc.select("div.f a").mapNotNull { a ->
            val href = a.attr("href")
            val text = a.text()
            if (href.isNotBlank() && !text.contains("sample", ignoreCase = true)) {
                fixUrl(href, url)
            } else null
        }
        
        if (qualityLinks.isEmpty()) return null
        
        return newMovieLoadResponse(title, url, TvType.Movie, qualityLinks.joinToString(",")) {
            this.year = year.toIntOrNull()
            this.plot = "No synopsis available."
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = data.split(",")
        var foundAny = false
        coroutineScope {
            urls.map { url ->
                async {
                    if (crawlAllLinks(url.trim(), "Auto", mutableSetOf(), callback)) {
                        foundAny = true
                    }
                }
            }.awaitAll()
        }
        return foundAny
    }

    private suspend fun searchDubbedMovieLinks(title: String, year: String): List<String> {
        val searchUrl = "$mainUrl/tamil-${year}-dubbed-movies/"
        val doc = app.get(searchUrl).document
        val totalPages = getTotalPages(doc)
        
        for (page in 1..totalPages) {
            val pageUrl = if (page == 1) searchUrl else "$searchUrl?get-page=$page"
            val pageDoc = app.get(pageUrl).document
            val movieLink = pageDoc.select("div.f a").firstOrNull { a ->
                val linkText = a.text()
                wordTokenMatch(title, linkText) && year in linkText
            }?.attr("href") ?: continue
            
            val moviePageUrl = fixUrl(movieLink, pageUrl)
            val movieDoc = app.get(moviePageUrl).document
            val qualityLinks = movieDoc.select("div.f a").mapNotNull { a ->
                val href = a.attr("href")
                val text = a.text()
                if (href.isNotBlank() && !text.contains("sample", ignoreCase = true)) {
                    fixUrl(href, moviePageUrl)
                } else null
            }
            if (qualityLinks.isNotEmpty()) return qualityLinks
        }
        return emptyList()
    }

    private suspend fun getTotalPages(doc: Document): Int {
        val hiddenDiv = doc.select("div[style*='display: none;']").first()
        val totalSpan = hiddenDiv?.select("span#totalPages")?.first()?.text()?.toIntOrNull()
        if (totalSpan != null) return totalSpan
        
        val paginationLinks = doc.select("div.pagination-container a")
        val maxPage = paginationLinks.mapNotNull { a ->
            Regex("get-page=(\\d+)").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
        }.maxOrNull()
        return maxPage ?: 1
    }

    private fun wordTokenMatch(title1: String, title2: String): Boolean {
        fun normalize(s: String): Set<String> {
            return s.lowercase()
                .replace(Regex("[^\\w\\s]"), "")
                .split(Regex("\\s+"))
                .filter { it.length > 2 && it !in setOf("the", "and", "of", "to", "in", "for", "on", "with", "by") }
                .toSet()
        }
        val words1 = normalize(title1)
        val words2 = normalize(title2)
        if (words1.isEmpty()) return false
        val matchCount = words1.count { it in words2 }
        return matchCount.toDouble() / words1.size >= 0.6
    }

    private suspend fun crawlAllLinks(
        url: String,
        quality: String,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (seen.size > 20 || !seen.add(url)) return false
        
        // Check if it's a final download URL
        if (isFinalDownloadUrl(url)) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name $quality",
                    url = url,
                    type = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                    referer = "$mainUrl/"
                )
            )
            return true
        }
        
        val doc = try {
            app.get(url).document
        } catch (e: Exception) {
            return false
        }
        
        // First, look for "Download Server" links (priority)
        val downloadServers = doc.select("a").filter { a ->
            a.text().contains("download server", ignoreCase = true)
        }.map { fixUrl(it.attr("href"), url) }
        
        if (downloadServers.isNotEmpty()) {
            var found = false
            for (serverUrl in downloadServers) {
                if (crawlAllLinks(serverUrl, quality, seen, callback)) {
                    found = true
                }
            }
            return found
        }
        
        // Otherwise, look for quality/folder links (div.f a)
        val folderLinks = doc.select("div.f a").mapNotNull { a ->
            val href = a.attr("href")
            val text = a.text()
            if (href.isNotBlank() && !text.contains("sample", ignoreCase = true)) {
                val newQuality = when {
                    text.contains("1080") -> "1080p"
                    text.contains("720") -> "720p"
                    text.contains("640") || text.contains("480") -> "480p"
                    text.contains("360") || text.contains("320") -> "360p"
                    text.contains("hd", ignoreCase = true) && quality == "Auto" -> "HD"
                    else -> quality
                }
                Pair(fixUrl(href, url), newQuality)
            } else null
        }
        
        var found = false
        for ((nextUrl, nextQuality) in folderLinks) {
            if (crawlAllLinks(nextUrl, nextQuality, seen, callback)) {
                found = true
            }
        }
        return found
    }

    private fun isFinalDownloadUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".mp4") ||
                lower.endsWith(".mkv") ||
                lower.endsWith(".avi") ||
                lower.contains("download.php?dl=") ||
                lower.contains("dubpage.xyz") ||
                lower.contains("dubmv.xyz") ||
                lower.contains("dub.uptodub.ch")
    }

    private fun fixUrl(href: String, baseUrl: String): String {
        return if (href.startsWith("http")) href
        else if (href.startsWith("//")) "https:$href"
        else {
            val host = runCatching { java.net.URI(baseUrl).host }.getOrNull() ?: mainUrl
            val base = "https://$host"
            if (href.startsWith("/")) "$base$href" else "$base/$href"
        }
    }
}
