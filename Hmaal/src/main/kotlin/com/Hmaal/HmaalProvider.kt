package com.Hmaal

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.util.concurrent.ConcurrentHashMap

class HmaalProvider : MainAPI() {
    override var mainUrl = "https://hotott.net"
    override var name = "Hmaal"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val TAG = "HmaalProvider"

    // All mirror sites share the exact same theme/markup structure.
    private val mirrorDomains = listOf(
        "https://hmaal.tv",
        "https://hdmaal.io",
        "https://hotmaal.xxx",
        "https://ottdude.com",
        "https://serieswala.com",
        "https://ottzone.net",
        "https://hotott.net",
        "https://botmaal.io",
        "https://maaltv.io"
    )

    // hotott.net has been observed serving stale/dead links from an old R2 bucket
    // (pub-*.r2.dev) instead of the live video.maalcdn.com / cdn.pmaal.com CDN that
    // every other mirror uses. We still scrape it (it's the primary domain and drives
    // the main page/search), but we never trust its video links without validating them.
    private val knownUnreliableCdnHosts = listOf("r2.dev")

    // Caches listing posters (homepage/search) keyed by path slug so load() reuses them.
    private val posterCache = ConcurrentHashMap<String, String>()

    // ---------- helpers ----------

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // Matches the UA your working cURLs used for the actual CDN byte-range requests.
    private val mobileUserAgent =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to desktopUserAgent,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    /** Encodes spaces and unescaped characters in video paths to prevent 404/URISyntax errors on CDNs */
    private fun sanitizeUrl(url: String): String {
        if (url.isBlank()) return url
        var cleaned = url.trim()
        cleaned = cleaned.replace(" ", "%20")
        return cleaned
    }

