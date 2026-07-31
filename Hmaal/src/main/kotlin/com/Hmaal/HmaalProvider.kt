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

    /** Converts full URL to normalized path slug (e.g. "/ott/ullu/movie/") for mirror-agnostic cache keys. */
    private fun getPath(url: String): String {
        return try {
            val path = URI(url).rawPath ?: ""
            path.trimEnd('/')
        } catch (e: Exception) {
            url
        }
    }

    /** scheme://host for a given absolute URL, used as the Referer for hotlink-protected images. */
    private fun originOf(url: String): String {
        return try {
            val u = URI(url)
            if (u.scheme != null && u.host != null) "${u.scheme}://${u.host}" else mainUrl
        } catch (e: Exception) {
            mainUrl
        }
    }

    /** Headers that let Coil load images from CDNs that reject requests with no/foreign Referer. */
    private fun refererHeaders(url: String?): Map<String, String> {
        val origin = if (!url.isNullOrBlank()) originOf(url) else mainUrl
        return mapOf(
            "User-Agent" to desktopUserAgent,
            "Referer" to "$origin/"
        )
    }

    /**
     * Looks for video links on both container variants:
     * - `#primary > div.videos > a` (hotott.net, botmaal.io)
     * - `#primary > div > a` (ottdude.com, serieswala.com, maaltv.io)
     */
    private fun Document.selectVideoElements(): List<Element> {
        val elements = this.select("#primary > div.videos > a")
        return if (elements.isNotEmpty()) elements else this.select("#primary > div > a")
    }

    /**
     * Looks for a playable video URL in several possible spots.
     */
    private fun Document.extractVideoSources(): List<String> {
        val urls = linkedSetOf<String>()

        this.select("#my-video source, video#my-video source, video source").forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("data-src") }.trim()
            if (src.isNotBlank()) urls.add(src)
        }

        this.select("#my-video, video#my-video, video").forEach { el ->
            val src = el.attr("src").ifBlank { el.attr("data-src") }.trim()
            if (src.isNotBlank()) urls.add(src)
        }

        return urls.toList()
    }

    /** Pulls the url out of `background-image: url('...')` or lazy data attributes. */
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

    /** Extracts image poster from element via background-style or fallback img tags. */
    private fun Element.extractPoster(domain: String): String? {
        val bg = this.extractBackgroundImage()
        val src = if (bg.isNullOrBlank()) {
            this.selectFirst("img")?.attr("src")?.ifBlank { this.selectFirst("img")?.attr("data-src") }
        } else {
            bg
        }
        return src?.trim()?.ifBlank { null }?.let { fixUrlNull(it, domain) }
    }

    /** Converts one `a.video` tile (homepage / search) into a SearchResponse. */
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

        // Dedupe by exact media name (case-insensitive, trimmed) — keeps first hit found.
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

        // Extract path+query from whichever mirror the URL came from so we can
        // rebuild it against every other mirror (they share the same slugs).
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
                        val doc = app.get(pageUrl, headers = browserHeaders).document
                        val videoUrls = doc.extractVideoSources()
                        if (videoUrls.isEmpty()) return@async // e.g. "oops page not found" mirror

                        val host = URI(pageUrl).host ?: pageUrl

                        videoUrls.forEach { rawUrl ->
                            val videoUrl = fixUrl(rawUrl)
                            callback(
                                newExtractorLink(
                                    source = this@HmaalProvider.name,
                                    name = "${this@HmaalProvider.name} - $host",
                                    url = videoUrl,
                                    type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = pageUrl
                                    this.quality = Qualities.Unknown.value
                                    this.headers = mapOf("User-Agent" to desktopUserAgent)
                                }
                            )
                            found = true
                        }
                    } catch (e: Exception) {
                        // dead mirror / not found for this specific media, skip and keep trying others
                    }
                }
            }.awaitAll()
        }

        return found
    }
}
