package com.skybap

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * SkyBap provider
 *
 * The real content domain rotates constantly, so every request first resolves
 * the "live" domain from https://skybap.site/  ( body > a  ->  href = live domain ).
 * That resolved domain is cached for the lifetime of the provider instance.
 */
class SkyBapProvider : MainAPI() {

    // Static landing page that always redirects to the current working domain.
    private val resolverUrl = "https://skybap.site"

    override var mainUrl = resolverUrl
    override var name = "SkyBap"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.NSFW
    )

    // ---------------------------------------------------------------------
    // Dynamic URL resolution
    // ---------------------------------------------------------------------

    private var resolvedBaseUrl: String? = null

    /**
     * Fetches skybap.site, reads `body > a` for the currently active mirror,
     * and caches it. Falls back to throwing a readable error if the
     * resolver page itself is unreachable or has changed structure.
     */
    private suspend fun getActiveBaseUrl(): String {
        resolvedBaseUrl?.let { return it }

        val doc = app.get(resolverUrl, timeout = 15).document
        val href = doc.selectFirst("body > a")?.attr("href")
            ?: doc.selectFirst("a[href^=http]")?.attr("href") // fallback if body>a shifts
            ?: throw ErrorLoadingException("SkyBap resolver page did not return a live URL")

        val normalized = when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            else -> "https://$href"
        }.trimEnd('/')

        resolvedBaseUrl = normalized
        return normalized
    }

    /** Builds an absolute URL from a possibly-relative href using the live base. */
    private fun absolute(base: String, href: String?): String? {
        if (href.isNullOrBlank()) return null
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("/") -> "$base$href"
            else -> "$base/$href"
        }
    }

    // ---------------------------------------------------------------------
    // Home page
    // ---------------------------------------------------------------------

    override val mainPage = mainPageOf(
        "category/Tamil-Movies.html" to "Tamil Movies",
        "category/Bollywood-Movies.html" to "Bollywood Movies",
        "category/All-Web-Series.html" to "Web Series",
        "category/Hot-Short-Film.html" to "Hot Short Films"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getActiveBaseUrl()
        val url = "$base/${request.data}"
        val doc = app.get(url, timeout = 15).document

        val items = parseFolderListing(doc, base)

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = false
        )
    }

    /**
     * Folders on category / search pages are anchors sitting inside a
     * `div.L` block, e.g.
     *   <div class="L"><b><a href="/movie/....html">Title</a></b></div>
     */
    private fun parseFolderListing(doc: Document, base: String): List<SearchResponse> {
        val anchors = doc.select("div.L b a[href]")
            .ifEmpty { doc.select("div.L a[href]") } // fallback if <b> wrapper missing

        return anchors.mapNotNull { it.toSearchResult(base) }
    }

    private fun Element.toSearchResult(base: String): SearchResponse? {
        val href = absolute(base, this.attr("href")) ?: return null

        // Title = anchor text, stripped of the leading arrow-icon image alt/whitespace.
        val title = this.text().trim().ifBlank { return null }

        val poster = this.selectFirst("img")?.let { absolute(base, it.attr("src")) }

        val type = when {
            title.contains("Web Series", ignoreCase = true) -> TvType.TvSeries
            title.contains("Short Film", ignoreCase = true) -> TvType.NSFW
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    // ---------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getActiveBaseUrl()
        val url = "$base/search.php?search=${query.replace(" ", "+")}&cat=All"
        val doc = app.get(url, timeout = 15).document
        return parseFolderListing(doc, base)
    }

    // ---------------------------------------------------------------------
    // Load (detail page)
    // ---------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse {
        val base = getActiveBaseUrl()
        val doc = app.get(url, timeout = 15).document

        val title = extractTitle(doc)
        val poster = doc.selectFirst("div.movielist img")?.let { absolute(base, it.attr("src")) }
        val description = extractStory(doc)
        val tags = extractTags(doc)
        val videoLinks = extractRawLinks(doc)

        // Bundle every raw (possibly relative-to-nothing, always http(s)) link into
        // one pipe-delimited payload string that loadLinks will split back apart.
        val data = videoLinks.joinToString("||")

        val isSeries = title.contains("Web Series", ignoreCase = true) ||
            url.contains("/webseries/", ignoreCase = true)

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, data) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    /**
     * Title lives in an un-classed `<b>` tag, which is fragile against index
     * selectors. We instead grab the first reasonably long, non-menu `<b>`
     * near the top of the page.
     */
    private fun extractTitle(doc: Document): String {
        return doc.select("div b").firstOrNull { b ->
            val t = b.text().trim()
            t.length > 8 && !t.contains("Story", ignoreCase = true) &&
                !t.contains("Download", ignoreCase = true) == false // keep simple heuristic
        }?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()
            ?: "Unknown title"
    }

    /**
     * Story block: <b>Story : </b> followed by plain text in the same
     * container, e.g. div:nth-child(8) under the info `center` block.
     */
    private fun extractStory(doc: Document): String? {
        val storyLabel = doc.select("b").firstOrNull {
            it.text().trim().startsWith("Story", ignoreCase = true)
        } ?: return null

        val container = storyLabel.parent() ?: return null
        val fullText = container.text().trim()
        val idx = fullText.indexOf(":")
        return if (idx != -1) fullText.substring(idx + 1).trim() else fullText
    }

    /**
     * Genre tags: <a href="/search.php?search= Comedy , Horror , Thriller&cat=All">
     * Text needs trimming of stray spaces around each comma-separated term.
     */
    private fun extractTags(doc: Document): List<String> {
        val genreAnchor = doc.selectFirst("div.L span a[href*=search.php]")
            ?: doc.selectFirst("span a[href*=search.php]")
            ?: return emptyList()

        return genreAnchor.text()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Pulls every non-empty http(s) href out of the download/watch block.
     * That block is a `div.Bolly` (per the sample HTML) full of mostly-empty
     * <a href=""></a> spacer tags interleaved with the real quality links.
     */
    private fun extractRawLinks(doc: Document): List<String> {
        val container = doc.selectFirst("div.Bolly")
            ?: doc.selectFirst("center div") // loose fallback
            ?: doc

        return container.select("a[href]")
            .map { it.attr("href").trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
    }

    // ---------------------------------------------------------------------
    // Link resolution
    // ---------------------------------------------------------------------

    // Hosts we know how to hand off to Cloudstream's built-in / community extractors.
    private val directHostHints = listOf(
        "streamtape", "advtpe", "tpead",
        "dood", "dooood", "dood.watch", "dood.to",
        "hubcloud", "hubdrive",
        "gdflix", "gofile", "filepress",
        "voe", "dsvplay", "vikingfile", "uploadhub"
    )

    // Hosts that are actually "link hub" blog pages containing further
    // streaming/download links buried among unrelated ad/redirect links.
    private val hubPageHints = listOf(
        "howblog", "blogspot", "blogger.com"
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawLinks = data.split("||").map { it.trim() }.filter { it.isNotBlank() }
        if (rawLinks.isEmpty()) return false

        var foundAny = false

        for (link in rawLinks) {
            try {
                when {
                    isHubPage(link) -> {
                        foundAny = resolveHubPage(link, subtitleCallback, callback) || foundAny
                    }
                    isDirectHost(link) -> {
                        loadExtractor(link, link, subtitleCallback, callback)
                        foundAny = true
                    }
                    else -> {
                        // Unknown host: still attempt, loadExtractor no-ops safely
                        // if it doesn't recognize the domain.
                        val before = foundAny
                        loadExtractor(link, link, subtitleCallback, callback)
                        foundAny = before || true
                    }
                }
            } catch (_: Exception) {
                // Skip bad/dead links, keep processing the rest.
            }
        }

        return foundAny
    }

    private fun isDirectHost(url: String) =
        directHostHints.any { url.contains(it, ignoreCase = true) }

    private fun isHubPage(url: String) =
        hubPageHints.any { url.contains(it, ignoreCase = true) }

    /**
     * "howblog"-style pages are an intermediate wall of links: some are
     * ad/payroll-timer redirects, some are the actual streaming/download
     * hosts we care about. We fetch the page and only forward links whose
     * domain matches our known host list.
     */
    private suspend fun resolveHubPage(
        hubUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = try {
            app.get(hubUrl, timeout = 15).document
        } catch (_: Exception) {
            return false
        }

        val candidateLinks = doc.select("a[href^=http]")
            .map { it.attr("href").trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .filter { isDirectHost(it) }

        var found = false
        for (link in candidateLinks) {
            try {
                loadExtractor(link, hubUrl, subtitleCallback, callback)
                found = true
            } catch (_: Exception) {
                // skip individual dead extractor links
            }
        }
        return found
    }
}
