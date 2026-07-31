package com.hmaal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
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

    /** Pulls the url out of `background-image: url('...')` in a style attribute. */
    private fun Element.extractBackgroundImage(): String? {
        val style = this.attr("style")
        val match = Regex("""url\((['"]?)(.*?)\1\)""").find(style)
        return match?.groupValues?.get(2)?.trim()?.ifBlank { null }
    }

    /** Grabs an integer episode number out of a title like "Series Episode 2". */
    private fun parseEpisodeNumber(title: String): Int? {
        val match = Regex("""episode\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)
            ?: Regex("""\bep\s*[-:]?\s*(\d+)""", RegexOption.IGNORE_CASE).find(title)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Converts one `a.video` tile (homepage / search / series listing) into a SearchResponse. */
    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title").ifBlank { this.text() }.trim()
        if (title.isBlank()) return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = this.extractBackgroundImage()?.let { fixUrlNull(it) }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ---------- homepage ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val document = app.get(url).document

        val items = document.select("#primary > div > a").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList(request.name, items),
            hasNext = items.isNotEmpty(),
            isHorizontalImages = true
        )
    }

    // ---------- search (all three mirrors, deduplicated) ----------

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        mirrorDomains.apmap { domain ->
            try {
                val doc = app.get("$domain/?s=$query").document
                val items = doc.select("#primary > div > a").mapNotNull { it.toSearchResult() }
                synchronized(results) { results.addAll(items) }
            } catch (e: Exception) {
                // mirror unreachable / no results on this domain, skip it
            }
        }

        // Dedupe by exact media name (case-insensitive, trimmed) — keeps first hit found.
        return results.distinctBy { it.name.trim().lowercase() }
    }

    // ---------- load (series episode list OR single movie/episode) ----------

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val seriesLink = document.selectFirst("#primary > div.taxonomy-meta > div.series-list > a")

        if (seriesLink != null) {
            val seriesName = seriesLink.text().trim().ifBlank { "Unknown Series" }
            val seriesUrl = fixUrl(seriesLink.attr("href"))
            val seriesDoc = app.get(seriesUrl).document

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

        candidateUrls.apmap { pageUrl ->
            try {
                val doc = app.get(pageUrl).document
                val sources = doc.select("#my-video > source")
                if (sources.isEmpty()) return@apmap // e.g. "oops page not found" mirror

                val host = URI(pageUrl).host ?: pageUrl

                sources.forEach { source ->
                    val videoUrl = source.attr("src")
                    if (videoUrl.isNotBlank()) {
                        callback(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} - $host",
                                url = fixUrl(videoUrl),
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = pageUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        found = true
                    }
                }
            } catch (e: Exception) {
                // dead mirror / not found for this specific media, skip and keep trying others
            }
        }

        return found
    }
}