    private fun fixUrl(url: String, domain: String): String {
        val trimmed = url.trim()
        val fullUrl = when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> "${domain.trimEnd('/')}/${trimmed.trimStart('/')}"
        }
        return sanitizeUrl(fullUrl)
    }

    private fun fixUrlNull(url: String?, domain: String): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url, domain)
    }

    private fun cleanTitle(title: String?): String {
        if (title.isNullOrBlank()) return "Unknown"
        var clean = title.trim()

        clean = org.jsoup.parser.Parser.unescapeEntities(clean, false)
        clean = clean.replace(Regex("""(?i)^Watch\s+"""), "")
        clean = clean.replace(Regex("""(?i)\s*\b(web\s*series|online|full\s*movie|hd|all\s*episodes?|hindi)\b"""), "")
        clean = clean.replace(
            Regex("""(?i)\s*[-|–—:»>]\s*(?:watch|hotott|botmaal|ottdude|serieswala|maaltv|ymaal|zmaal|hdmaal|hotmaal|ottzone|newmaal|hmaal)(?:\.\w+)?.*$"""),
            ""
        )
        clean = clean.replace(Regex("""(?i)\s*»\s*.*$"""), "")
        clean = clean.replace(Regex("""(?i)\s*>\s*.*$"""), "")
        clean = clean.trim(' ', '-', '|', ':', '»', '>', '–', '—', '.').trim()
        clean = clean.replace(Regex("""\s+"""), " ")

        return if (clean.isNotBlank()) clean else title.trim()
    }

    private fun getSiteName(pageUrl: String): String {
        return try {
            val host = URI(pageUrl).host?.removePrefix("www.") ?: ""
            host.substringBefore(".")
        } catch (e: Exception) {
            "mirror"
        }
    }

    private fun getPath(url: String): String {
        return try {
            val path = URI(url).rawPath ?: ""
            path.trimEnd('/')
        } catch (e: Exception) {
            url
        }
    }

    private fun originOf(url: String): String {
        return try {
            val u = URI(url)
            if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else mainUrl
        } catch (e: Exception) {
            mainUrl
        }
    }

    private fun refererHeaders(url: String?): Map<String, String> {
        val origin = if (!url.isNullOrBlank()) originOf(url) else mainUrl
        return mapOf(
            "User-Agent" to desktopUserAgent,
            "Referer" to "$origin/"
        )
    }

    private fun Document.selectVideoElements(): List<Element> {
        val elements = this.select("#primary > div.videos > a")
        return if (elements.isNotEmpty()) elements else this.select("#primary > div > a")
    }

    private fun Document.extractVideoSources(): List<String> {
        val urls = linkedSetOf<String>()

        val videoSelectors = listOf(
            "#my-video source",
            "#my-video",
            "video source",
            "video",
            ".content > div.video-container > div > div.xplayer-lazy-source",
            ".content > div.video-container > div > div",
            ".xplayer-lazy-source"
        )

        this.select(videoSelectors.joinToString(", ")).forEach { el ->
            val src = el.attr("vid-src")
                .ifBlank { el.attr("src") }
                .ifBlank { el.attr("data-src") }
                .ifBlank { el.attr("data-video-src") }
                .trim()
            if (src.isNotBlank() && !src.startsWith("blob:") && !src.startsWith("javascript:")) {
                urls.add(src)
            }
        }

        this.select("iframe").forEach { el ->
            val src = el.attr("src")
                .ifBlank { el.attr("vid-src") }
                .ifBlank { el.attr("data-src") }
                .ifBlank { el.attr("data-lazy-src") }
                .trim()
            if (src.isNotBlank() && !src.startsWith("about:") && !src.startsWith("javascript:")) {
                urls.add(src)
            }
        }

        return urls.toList()
    }

    private fun Element.extractBackgroundImage(): String? {
        val style = this.attr("style").ifBlank { this.attr("data-style") }
        val match = Regex("""url\(\s*['"]?(.*?)['"]?\s*\)""", RegexOption.IGNORE_CASE).find(style)
        val url = match?.groupValues?.get(1)?.trim()?.ifBlank { null }
        if (url != null) return url

        return this.attr("data-src").ifBlank {
            this.attr("data-bg").ifBlank {
                this.attr("data-background")
            }
        }.trim().ifBlank { null }
    }

    private fun Element.extractPoster(domain: String): String? {
        val bg = this.extractBackgroundImage()
        val src = if (bg.isNullOrBlank()) {
            this.selectFirst("img")?.attr("src")?.ifBlank { this.selectFirst("img")?.attr("data-src") }
        } else {
            bg
        }
        return src?.trim()?.ifBlank { null }?.let { fixUrlNull(it, domain) }
    }

    private fun Element.toSearchResult(domain: String): SearchResponse? {
        val rawTitle = this.attr("title").ifBlank { this.text() }.trim()
        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null
        val href = fixUrlNull(this.attr("href"), domain) ?: return null
        val posterUrl = this.extractPoster(domain)

        if (posterUrl != null) {
            posterCache[getPath(href)] = posterUrl
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = refererHeaders(posterUrl)
        }
    }

    // ---------- homepage ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url, headers = browserHeaders).document

        val items = document.selectVideoElements().mapNotNull { it.toSearchResult(mainUrl) }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext = items.isNotEmpty()
        )
    }

    // ---------- search (all mirrors, deduplicated) ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        coroutineScope {
            mirrorDomains.map { domain ->
                async {
                    try {
                        val doc = app.get("$domain/?s=$query", headers = browserHeaders).document
                        val items = doc.selectVideoElements().mapNotNull { it.toSearchResult(domain) }
                        synchronized(results) { results.addAll(items) }
                    } catch (e: Exception) {
                        Log.w(TAG, "search: mirror $domain failed: ${e.message}")
                    }
                }
            }.awaitAll()
        }

        return results.distinctBy { it.name.trim().lowercase() }
    }

    // ---------- load (single movie/video page ONLY) ----------

    override suspend fun load(url: String): LoadResponse {
        val pathKey = getPath(url)
        val cachedPoster = posterCache[pathKey]
        val currentDomain = originOf(url)

        val document = app.get(url, headers = browserHeaders).document

        val rawTitle = document.selectFirst("h1.page-title, h1.title, h1")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()?.ifBlank { null }
            ?: document.selectFirst("title")?.text()?.trim()
            ?: "Unknown"

        val title = cleanTitle(rawTitle)

        val poster = cachedPoster
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it, currentDomain) }
            ?: document.selectFirst("a.video")?.extractPoster(currentDomain)

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = refererHeaders(poster)
        }
    }

    // ---------- links (tries the same page path across ALL mirrors) ----------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false
        val processedUrls = ConcurrentHashMap.newKeySet<String>()

        val path = try {
            URI(data).let { (it.rawPath ?: "") + (it.rawQuery?.let { q -> "?$q" } ?: "") }
        } catch (e: Exception) {
            null
        }

        val candidateUrls = if (!path.isNullOrBlank()) {
            mirrorDomains.map { it.trimEnd('/') + path }.distinct()
        } else {
            listOf(data)
        }

        coroutineScope {
            candidateUrls.map { pageUrl ->
                async {
                    val domainLabel = getSiteName(pageUrl)
                    try {
                        val currentDomain = originOf(pageUrl)
                        val doc = app.get(pageUrl, headers = browserHeaders).document
                        val rawSources = doc.extractVideoSources()
                        Log.d(TAG, "loadLinks: [$domainLabel] page fetched, ${rawSources.size} raw source(s) found")
                        if (rawSources.isEmpty()) return@async

                        for (rawUrl in rawSources) {
                            val fullUrl = fixUrl(rawUrl, currentDomain)
                            if (!processedUrls.add(fullUrl)) continue

                            val extractorLoaded = loadExtractor(fullUrl, pageUrl, subtitleCallback, callback)
                            if (extractorLoaded) {
                                found = true
                                continue
                            }

                            if (fullUrl.contains("/embed/") || fullUrl.contains("/player/") || rawUrl.contains("iframe") || fullUrl.endsWith(".html") || fullUrl.endsWith(".php")) {
                                try {
                                    val iframeDoc = app.get(fullUrl, headers = browserHeaders + mapOf("Referer" to pageUrl)).document
                                    val innerSources = iframeDoc.extractVideoSources()
                                    for (innerRaw in innerSources) {
                                        val innerFull = fixUrl(innerRaw, originOf(fullUrl))
                                        if (processedUrls.add(innerFull)) {
                                            if (!loadExtractor(innerFull, fullUrl, subtitleCallback, callback)) {
                                                if (emitVideoLinkIfAlive(innerFull, pageUrl, domainLabel, callback)) {
                                                    found = true
                                                }
                                            } else {
                                                found = true
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "loadLinks: [$domainLabel] iframe fetch failed: ${e.message}")
                                }
                            } else {
                                // Direct video file (.mp4, .m3u8, etc.) — validate before trusting it.
                                if (emitVideoLinkIfAlive(fullUrl, pageUrl, domainLabel, callback)) {
                                    found = true
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "loadLinks: [$domainLabel] mirror unreachable/skip: ${e.message}")
                    }
                }
            }.awaitAll()
        }

        Log.d(TAG, "loadLinks: finished, found=$found, validatedLinks=${processedUrls.size}")
        return found
    }

    /**
     * Checks a candidate video URL with a cheap ranged GET before handing it to the player.
     * This stops known-dead sources (e.g. hotott.net's stale r2.dev bucket links) from ever
     * reaching ExoPlayer, so CloudStream falls back to whichever mirror actually has a live link
     * instead of surfacing a single 404.
     */
    private suspend fun isLinkAlive(url: String, referer: String): Boolean {
        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to mobileUserAgent,
                    "Referer" to referer,
                    "Accept" to "*/*",
                    "Range" to "bytes=0-1",
                    "Sec-Fetch-Dest" to "video",
                    "Sec-Fetch-Mode" to "no-cors",
                    "Sec-Fetch-Site" to "cross-site"
                )
            )
            // Accept 200 (full content), 206 (partial content / range honored)
            val alive = response.code == 200 || response.code == 206
            if (!alive) {
                Log.w(TAG, "isLinkAlive: $url returned HTTP ${response.code}")
            }
            alive
        } catch (e: Exception) {
            Log.w(TAG, "isLinkAlive: $url failed validation: ${e.message}")
            false
        }
    }

    private suspend fun emitVideoLinkIfAlive(
        videoUrl: String,
        pageUrl: String,
        domainLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageOrigin = originOf(pageUrl)
        val refererValue = "$pageOrigin/"

        // Known-dead CDN hosts (currently: hotott.net's stale R2 bucket) are skipped without
        // even spending a network round trip, since they've been observed to always 404.
        if (knownUnreliableCdnHosts.any { videoUrl.contains(it, ignoreCase = true) }) {
            Log.w(TAG, "emitVideoLinkIfAlive: [$domainLabel] skipping known-unreliable CDN host: $videoUrl")
            if (!isLinkAlive(videoUrl, refererValue)) return false
        } else if (!isLinkAlive(videoUrl, refererValue)) {
            return false
        }

        val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)

        callback(
            newExtractorLink(
                source = this@HmaalProvider.name,
                name = "${this@HmaalProvider.name} - $domainLabel",
                url = videoUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = refererValue
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to mobileUserAgent,
                    "Referer" to refererValue,
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US",
                    "Range" to "bytes=0-",
                    "Sec-Fetch-Dest" to "video",
                    "Sec-Fetch-Mode" to "no-cors",
                    "Sec-Fetch-Site" to "cross-site"
                )
            }
        )
        return true
    }
}
