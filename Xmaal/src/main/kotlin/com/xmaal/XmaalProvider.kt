package com.xmaal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.nodes.Element
import java.net.URI

class XmaalProvider : MainAPI() {
    override var mainUrl = "https://hitmaal.io"
    override var name = "Xmaal"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.TvSeries)

    // The three mirror sites that share the exact same HTML structure.
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

    // ---------------------------------------------------------------
    // Small data holder used to carry the poster/title that was
    // already scraped on the OUTSIDE (home page / search results) into
    // the INSIDE of the media page, instead of re-deriving it there
    // (og:title / og:image can differ from the actual background-image
    // poster, so we simply forward what we already extracted).
    // ---------------------------------------------------------------
    private data class LoadData(
        val url: String,
        val title: String,
        val poster: String?
    )

    // Extracts the background-image url out of a style attribute like:
    // background-image: url("https://.../poster.webp");
    private fun extractBgImage(style: String?): String? {
        if (style.isNullOrBlank()) return null
        val regex = Regex("""url\((['"]?)(.*?)\1\)""")
        return regex.find(style)?.groupValues?.get(2)?.takeIf { it.isNotBlank() }
    }

    // Title must come from the anchor's title="" attribute
    // (document.querySelector("#primary > div > a") -> title attribute),
    // as seen in: <a ... title="Bidaai Episode 3 (Bua ji and Sasuma)" href="...">
    private fun extractTitle(el: Element): String? {
        val fromAttr = el.attr("title").trim()
        if (fromAttr.isNotBlank()) return fromAttr
        // fallback only if the title attribute is genuinely missing
        val fromText = el.text().trim()
        return fromText.ifBlank { null }
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

    // The "data" string we pass around (search result url / load url) can
    // either be a plain url or a JSON-encoded LoadData - handle both.
    private fun resolveUrl(data: String): String {
        return try {
            parseJson<LoadData>(data).url
        } catch (e: Exception) {
            data
        }
    }

    // Base "series name" extracted from a full episode title, e.g.
    // "Bidaai Episode 3 (Bua ji and Sasuma)"  -> "Bidaai"
    // "Series 2 Episode 1"                    -> "Series 2"
    // Used to keep episode lists limited to a single season: selecting
    // "Series" only pulls "Series Episode 1/2/3", selecting "Series 2"
    // only pulls "Series 2 Episode 1/2/3".
    private fun seriesBaseName(title: String): String {
        val regex = Regex("""(?i)^(.*?)\s+episode\s+\d+""")
        val match = regex.find(title.trim())
        return (match?.groupValues?.get(1) ?: title).trim().lowercase()
    }

    // ---------------------------------------------------------------
    // Shared list-item parser used by home page, search AND the
    // episode/series listing page, so poster + title are always
    // extracted the exact same way everywhere.
    // ---------------------------------------------------------------
    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = extractTitle(element) ?: return null
        val poster = extractBgImage(element.attr("style"))

        val data = LoadData(href, title, poster).toJson()

        return newTvSeriesSearchResponse(title, data, TvType.TvSeries) {
            this.posterUrl = poster
        }
    }

    private fun listItems(document: org.jsoup.nodes.Document): List<Element> {
        return document.select("#primary > div > a.video, #primary > div > a.lazy-bg, #primary > div > a")
            .ifEmpty { document.select("main > div > a") } // xpath fallback: /html/body/div/main/div/a
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
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}page/$page/"
        val document = app.get(url).document

        val items = listItems(document).mapNotNull { toSearchResult(it) }.distinctBy { it.name }

        return newHomePageResponse(
            list = HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    // ---------------------------------------------------------------
    // SEARCH (unified across all three mirrors, deduplicated)
    // ---------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        mirrors.forEach { mirror ->
            try {
                val document = app.get("$mirror/?s=$query").document
                results.addAll(listItems(document).mapNotNull { toSearchResult(it) })
            } catch (e: Exception) {
                // if one mirror is down, ignore and continue with the others
            }
        }

        // Deduplicate by title + url slug (ignoring which domain it came from)
        return results.distinctBy { res ->
            val realUrl = resolveUrl(res.url)
            val slug = pathOf(realUrl).trimEnd('/').substringAfterLast('/')
            "${res.name.lowercase().trim()}|$slug"
        }
    }

    // ---------------------------------------------------------------
    // LOAD (media page -> single-season episode list)
    // ---------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        // Prefer the poster/title we already scraped outside (home/search),
        // so the media page shows the exact same poster + title.
        val incoming = try {
            parseJson<LoadData>(url)
        } catch (e: Exception) {
            null
        }

        val realUrl = incoming?.url ?: url
        val document = app.get(realUrl).document

        val title = incoming?.title
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("title")?.text()
            ?: "Xmaal"

        val poster = incoming?.poster
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        // Series list link, e.g. https://hitmaal.io/series/bidaai/
        val seriesLink = document.selectFirst("#primary > div.taxonomy-meta > div.series-list > a")
            ?.attr("href")
            ?: document.selectFirst("div.series-list > a")?.attr("href")

        val selectedBase = seriesBaseName(title)
        val episodes = mutableListOf<Episode>()

        if (!seriesLink.isNullOrBlank()) {
            val seriesDoc = app.get(seriesLink).document

            listItems(seriesDoc).forEach { el ->
                val epHref = el.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                val epTitle = extractTitle(el) ?: return@forEach
                val epPoster = extractBgImage(el.attr("style"))

                // Only keep episodes belonging to the SAME season as the
                // media the user selected (e.g. "Series" vs "Series 2").
                if (seriesBaseName(epTitle) != selectedBase) return@forEach

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.posterUrl = epPoster ?: poster
                    }
                )
            }
        }

        // Ascending order by the episode number parsed out of the title.
        val epRegex = Regex("""(?i)episode\s*(\d+)""")
        var sortedEpisodes = episodes
            .distinctBy { it.data }
            .sortedBy { ep -> epRegex.find(ep.name ?: "")?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE }

        // If crawling the series page failed (no series link, or filtering
        // left nothing), fall back to a single episode: this page itself.
        if (sortedEpisodes.isEmpty()) {
            sortedEpisodes = listOf(
                newEpisode(realUrl) {
                    this.name = title
                    this.posterUrl = poster
                }
            )
        }

        return newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, sortedEpisodes) {
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
        val realUrl = resolveUrl(data)

        mirrorUrls(realUrl).forEach { mirrorUrl ->
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
