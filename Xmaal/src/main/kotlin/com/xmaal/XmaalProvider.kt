package com.xmaal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
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

    // Root "https://host/" of a url, used as the Referer header these
    // sites expect (they block hotlinked images/videos without it).
    private fun refererFor(url: String): String {
        return try {
            val u = URI(url)
            "${u.scheme}://${u.host}/"
        } catch (e: Exception) {
            mainUrl
        }
    }

    // ---------------------------------------------------------------
    // Carries the poster/title already scraped OUTSIDE (home page /
    // search results) into the INSIDE of the media page, so the exact
    // same poster+title is reused instead of being re-derived.
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
        return regex.find(style)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun resolveUrl(data: String): String {
        return try {
            parseJson<LoadData>(data).url
        } catch (e: Exception) {
            data
        }
    }

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

    // Base "series name" from a full episode title, e.g.
    // "Bidaai Episode 3 (Bua ji and Sasuma)" -> "bidaai"
    // "Series 2 Episode 1"                   -> "series 2"
    // Keeps the episode list limited to a single season.
    private fun seriesBaseName(title: String): String {
        val regex = Regex("""(?i)^(.*?)\s+episode\s+\d+""")
        val match = regex.find(title.trim())
        return (match?.groupValues?.get(1) ?: title).trim().lowercase()
    }

    // ---------------------------------------------------------------
    // A "real" media item is exactly what the site markup shows us:
    // <a class="video lazy-bg" style="...background-image:url(...)"
    //    title="..." href="...">
    // Anything without BOTH a title attribute and a background-image
    // style is not an episode/media card (pagination, nav links, etc.)
    // and must be discarded - this is what was leaking "1.", "2.", "3."
    // and mismatched posters into the episode list.
    // ---------------------------------------------------------------
    private fun listItems(document: Document): List<Element> {
        val candidates = document.select("#primary > div > a")
            .ifEmpty { document.select("main > div > a") } // xpath fallback: /html/body/div/main/div/a

        return candidates.filter { el ->
            el.attr("title").isNotBlank() && extractBgImage(el.attr("style")) != null
        }
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = element.attr("title").trim().ifBlank { return null }
        val poster = extractBgImage(element.attr("style")) ?: return null

        val data = LoadData(href, title, poster).toJson()

        return newTvSeriesSearchResponse(title, data, TvType.TvSeries) {
            this.posterUrl = poster
            this.posterHeaders = mapOf("Referer" to refererFor(href))
        }
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
        val document = app.get(url, referer = mainUrl).document

        val items = listItems(document).mapNotNull { toSearchResult(it) }.distinctBy { it.url }

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
                val document = app.get("$mirror/?s=$query", referer = mirror).document
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
        val incoming = try {
            parseJson<LoadData>(url)
        } catch (e: Exception) {
            null
        }

        val realUrl = incoming?.url ?: url
        val document = app.get(realUrl, referer = refererFor(realUrl)).document

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
            val seriesDoc = app.get(seriesLink, referer = refererFor(seriesLink)).document

            listItems(seriesDoc).forEach { el ->
                val epHref = el.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                val epTitle = el.attr("title").trim().ifBlank { return@forEach }
                // Each episode's own poster - never falls back to the
                // series poster, since listItems() already guarantees a
                // real background-image is present on every element.
                val epPoster = extractBgImage(el.attr("style"))

                // Only keep episodes belonging to the SAME season as the
                // media the user selected (e.g. "Series" vs "Series 2").
                if (seriesBaseName(epTitle) != selectedBase) return@forEach

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.posterUrl = epPoster
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
            this.posterHeaders = mapOf("Referer" to refererFor(realUrl))
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
                val document = app.get(mirrorUrl, referer = refererFor(mirrorUrl)).document

                // Primary: document.querySelector("#post-XXXX > div.video-container > div > div:nth-child(1)")
                // i.e. any element with data-src nested inside div.video-container
                var streamUrl = document.selectFirst("div.video-container [data-src]")?.attr("data-src")

                // Fallback: any element on the page carrying a data-src that
                // points at a video file, in case the container markup
                // differs slightly between mirrors.
                if (streamUrl.isNullOrBlank()) {
                    streamUrl = document.select("[data-src]")
                        .map { it.attr("data-src") }
                        .firstOrNull { it.contains(".mp4", ignoreCase = true) }
                }

                if (!streamUrl.isNullOrBlank()) {
                    val srcName = siteNameFor(mirrorUrl)
                    callback(
                        newExtractorLink(
                            source = srcName,
                            name = "$name - $srcName",
                            url = streamUrl
                        ) {
                            this.referer = refererFor(mirrorUrl)
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
