package com.Xmaza

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URLDecoder

class XmazaProvider : MainAPI() {
    override var mainUrl = "https://xmaza2.net"
    override var name = "Xmaza"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    /** All 5 mirrors are treated as equal fallback sources for both search
     * results and streams. loadLinks() labels each ExtractorLink by its own
     * domain name (confirmed against real search-page HTML from every site). */
    private val mirrors = listOf(
        "https://ottdude.com",
        "https://maalvdo.net",
        "https://xmaza.gg",
        "https://zmaal.net",
        "https://xmaza2.net"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ott/ullu" to "ULLU",
        "$mainUrl/ott/atrangii" to "Atrangii",
        "$mainUrl/ott/primeplay" to "PrimePlay",
        "$mainUrl/ott/voovi" to "Voovi"
    )

    private val styleUrlRegex = Regex("url\\((['\"]?)(.*?)\\1\\)")

    // Any quoted string ending in mp4/m3u8 - deliberately NOT anchored to a
    // preceding src=/file=/source= keyword, since different mirrors embed the
    // video url under different JS variable names (url:, embedUrl", etc.).
    private val streamPattern = Regex("[\"'](https?://[^\"']+\\.(?:mp4|m3u8)[^\"']*)[\"']")

    // "Chawl House Episode 3" -> (base="Chawl House", season=null, ep=3)
    // "Chawl House 2 Episode 1" -> (base="Chawl House", season=2, ep=1)
    private val episodeRegex = Regex("^(.*?)(?:\\s+(\\d+))?\\s+Episode\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE)

    private fun normalizeTitle(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun domainOf(url: String): String {
        val protocolEnd = url.indexOf("//") + 2
        return url.substring(0, protocolEnd) + url.substring(protocolEnd).substringBefore("/")
    }

    /**
     * Resolves any image reference to a real, directly-loadable url:
     *  - Next.js `/_next/image?url=...` proxy paths (xmaza2.net) are decoded
     *    straight to the upstream CDN url rather than depending on the
     *    mirror's own resize proxy having a full host attached.
     *  - protocol-relative ("//") and root-relative ("/") paths are resolved
     *    against the page's own domain.
     *  - inline base64 placeholders ("data:") are dropped entirely.
     */
    private fun fixImageUrl(raw: String?, pageUrl: String): String? {
        if (raw.isNullOrBlank()) return null
        val url = raw.trim()
        if (url.startsWith("data:")) return null

        val root = domainOf(pageUrl)

        if (url.contains("/_next/image")) {
            val full = if (url.startsWith("http")) url else root + url
            val encoded = Regex("[?&]url=([^&]+)").find(full)?.groupValues?.get(1)
            return if (encoded != null) {
                try {
                    URLDecoder.decode(encoded, "UTF-8")
                } catch (e: Exception) {
                    full
                }
            } else full
        }

        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> root + url
            else -> url
        }
    }

    private fun resolveHref(hrefRaw: String, site: String): String =
        if (hrefRaw.startsWith("/")) domainOf(site) + hrefRaw else hrefRaw

    /**
     * Extracts (title, href, rawPosterOrNull) from any listing/grid page -
     * search results, mainPage categories, or a series' episode list all use
     * one of these three markup families, confirmed against live HTML:
     *
     *  - xmaza2.net   : <a class="group block" href="/watch/..."><img src="/_next/image?..."></a>
     *  - zmaal.net    : <article><a class="link" href="..." title="..."></a><img src="..."></article>
     *                   (the poster <img> is a SIBLING of the anchor, not nested inside it)
     *  - all others   : <a class="video[ lazy-bg]" data-bg="...webp" style="background-size:cover;..."
     *                     title="..." href="..."><h2 class="vtitle">Title</h2></a>
     *                   data-bg must be checked BEFORE the style url() - on maalvdo/xmaza.gg the
     *                   background-image:url(...) in `style` is injected by client-side "lazy-bg"
     *                   JS after page load, so the raw HTTP response only has data-bg at fetch time.
     *                   ottdude renders url() directly server-side with no JS needed, which is why
     *                   the style-regex fallback is still needed as a second path.
     */
    private fun extractCards(doc: Document, site: String): List<Triple<String, String, String?>> {
        val results = mutableListOf<Triple<String, String, String?>>()

        when {
            site.contains("xmaza2") -> {
                doc.select("a.group.block").forEach { a ->
                    val title = a.selectFirst("h4")?.text()?.trim() ?: a.attr("title")
                    val href = a.attr("href")
                    val raw = a.selectFirst("img")?.attr("src")
                    if (title.isNotBlank() && href.isNotBlank()) results.add(Triple(title, href, raw))
                }
            }

            site.contains("zmaal") -> {
                doc.select("article").forEach { article ->
                    val a = article.selectFirst("a.link") ?: return@forEach
                    val title = a.attr("title").ifBlank { a.attr("aria-label") }.ifBlank { a.text() }
                    val href = a.attr("href")
                    val img = article.selectFirst("img")
                    val raw = img?.attr("data-src")?.ifBlank { img.attr("src") }
                    if (title.isNotBlank() && href.isNotBlank()) results.add(Triple(title, href, raw))
                }
            }

            else -> {
                doc.select("a.video").forEach { a ->
                    val title = a.selectFirst("h2.vtitle")?.text()?.trim() ?: a.attr("title")
                    val href = a.attr("href")
                    val dataBg = a.attr("data-bg")
                    val raw = dataBg.ifBlank {
                        styleUrlRegex.find(a.attr("style"))?.groupValues?.get(2)
                    }
                    if (title.isNotBlank() && href.isNotBlank()) results.add(Triple(title, href, raw))
                }
            }
        }

        return results
    }

    /**
     * Series-page hero poster. Ranks every candidate source (og:image,
     * twitter:image, link[image_src], WordPress featured-image class) by how
     * much its filename overlaps with the series title's words, and rejects
     * a non-positive score outright. This is necessary because some mirrors
     * (confirmed on maalvdo.net) set a single sitewide og:image (their own
     * logo) as a generic fallback rather than a real per-post image - without
     * scoring, that logo would otherwise be shown as if it were the poster.
     */
    private fun extractSeriesPoster(doc: Document, pageUrl: String, title: String): String? {
        val candidates = mutableListOf<String>()

        doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        doc.selectFirst("meta[name=\"twitter:image\"]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        doc.selectFirst("link[rel=\"image_src\"]")?.attr("href")
            ?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        doc.selectFirst(".wp-post-image, .attachment-post-thumbnail, .post-thumbnail img")?.let {
            val raw = it.attr("data-src").ifBlank { it.attr("src") }
            if (raw.isNotBlank()) candidates.add(raw)
        }

        if (candidates.isEmpty()) return null

        fun tokenize(s: String) =
            s.lowercase().split(Regex("[-_.\\s/%0-9]+")).filter { it.length > 2 }.toSet()

        val titleTokens = tokenize(title)
        val siteTokens = tokenize(domainOf(pageUrl))
        val brandingWords = setOf("logo", "icon", "default", "placeholder")

        var bestRaw: String? = null
        var bestScore = Int.MIN_VALUE

        for (raw in candidates) {
            val filename = raw.substringAfterLast("/").substringBefore("?")
            val fileTokens = tokenize(filename)
            val overlap = fileTokens.intersect(titleTokens).size

            var score = overlap * 10
            val isBranding = fileTokens.isNotEmpty() && fileTokens.all { it in siteTokens || it in brandingWords }
            if (isBranding) score -= 50
            val fnLower = filename.lowercase()
            if (fnLower.contains("logo") || fnLower.contains("icon") || fnLower.contains("default")) score -= 50

            if (score > bestScore) {
                bestScore = score
                bestRaw = raw
            }
        }

        if (bestScore <= 0) return null
        return fixImageUrl(bestRaw, pageUrl)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = extractCards(document, request.data).mapNotNull { (title, hrefRaw, posterRaw) ->
            val href = resolveHref(hrefRaw, request.data)
            if (title.isBlank() || href.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixImageUrl(posterRaw, request.data)
            }
        }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true)
        )
    }

