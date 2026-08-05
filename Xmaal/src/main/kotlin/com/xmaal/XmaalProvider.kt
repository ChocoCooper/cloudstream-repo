package com.xmaal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class XmaalProvider : MainAPI() {
    override var mainUrl = "https://hitmaal.io"
    override var name = "Xmaal"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // The three mirror sites that share the same HTML structure.
    private val mirrors = listOf(
        "https://hitmaal.io",
        "https://xxmaza.com",
        "https://uncutmaza.gg"
    )

    // A realistic browser UA + referer. Several of these sites otherwise
    // serve a stripped-down page (or block the request outright) to
    // non-browser user agents, which is the most likely reason the
    // video-source selector was coming back empty ("No Links Found").
    private fun browserHeaders(referer: String? = null): Map<String, String> {
        val map = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        if (referer != null) map["Referer"] = referer
        return map
    }

    private suspend fun fetchDoc(url: String): Document {
        return app.get(url, headers = browserHeaders(url)).document
    }

    private fun siteNameFor(url: String): String {
        return when {
            url.contains("hitmaal.io") -> "HitMaal"
            url.contains("xxmaza.com") -> "XXMaza"
            url.contains("uncutmaza.gg") -> "UncutMaza"
            else -> name
        }
    }

    // Extracts the background-image url out of a style attribute like:
    // background-image: url("https://.../poster.webp");
    private fun extractBgImage(style: String?): String? {
        if (style.isNullOrBlank()) return null
        val regex = Regex("""url\((['"]?)(.*?)\1\)""")
        return regex.find(style)?.groupValues?.get(2)
    }

    // The path of a media page is identical across all three mirrors, e.g.
    // https://hitmaal.io/pyaar-idhar-udhar-episode-1/
    // https://xxmaza.com/pyaar-idhar-udhar-episode-1/
    // https://uncutmaza.gg/pyaar-idhar-udhar-episode-1/
    private fun pathOf(url: String): String {
        return try {
            URI(url).path ?: "/"
        } catch (e: Exception) {
            "/"
        }
    }

    private fun mirrorUrls(url: String): List<String> {
        val path = pathOf(url)
        return mirrors.map { it.trimEnd('/') + path }
    }

    // Only accept links that actually look like episode/media pages
    // (…-episode-12/ style slugs). This is what stops sidebar / related
    // widgets on the series-list page from being scraped in as if they
    // were episodes of the current series, which was scrambling the
    // final episode order.
    private val episodeHrefRegex = Regex("""(?i)-episode-\d+""")

    private fun listAnchors(document: Document): List<Element> {
        return document.select("#primary a.video.lazy-bg, #primary a.video, #primary a.lazy-bg")
            .ifEmpty { document.select("#primary a[href]") }
            .filter { episodeHrefRegex.containsMatchIn(it.attr("href")) || it.attr("style").contains("background-image") }
    }

    // ---------------------------------------------------------------
    // HOME PAGE
    // ---------------------------------------------------------------

    override val mainPage = mainPageOf(
        "https://hitmaal.io/ott/ullu/" to "Ullu",
        "https://hitmaal.io/ott/primeplay-d1/" to "PrimePlay",
        "https://hitmaal.io/ott/jugnu/" to "Jugnu",
        "https://hitmaal.io/ott/hunters/" to "Hunters",
        "https://hitmaal.io/ott/hitprime/" to "HitPrime",
        "https://hitmaal.io/ott/voovi-d3/" to "Voovi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = fetchDoc(url)

        val items = listAnchors(document)
            .mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = true),
            hasNext = items.isNotEmpty()
        )
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = element.attr("title").ifBlank { element.text() }.ifBlank { return null }
        val poster = extractBgImage(element.attr("style"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    // ---------------------------------------------------------------
    // SEARCH (unified across all three mirrors, deduplicated)
    // ---------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        mirrors.forEach { mirror ->
            try {
                val document = fetchDoc("$mirror/?s=$query")
                results.addAll(listAnchors(document).mapNotNull { toSearchResult(it) })
            } catch (e: Exception) {
                // if one mirror is down, ignore and continue with the others
            }
        }

        // Deduplicate by media title + slug path (ignoring which domain it came from)
        return results.distinctBy { res ->
            val slug = pathOf(res.url).trimEnd('/').substringAfterLast('/')
            "${res.name.lowercase().trim()}|$slug"
        }
    }

    // ---------------------------------------------------------------
    // LOAD (media page -> episode list)
    // ---------------------------------------------------------------

    // Splits a title like "Bidaai Episode 4 (Bua ji and Sasuma)" into
    // ("Bidaai", 4) so episodes can be grouped by series and sorted
    // numerically within that series.
    private val seriesEpisodeRegex = Regex("""(?i)^(.*?)\s*episode\s*(\d+)""")

    private fun parseSeriesAndEpisode(title: String, hrefFallback: String): Pair<String, Int> {
        seriesEpisodeRegex.find(title)?.let {
            val seriesName = it.groupValues[1].trim().ifBlank { title }
            val epNum = it.groupValues[2].toIntOrNull() ?: 1
            return seriesName to epNum
        }
        // fall back to parsing the url slug, e.g. /bidaai-episode-4/
        val slugMatch = Regex("""(?i)^(.*?)-episode-(\d+)""").find(
            pathOf(hrefFallback).trim('/').substringAfterLast('/')
        )
        if (slugMatch != null) {
            val seriesName = slugMatch.groupValues[1].replace('-', ' ').trim().ifBlank { title }
            val epNum = slugMatch.groupValues[2].toIntOrNull() ?: 1
            return seriesName to epNum
        }
        return title to 1
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDoc(url)

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("title")?.text()
            ?: "Xmaal"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        // Series list link, e.g. https://hitmaal.io/series/bidaai/
        val seriesLink = document.selectFirst("#primary > div.taxonomy-meta > div.series-list > a")
            ?.attr("href")

        // Collect candidate episode hrefs from the series listing page.
        val candidateHrefs: List<String> = if (seriesLink != null) {
            try {
                val seriesDoc = fetchDoc(seriesLink)
                listAnchors(seriesDoc)
                    .mapNotNull { it.attr("href").takeIf { h -> h.isNotBlank() } }
                    .distinct()
            } catch (e: Exception) {
                emptyList()
            }
        } else emptyList()

        // The listing page usually doesn't carry a poster for each entry -
        // posters only show up once you open the individual episode page
        // (its own og:image). So we crawl each candidate episode page (in
        // parallel) to grab its real title + poster + confirm it's valid.
        data class EpisodeInfo(val href: String, val title: String, val poster: String?)

        val episodeInfos: List<EpisodeInfo> = coroutineScope {
            candidateHrefs.map { href ->
                async {
                    try {
                        val epDoc = fetchDoc(href)
                        val epTitle = epDoc.selectFirst("meta[property=og:title]")?.attr("content")
                            ?: epDoc.selectFirst("title")?.text()
                            ?: href
                        val epPoster = epDoc.selectFirst("meta[property=og:image]")?.attr("content")
                        EpisodeInfo(href, epTitle, epPoster)
                    } catch (e: Exception) {
                        null
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // Group by series name (parsed from title/url), then sort ascending
        // by episode number within each series, then by series name so the
        // final order looks like:
        //   Series A Episode 1, Series A Episode 2, ... Series B Episode 1, ...
        val parsed = episodeInfos.map { info ->
            val (seriesName, epNum) = parseSeriesAndEpisode(info.title, info.href)
            Triple(info, seriesName, epNum)
        }

        val seriesOrder = parsed.map { it.second }.distinct()
        val seasonIndexOf = seriesOrder.withIndex().associate { (idx, name) -> name to (idx + 1) }

        val sortedParsed = parsed.sortedWith(compareBy({ seasonIndexOf[it.second] ?: Int.MAX_VALUE }, { it.third }))

        val episodes = sortedParsed.map { (info, seriesName, epNum) ->
            newEpisode(info.href) {
                this.name = info.title
                this.posterUrl = info.poster
                this.season = seasonIndexOf[seriesName] ?: 1
                this.episode = epNum
            }
        }

        // If crawling the series page failed to find episodes, fall back to a single "episode" for this page.
        val finalEpisodes = episodes.ifEmpty {
            listOf(
                newEpisode(url) {
                    this.name = title
                    this.posterUrl = poster
                    this.season = 1
                    this.episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (checks all three mirrors for stream sources)
    // ---------------------------------------------------------------

    private fun findStreamUrl(document: Document): String? {
        // Primary target per the site's known markup.
        document.selectFirst("div.xplayer-lazy-source[data-src]")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }?.let { return it }

        // Fallbacks in case markup shifts slightly between mirrors/updates.
        document.selectFirst("div.video-container [data-src]")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }?.let { return it }

        document.selectFirst("[data-src]")?.attr("data-src")
            ?.takeIf { it.isNotBlank() }?.let { return it }

        document.selectFirst("video source[src]")?.attr("src")
            ?.takeIf { it.isNotBlank() }?.let { return it }

        document.selectFirst("video[src]")?.attr("src")
            ?.takeIf { it.isNotBlank() }?.let { return it }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        mirrorUrls(data).forEach { mirrorUrl ->
            try {
                val document = app.get(mirrorUrl, headers = browserHeaders(mirrorUrl)).document
                val streamUrl = findStreamUrl(document)

                if (!streamUrl.isNullOrBlank()) {
                    val srcName = siteNameFor(mirrorUrl)
                    callback(
                        newExtractorLink(
                            source = srcName,
                            name = "$name - $srcName",
                            url = streamUrl
                        ) {
                            this.referer = mirrorUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found = true
                }
            } catch (e: Exception) {
                // this mirror failed / doesn't have this episode, try the next one
            }
        }

        return found
    }
}
