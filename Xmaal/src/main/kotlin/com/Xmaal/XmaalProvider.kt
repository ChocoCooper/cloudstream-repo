package com.Xmaal

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

class XmaalProvider : MainAPI() {

    /**
     * EVERY domain used anywhere in this provider is defined here ONCE. If any
     * mirror changes its domain, update it in this one place - nothing else
     * in the file needs to change (mirrors list, mainPage categories, and all
     * dispatch logic in extractCards()/fixImageUrl() reference these constants
     * rather than hardcoded strings).
     */
    private object Domains {
        const val OTTDUDE = "https://ottdude.com"
        const val MAALVDO = "https://maalvdo.net"
        const val XMAZA_GG = "https://xmaza.gg"
        const val ZMAAL = "https://zmaal.net"
        const val XMAZA2 = "https://xmaza2.net"
    }

    override var mainUrl = Domains.XMAZA2
    override var name = "Xmaza"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    /** All 5 mirrors are treated as equal fallback sources for both search
     * results and streams. loadLinks() labels each ExtractorLink by its own
     * domain name (confirmed against real search-page HTML from every site). */
    private val mirrors = listOf(
        Domains.OTTDUDE,
        Domains.MAALVDO,
        Domains.XMAZA_GG,
        Domains.ZMAAL,
        Domains.XMAZA2
    )

    override val mainPage = mainPageOf(
        "${Domains.OTTDUDE}/ott/ullu/" to "ULLU",
        "${Domains.OTTDUDE}/ott/atrangii/" to "Atrangii",
        "${Domains.OTTDUDE}/ott/primeplay/" to "PrimePlay",
        "${Domains.OTTDUDE}/ott/voovi/" to "Voovi"
    )

    private val styleUrlRegex = Regex("url\\((['\"]?)(.*?)\\1\\)")

    // Any quoted string ending in mp4/m3u8 - deliberately NOT anchored to a
    // preceding src=/file=/source= keyword, since different mirrors embed the
    // video url under different JS variable names (url:, embedUrl", etc.).
    private val streamPattern = Regex("[\"'](https?://[^\"']+\\.(?:mp4|m3u8)[^\"']*)[\"']")

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

    private fun extractCards(doc: Document, site: String): List<Triple<String, String, String?>> {
        val results = mutableListOf<Triple<String, String, String?>>()

        when {
            site.contains(Domains.XMAZA2) -> {
                doc.select("a.group.block").forEach { a ->
                    val title = a.selectFirst("h4")?.text()?.trim() ?: a.attr("title")
                    val href = a.attr("href")
                    val raw = a.selectFirst("img")?.attr("src")
                    if (title.isNotBlank() && href.isNotBlank()) results.add(Triple(title, href, raw))
                }
            }

            site.contains(Domains.ZMAAL) -> {
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

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableMapOf<String, SearchResponse>()
        val mutex = Mutex()

        coroutineScope {
            mirrors.map { site ->
                async {
                    try {
                        val searchUrl = if (site.contains(Domains.XMAZA2)) "$site/search/$query" else "$site/?s=$query"
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

        // The clicked episode page's own og:image is a real per-post image (unlike a
        // taxonomy/archive page's og:image, which some mirrors default to a sitewide
        // logo - see extractSeriesPoster's docstring). It's also the same image already
        // shown in search results, so reuse it as the primary backdrop/poster candidate.
        val episodePoster = extractSeriesPoster(epDoc, url, epDoc.selectFirst("h1, h2")?.text()?.trim() ?: "")

        // 2. Fetch the actual series page.
        val seriesDoc = app.get(seriesUrl).document
        val title = seriesDoc.selectFirst("h1, h2")?.text()?.trim() ?: "Unknown Series"
        val seriesPoster = extractSeriesPoster(seriesDoc, seriesUrl, title)
        val poster = episodePoster ?: seriesPoster

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
                newEpisode(epSlug) { // data = slug (see loadLinks - re-extracts the last
                    // path segment defensively in case the framework resolves this
                    // against mainUrl before loadLinks receives it)
                    this.name = epTitle
                    this.posterUrl = fixImageUrl(posterRaw, seriesUrl)
                }
            )
        }

        // Season-aware sort: all of "Episode 1,2,3.." first, then "2 Episode 1,2,3..", etc.
        val sortedEpisodes = episodesList.sortedWith(SeasonAwareComparator())

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.trimEnd('/').substringAfterLast("/")

        val mirrorUrls = mirrors.associateWith { site ->
            if (site.contains(Domains.XMAZA2)) "$site/watch/$slug" else "$site/$slug/"
        }

        coroutineScope {
            mirrorUrls.map { (site, mirrorUrl) ->
                async {
                    try {
                        val html = app.get(mirrorUrl).text
                        val sourceName = domainOf(site).removePrefix("https://").removePrefix("http://")

                        // Some mirrors embed the same video url 2-3 times on one page
                        // (<video><source>, then again in JSON-LD "embedUrl"/"contentUrl");
                        // others may expose a handful of genuinely different urls (quality
                        // variants, alternate signed copies). Since every link is labeled
                        // Unknown quality, showing more than one per mirror is indistinguishable
                        // from a plain duplicate - only the first working url is surfaced here.
                        val videoUrl = streamPattern.findAll(html)
                            .map { Parser.unescapeEntities(it.groupValues[1], false) }
                            .firstOrNull()

                        if (videoUrl != null) {
                            val isM3u8 = videoUrl.contains(".m3u8")

                            callback.invoke(
                                newExtractorLink(
                                    source = sourceName,
                                    name = sourceName,
                                    url = videoUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mirrorUrl
                                    // Unknown rather than a guessed resolution - we never actually
                                    // verified the real quality, so don't show a false number.
                                    this.quality = Qualities.Unknown.value
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