    /**
     * Searches all 5 mirrors concurrently and deduplicates by normalized
     * title. Whichever mirror's coroutine finishes first "wins" a given
     * title (a Mutex guards the shared map from concurrent writes); mirror
     * response order isn't guaranteed, so this is best-effort rather than a
     * strict site-priority order.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableMapOf<String, SearchResponse>()
        val mutex = Mutex()

        coroutineScope {
            mirrors.map { site ->
                async {
                    try {
                        val searchUrl = if (site.contains("xmaza2")) "$site/search/$query" else "$site/?s=$query"
                        val doc = app.get(searchUrl).document

                        extractCards(doc, site).forEach { (title, hrefRaw, posterRaw) ->
                            val key = normalizeTitle(title)
                            val href = resolveHref(hrefRaw, site)
                            if (key.isBlank() || href.isBlank()) return@forEach

                            mutex.withLock {
                                if (!results.containsKey(key)) {
                                    results[key] = newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                                        this.posterUrl = fixImageUrl(posterRaw, site)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // 1. Fetch clicked episode page to find the parent series/web-series link.
        val epDoc = app.get(url).document

        var seriesUrl: String? = null
        for (a in epDoc.select("a")) {
            val href = a.attr("href")
            if (href.isBlank()) continue
            val full = if (href.startsWith("http")) href else domainOf(url) + href
            val pathParts = full.substringAfter("://").substringAfter("/").split("/").filter { it.isNotBlank() }

            // "series"/"web-series" root alone (size 1) is the category index, not a specific title.
            if ((pathParts.contains("series") || pathParts.contains("web-series")) && pathParts.size > 1) {
                seriesUrl = full
                break
            }
        }

        if (seriesUrl == null) return null

        // 2. Fetch the actual series page.
        val seriesDoc = app.get(seriesUrl).document
        val title = seriesDoc.selectFirst("h1, h2")?.text()?.trim() ?: "Unknown Series"
        val poster = extractSeriesPoster(seriesDoc, seriesUrl, title)

        // 3. Extract episodes using the same per-site card extractor as search/mainPage.
        val episodesList = mutableListOf<Episode>()
        val seenEpTitles = mutableSetOf<String>()

        extractCards(seriesDoc, seriesUrl).forEach { (epTitle, hrefRaw, posterRaw) ->
            val epUrl = resolveHref(hrefRaw, seriesUrl)
            if (epTitle.isBlank() || epUrl.isBlank()) return@forEach

            val normEpTitle = normalizeTitle(epTitle)
            if (seenEpTitles.contains(normEpTitle)) return@forEach
            seenEpTitles.add(normEpTitle)

            val epSlug = epUrl.trimEnd('/').substringAfterLast("/")
            episodesList.add(
                newEpisode(epSlug) { // data = slug, passed to loadLinks
                    this.name = epTitle
                    this.posterUrl = fixImageUrl(posterRaw, seriesUrl)
                }
            )
        }

        // Season-aware sort: all of "Episode 1,2,3.." first, then "2 Episode 1,2,3..", etc.
        val sortedEpisodes = episodesList.sortedWith(SeasonAwareComparator())

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data

        val mirrorUrls = mirrors.associateWith { site ->
            if (site.contains("xmaza2")) "$site/watch/$slug" else "$site/$slug/"
        }

        coroutineScope {
            mirrorUrls.map { (site, mirrorUrl) ->
                async {
                    try {
                        val html = app.get(mirrorUrl).text
                        val sourceName = domainOf(site).removePrefix("https://").removePrefix("http://")

                        streamPattern.findAll(html).forEach { matchResult ->
                            // Signed CDN urls (AWS S3 &X-Amz-...) are byte-sensitive - the page
                            // source html-encodes '&' as '&amp;', which MUST be unescaped or the
                            // request signature will not validate.
                            val videoUrl = Parser.unescapeEntities(matchResult.groupValues[1], false)
                            val isM3u8 = videoUrl.contains(".m3u8")

                            callback.invoke(
                                newExtractorLink(
                                    source = sourceName,
                                    name = sourceName,
                                    url = videoUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mirrorUrl
                                    this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P1080.value
                                }
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }

        return true
    }

    /**
     * Sorts episodes as "Chawl House Episode 1,2,3.." then "Chawl House 2
     * Episode 1,2,3.." then "Chawl House 3 Episode 1,2,3..", NOT interleaved
     * by episode number across parts (a plain alphanumeric token sort would
     * incorrectly place "2 Episode 1" before "Episode 1", since '2' < 'E').
     */
    private inner class SeasonAwareComparator : Comparator<Episode> {
        override fun compare(s1: Episode, s2: Episode): Int {
            val (base1, season1, ep1) = parseKey(s1.name ?: "")
            val (base2, season2, ep2) = parseKey(s2.name ?: "")
            if (base1 != base2) return base1.compareTo(base2)
            if (season1 != season2) return season1.compareTo(season2)
            return ep1.compareTo(ep2)
        }

        private fun parseKey(title: String): Triple<String, Int, Int> {
            val m = episodeRegex.matchEntire(title.trim()) ?: return Triple(normalizeTitle(title), 0, 0)
            val base = normalizeTitle(m.groupValues[1])
            val season = m.groupValues[2].toIntOrNull() ?: 1
            val ep = m.groupValues[3].toIntOrNull() ?: 0
            return Triple(base, season, ep)
        }
    }
}
