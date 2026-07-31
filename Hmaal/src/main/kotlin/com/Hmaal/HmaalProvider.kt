package com.hmaal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class HmaalProvider : MainAPI() {
    override var mainUrl = "https://hmaal.tv"
    override var name = "Hmaal"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    // All three sites share (almost) the exact same theme/markup structure,
    // so a page path from one mirror usually resolves on the others too.
    private val mirrorDomains = listOf(
        "https://hmaal.tv",
        "https://hdmaal.io",
        "https://hotmaal.xxx"
    )

    // Homepage only ever pulls from the primary domain (hmaal.tv), per spec.
    override val mainPage = mainPageOf(
        "$mainUrl/ott/ullu/" to "Ullu",
        "$mainUrl/ott/atrangii/" to "Atrangii",
        "$mainUrl/ott/primeplay/" to "PrimePlay",
        "$mainUrl/ott/voovi/" to "Voovi",
        "$mainUrl/ott/jugnu/" to "Jugnu"
    )

    // ---------- helpers ----------

    // A bare (no User-Agent) request is a dead giveaway for a bot/scraper and gets blocked or
    // served a stripped-down page by these WordPress theme sites. Send this on every request.
    private val desktopUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to desktopUserAgent,
        "Accept-Language" to "en-US,en;q=0.9"
    )

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
    private fun refererHeaders(domain: String): Map<String, String> {
        val origin = if (domain.startsWith("http")) domain else originOf(domain)
        return browserHeaders + mapOf("Referer" to "$origin/")
    }

    /**
     * Looks for a playable video URL in several possible spots, since these theme sites don't
     * always keep the stream on `#my-video > source src=` — some lazy-load it into `data-src`,
     * some put it directly on the `<video>` tag with no child `<source>` at all.
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

    /** Pulls the url out of `background-image: url('...')` in a style attribute. */
    private fun Element.extractBackgroundImage(): String? {
        val style = this.attr("style")
        val match = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
        return match?.groupValues?.get(2)?.trim()?.ifBlank { null }
    }

    /**
     * Grabs an integer episode number out of a title regardless of what surrounds it, e.g.
     * "Series Episode 2", "Series Episode-02", "Series Ep 2", "Bidaai Episode 2 (Wife)".
     * Uses `find` (not an end/full match) so trailing text after the number is fine.
     */
    private fun parseEpisodeNumber(title: String): Int? {
        val patterns = listOf(
            Regex("""episode\s*[-:.#]?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\bep\s*[-:.#]?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\be\s*[-:.#]?(\d+)\b""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            pattern.find(title)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        }
        return null
    }

    /** Converts one `a.video` tile (homepage / search / series listing) into a SearchResponse. */
    private fun Element.toSearchResult(domain: String): SearchResponse? {
        val title = this.attr("title").ifBlank { this.text() }.trim()
        if (title.isBlank()) return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = this.extractBackgroundImage()?.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = refererHeaders(domain)
        }
    }

    // ---------- homepage ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url, headers = browserHeaders).document

        val items = document.select("#primary > div > a").mapNotNull { it.toSearchResult(mainUrl) }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext = items.isNotEmpty()
        )
    }

    // ---------- search (all three mirrors, deduplicated) ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        coroutineScope {
            mirrorDomains.map { domain ->
                async {
                    try {
                        val doc = app.get("$domain/?s=$query", headers = browserHeaders).document
                        val items = doc.select("#primary > div > a").mapNotNull { it.toSearchResult(domain) }
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

    // ---------- load (series episode list OR single movie/episode) ----------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = browserHeaders).document

        val seriesLink = document.selectFirst("#primary > div.taxonomy-meta > div.series-list > a")

        if (seriesLink != null) {
            val seriesName = seriesLink.text().trim().ifBlank { "Unknown Series" }
            val seriesUrl = fixUrl(seriesLink.attr("href"))
            val seriesDoc = app.get(seriesUrl, headers = browserHeaders).document

            val episodes = seriesDoc.select("#primary > div > a").mapNotNull { el ->
                val title = el.attr("title").ifBlank { el.text() }.trim()
                if (title.isBlank()) return@mapNotNull null
                val epHref = fixUrlNull(el.attr("href")) ?: return@mapNotNull null
                val epImage = el.extractBackgroundImage()?.let { fixUrlNull(it) }
                val epNum = parseEpisodeNumber(title)

                newEpisode(epHref) {
                    this.name = title
                    this.episode = epNum
                    this.posterUrl = epImage
                }
            }.sortedBy { it.episode ?: Int.MAX_VALUE }

            val poster = document.selectFirst("a.video")?.extractBackgroundImage()
                ?.let { fixUrlNull(it) } ?: episodes.firstOrNull()?.posterUrl

            return newTvSeriesLoadResponse(seriesName, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.posterHeaders = refererHeaders(originOf(url))
            }
        }

        // No series list found -> treat as a standalone movie/episode page.
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.trim()?.ifBlank { null }
            ?: document.selectFirst("title")?.text()?.trim()
            ?: "Unknown"

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.let { fixUrlNull(it) }
            ?: document.selectFirst("a.video")?.extractBackgroundImage()?.let { fixUrlNull(it) }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = refererHeaders(originOf(url))
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
