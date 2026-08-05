package com.xmaal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        if (style == null) return null
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

    // Small payload we thread through search -> load, so the real title
    // ("Utha Le Jaunga Episode 4") and the real poster (the background-image
    // url off the card) survive into the media page, instead of falling back
    // to the page's <meta og:title>/<meta og:image>, which on these sites are
    // a generic "Watch ... Web Series >>> HITMaal" string and a grey
    // placeholder image.
    data class LoadData(
        val url: String,
        val title: String,
        val poster: String? = null
    )

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
        val document = app.get(url).document

        val items = document.select("#primary > div > a.video, #primary > div > a.lazy-bg, #primary > div > a")
            .mapNotNull { toSearchResult(it) }
            .distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    // Builds a search result card straight from the anchor: the visible
    // title comes from the `title` attribute (e.g. "Utha Le Jaunga Episode
    // 4") and the poster comes from the inline `background-image` url in
    // `style` — never from page meta tags.
    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = element.attr("title").ifBlank { element.text() }.ifBlank { return null }
        val poster = extractBgImage(element.attr("style"))

        // Carry the real title/poster into load() via the url payload.
        val loadUrl = LoadData(href, title, poster).toJson()

        return newTvSeriesSearchResponse(title, loadUrl, TvType.TvSeries) {
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
                val document = app.get("$mirror/?s=$query").document
                val items = document.select("#primary > div > a.video, #primary > div > a.lazy-bg, #primary > div > a")
                    .mapNotNull { toSearchResult(it) }
                results.addAll(items)
            } catch (e: Exception) {
                // if one mirror is down, ignore and continue with the others
            }
        }

        // Deduplicate by media title + slug path (ignoring which domain it came from)
        return results.distinctBy { res ->
            val slug = pathOf((parseJson<LoadData>(res.url)).url).trimEnd('/').substringAfterLast('/')
            "${res.name.lowercase().trim()}|$slug"
        }
    }

    // ---------------------------------------------------------------
    // LOAD (media page -> episode list)
    // ---------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        // `url` is a LoadData JSON payload produced by toSearchResult(); it
        // carries the real page url plus the title/poster we already scraped
        // off the card, so we don't need to (and shouldn't) trust this page's
        // own <meta> tags for those two fields.
        val loadData = try {
            parseJson<LoadData>(url)
        } catch (e: Exception) {
            LoadData(url, url)
        }

        val pageUrl = loadData.url
        val document = app.get(pageUrl).document

        val title = loadData.title
        val poster = loadData.poster
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        // Series list link, e.g. https://hitmaal.io/series/bidaai/
        val seriesLink = document.selectFirst("#primary > div.taxonomy-meta > div.series-list > a")
            ?.attr("href")

        val episodes = mutableListOf<Episode>()

        if (seriesLink != null) {
            val seriesDoc = app.get(seriesLink).document
            val epElements = seriesDoc.select("#primary > div > a.video, #primary > div > a.lazy-bg, #primary > div > a")

            epElements.forEach { el ->
                val epHref = el.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                // Same rule here: episode title/poster come straight from the
                // anchor's title attribute + background-image style, never
                // from meta tags.
                val epTitle = el.attr("title").ifBlank { el.text() }.ifBlank { "Episode" }
                val epPoster = extractBgImage(el.attr("style"))
                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.posterUrl = epPoster
                    }
                )
            }
        }

        // Ensure ascending order using episode number parsed from the title, falling back to list order.
        val epRegex = Regex("""(?i)episode\s*(\d+)""")
        val sortedEpisodes = episodes.sortedBy { ep ->
            epRegex.find(ep.name ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
        }

        // If crawling the series page failed to find episodes, fall back to a single "episode" for this page.
        val finalEpisodes = sortedEpisodes.ifEmpty {
            listOf(
                newEpisode(pageUrl) {
                    this.name = title
                    this.posterUrl = poster
                }
            )
        }

        return newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, finalEpisodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------------------------------------------------------------
    // LOAD LINKS (checks all three mirrors for stream sources)
    // ---------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        mirrorUrls(data).forEach { mirrorUrl ->
            try {
                val document = app.get(mirrorUrl).document
                val sourceElement = document.selectFirst("div.xplayer-lazy-source, div.video-container div div[data-src]")
                val streamUrl = sourceElement?.attr("data-src")

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
