package com.Hmaal

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

    // Homepage pulls from primary domain (hotott.net)
    override val mainPage = mainPageOf(
        "$mainUrl/ott/ullu/" to "Ullu",
        "$mainUrl/ott/atrangii/" to "Atrangii",
        "$mainUrl/ott/primeplay/" to "PrimePlay",
        "$mainUrl/ott/voovi/" to "Voovi",
        "$mainUrl/ott/jugnu/" to "Jugnu"
    )

    // Caches listing posters (homepage/search) keyed by path slug so load() reuses them.
    private val posterCache = ConcurrentHashMap<String, String>()

    // ---------- helpers ----------

    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to desktopUserAgent,
        "Accept-Language" to "en-US,en;q=0.9"
    )

    /** Domain-aware URL helpers that allow custom domain resolution */
    private fun fixUrl(url: String, domain: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        return "${domain.trimEnd('/')}/${url.trimStart('/')}"
    }

    private fun fixUrlNull(url: String?, domain: String): String? {
        if (url.isNullOrBlank()) return null
        return fixUrl(url, domain)
    }

    /** Converts full URL to normalized path slug for mirror-agnostic cache keys. */
    private fun getPath(url: String): String {
        return try {
            val path = URI(url).rawPath ?: ""
            path.trimEnd('/')
        } catch (e: Exception) {
            url
        }
    }

    /** scheme://host for a given absolute URL, used as Referer. */
    private fun originOf(url: String): String {
        return try {
            val u = URI(url)
            if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else mainUrl
        } catch (e: Exception) {
            mainUrl
        }
    }

    /** Headers that let Coil load images from CDNs with hotlink protection. */
    private fun refererHeaders(url: String?): Map<String, String> {
        val origin = if (!url.isNullOrBlank()) originOf(url) else mainUrl
        return mapOf(
            "User-Agent" to desktopUserAgent,
            "Referer" to "$origin/"
        )
    }

    /**
     * Looks for video elements across various layout containers:
     * - `#primary > div.videos > a` (hotott.net, botmaal.io)
     * - `#primary > div > a` (ottdude.com, serieswala.com, maaltv.io)
     */
    private fun Document.selectVideoElements(): List<Element> {
        val elements = this.select("#primary > div.videos > a")
        return if (elements.isNotEmpty()) elements else this.select("#primary > div > a")
    }

    /**
     * Extracts playable video URLs and iframe embeds from document.
     * Updated to check vid-src and xplayer container elements.
     */
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

        // Parse custom video sources using vid-src and src
        this.select(videoSelectors.joinToString(", ")).forEach { el ->
            val src = el.attr("vid-src")
                .ifBlank { el.attr("src") }
                .ifBlank { el.attr("data-src") }
                .ifBlank { el.attr("data-video-src") }
                .trim()
            if (src.isNotBlank() && !src.startsWith("blob:")) {
                urls.add(src)
            }
        }

        // Iframe player embeds
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

    /** Pulls url out of background-image style or lazy data attributes. */
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

    /** Extracts image poster from element. */
    private fun Element.extractPoster(domain: String): String? {
        val bg = this.extractBackgroundImage()
        val src = if (bg.isNullOrBlank()) {
            this.selectFirst("img")?.attr("src")?.ifBlank { this.selectFirst("img")?.attr("data-src") }
        } else {
            bg
        }
        return src?.trim()?.ifBlank { null }?.let { fixUrlNull(it, domain) }
    }

    /** Converts video tile to SearchResponse. */
    private fun Element.toSearchResult(domain: String): SearchResponse? {
        val title = this.attr("title").ifBlank { this.text() }.trim()
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
                        // mirror unreachable / no results on this domain, skip it
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

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.trim()?.ifBlank { null }
            ?: document.selectFirst("h1.page-title, h1.title, h1")?.text()?.trim()?.ifBlank { null }
            ?: document.selectFirst("title")?.text()?.trim()
            ?: "Unknown"

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
                    try {
                        val currentDomain = originOf(pageUrl)
                        val doc = app.get(pageUrl, headers = browserHeaders).document
                        val rawSources = doc.extractVideoSources()
                        if (rawSources.isEmpty()) return@async

                        for (rawUrl in rawSources) {
                            val fullUrl = fixUrl(rawUrl, currentDomain)
                            if (!processedUrls.add(fullUrl)) continue

                            // 1. Try standard Cloudstream extractors for embedded links
                            val extractorLoaded = loadExtractor(fullUrl, pageUrl, subtitleCallback, callback)
                            if (extractorLoaded) {
                                found = true
                                continue
                            }

                            // 2. If it's an internal embed page or iframe, attempt to fetch inner video
                            if (fullUrl.contains("/embed/") || fullUrl.contains("/player/") || rawUrl.contains("iframe")) {
                                try {
                                    val iframeDoc = app.get(fullUrl, headers = browserHeaders + mapOf("Referer" to pageUrl)).document
                                    val innerSources = iframeDoc.extractVideoSources()
                                    for (innerRaw in innerSources) {
                                        val innerFull = fixUrl(innerRaw, originOf(fullUrl))
                                        if (processedUrls.add(innerFull)) {
                                            if (!loadExtractor(innerFull, fullUrl, subtitleCallback, callback)) {
                                                emitVideoLink(innerFull, pageUrl, callback)
                                                found = true
                                            } else {
                                                found = true
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            } else {
                                // 3. Direct video file (.mp4, .m3u8, etc.)
                                emitVideoLink(fullUrl, pageUrl, callback)
                                found = true
                            }
                        }
                    } catch (e: Exception) {
                        // mirror unreachable / not found for this media, continue searching others
                    }
                }
            }.awaitAll()
        }

        return found
    }

    private fun emitVideoLink(
        videoUrl: String,
        pageUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = try { URI(videoUrl).host ?: originOf(pageUrl) } catch (e: Exception) { originOf(pageUrl) }
        val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)

        callback(
            newExtractorLink(
                source = this@HmaalProvider.name,
                name = "${this@HmaalProvider.name} - $host",
                url = videoUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = pageUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to desktopUserAgent,
                    "Referer" to pageUrl
                )
            }
        )
    }
}
