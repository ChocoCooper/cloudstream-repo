package com.skybap

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

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

    // Marker used to smuggle the listing-page title through the URL that
    // Cloudstream hands back to load(). This guarantees the title shown on
    // the detail/media page is always exactly what was shown on the
    // homepage/search card, instead of being re-derived from the detail
    // page's own fragile (un-classed) title markup.
    private val titleMarker = "__sbTitle="

    private fun withEmbeddedTitle(href: String, title: String): String {
        val separator = if (href.contains("?")) "&" else "?"
        return "$href$separator$titleMarker${URLEncoder.encode(title, "UTF-8")}"
    }

    /** Splits an embedded-title URL back into (fetchable URL, title override). */
    private fun splitEmbeddedTitle(url: String): Pair<String, String?> {
        val parts = url.split(Regex("[?&]${Regex.escape(titleMarker)}"), limit = 2)
        val cleanUrl = parts.getOrNull(0) ?: url
        val title = parts.getOrNull(1)?.let {
            try {
                URLDecoder.decode(it, "UTF-8")
            } catch (_: Exception) {
                null
            }
        }
        return cleanUrl to title
    }

    /**
     * Runs [transform] over the receiver with at most [concurrency] requests
     * in flight at once. Used so homepage/search listings (which need one
     * extra request per item to fetch a real poster) don't fire dozens of
     * simultaneous requests at the target site.
     */
    private suspend fun <T, R> List<T>.mapConcurrent(
        concurrency: Int = 8,
        transform: suspend (T) -> R
    ): List<R> = coroutineScope {
        val semaphore = Semaphore(concurrency)
        map { item ->
            async { semaphore.withPermit { transform(item) } }
        }.awaitAll()
    }

    // Cached result of probing a listing item's detail page: whether it's
    // actually reachable, and its poster if so. Reused by both the listing
    // (for the poster) and, transitively, avoids re-fetching when load()
    // is called right after for the same URL.
    private data class DetailProbe(val reachable: Boolean, val poster: String?)

    private val detailProbeCache = HashMap<String, DetailProbe>()

    /**
     * Category/search listing pages only expose a small decorative arrow
     * icon next to each title (/images/arw.gif), not a real poster — the
     * actual poster only lives on the detail page (div.movielist img). To
     * show real posters in list views we fetch the detail page once per
     * item (cached) and pull the poster from there.
     *
     * This also checks the HTTP status explicitly: a 404/dead detail page
     * is resolved to "no poster" immediately instead of letting the image
     * loader hang or retry, and the timeout is kept short for the same
     * reason.
     */
    private suspend fun probeDetailPage(detailUrl: String): DetailProbe {
        detailProbeCache[detailUrl]?.let { return it }

        val probe = try {
            val response = app.get(detailUrl, timeout = 8)
            if (response.code !in 200..299) {
                DetailProbe(reachable = false, poster = null)
            } else {
                val base = getActiveBaseUrl()
                val src = response.document.selectFirst("div.movielist img")?.attr("src")?.trim()
                DetailProbe(reachable = true, poster = absolute(base, src))
            }
        } catch (_: Exception) {
            DetailProbe(reachable = false, poster = null)
        }

        detailProbeCache[detailUrl] = probe
        return probe
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

        // Confirmed from real page HTML: page 1 is the plain category URL
        // ("category/Tamil-Movies.html"); page 2+ follows the pattern
        // "category/Tamil-Movies/2.html" (site shows up to ~30 pages per
        // category via a "Next Page »" link at the bottom).
        val url = if (page <= 1) {
            "$base/${request.data}"
        } else {
            val slug = request.data.removePrefix("category/").removeSuffix(".html")
            "$base/category/$slug/$page.html"
        }

        val doc = app.get(url, timeout = 15).document
        val items = parseFolderListing(doc, base)

        // Confirmed from real page HTML: a "| Page 1 of 30 |" marker sits
        // above the pagination links. Parsing the total gives an exact
        // hasNext signal; falling back to "did this page return anything"
        // only if that marker is ever missing/changed.
        val totalPages = Regex("Page\\s+\\d+\\s+of\\s+(\\d+)")
            .find(doc.text())
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

        val hasNext = if (totalPages != null) page < totalPages else items.isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = hasNext
        )
    }

    /**
     * Folders on category / search pages are anchors sitting inside a
     * `div.L` block, e.g.
     *   <div class="L"><b><a href="/movie/....html">Title</a></b></div>
     */
    private suspend fun parseFolderListing(doc: Document, base: String): List<SearchResponse> {
        val anchors = doc.select("div.L b a[href]")
            .ifEmpty { doc.select("div.L a[href]") } // fallback if <b> wrapper missing

        // Bounded concurrency: each item needs one extra request to its
        // detail page to resolve a real poster and confirm it's alive
        // (see probeDetailPage).
        return anchors.mapConcurrent(concurrency = 8) { it.toSearchResult(base) }
            .filterNotNull()
    }

    private suspend fun Element.toSearchResult(base: String): SearchResponse? {
        val href = absolute(base, sanitizeHeaderValue(this.attr("href"))) ?: return null

        // Title = anchor text, stripped of the leading arrow-icon image alt/whitespace.
        val title = this.text().trim().ifBlank { return null }

        // NOTE: the listing page only has a decorative /images/arw.gif icon
        // next to the title, not a poster — the real poster lives on the
        // detail page, so we probe it there instead of reading this <img>.
        // If the detail page itself is dead (404/removed), drop the entry
        // entirely rather than showing a broken card that leads nowhere.
        val probe = probeDetailPage(href)
        if (!probe.reachable) return null

        val type = when {
            title.contains("Web Series", ignoreCase = true) -> TvType.TvSeries
            title.contains("Short Film", ignoreCase = true) -> TvType.NSFW
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, withEmbeddedTitle(href, title), type) {
            this.posterUrl = probe.poster
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
        val (cleanUrl, embeddedTitle) = splitEmbeddedTitle(url)
        val base = getActiveBaseUrl()
        val doc = app.get(cleanUrl, timeout = 15).document

        // Prefer the title already shown on the homepage/search card (passed
        // in via the URL) over re-deriving it from the detail page's own
        // un-classed markup - keeps the title consistent across both screens.
        val title = embeddedTitle ?: extractTitle(doc)
        val poster = doc.selectFirst("div.movielist img")?.let { absolute(base, it.attr("src").trim()) }
        val description = extractStory(doc)
        val tags = extractTags(doc)
        val videoLinks = extractRawLinks(doc)

        // Bundle every raw (possibly relative-to-nothing, always http(s)) link into
        // one pipe-delimited payload string that loadLinks will split back apart.
        val data = videoLinks.joinToString("||")

        // IMPORTANT: this site doesn't expose real per-episode pages even for
        // "Web Series" entries — one detail page just bundles every quality
        // link for the whole release. Returning a TvSeriesLoadResponse with
        // an empty episode list makes Cloudstream show "Coming Soon" with no
        // play button, regardless of whether real links exist. So everything
        // is loaded as a playable Movie response; the TvType tag on the
        // search result still reflects Web Series / Short Film for display
        // purposes, it just doesn't drive an empty episode list here.
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    /**
     * Only used as a fallback for stale bookmarks/history entries that
     * predate the embedded-title scheme above - normal navigation always
     * supplies embeddedTitle directly, so this path rarely runs.
     *
     * Verified against real page HTML: the title lives at
     * `<div class='Robiul'><b>{title}</b></div>`, appearing first among the
     * three separate `div.Robiul` elements on the page (the other two are
     * a "Movie Information" heading and a "Download {title}" line, both of
     * which come later in document order, so selectFirst naturally lands on
     * the right one without any extra filtering). The <title> tag (holding
     * "{title} Full Movie Download") is kept as a second fallback in case
     * div.Robiul's structure or class name ever changes.
     */
    private fun extractTitle(doc: Document): String {
        doc.selectFirst("div.Robiul b")?.text()?.trim()?.let { if (it.isNotBlank()) return it }

        val titleTag = doc.selectFirst("title")?.text()?.trim()
        if (!titleTag.isNullOrBlank()) {
            val stripped = titleTag
                .replace(Regex("\\s*Full Movie Download\\s*$", RegexOption.IGNORE_CASE), "")
                .trim()
            return stripped.ifBlank { titleTag }
        }

        // Last-resort fallback if even the <title> tag is missing.
        return doc.select("div b").firstOrNull { b ->
            val t = b.text().trim()
            t.length > 8 &&
                !t.contains("Story", ignoreCase = true) &&
                !t.contains("Download", ignoreCase = true) &&
                !t.contains("SkymoviesHD", ignoreCase = true) &&
                !t.contains("Full Movies", ignoreCase = true)
        }?.text()?.trim() ?: "Unknown title"
    }

    /**
     * Story block, confirmed against real page HTML:
     * `<div class='Let'><b>Story : </b>actual description text</div>`
     * - a flat div, not a numbered/nested one. Note the site itself
     * truncates long descriptions with a trailing "..." in the source
     * HTML; that's a site-side limitation, not something a better
     * selector can recover.
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
     * Genre tags, confirmed against real page HTML:
     * `<div class='L'><b>Genre :</b><span><a href="/search.php?search=18+, Hot, Romance, Erotic&cat=All"> 18+, Hot, Romance, Erotic</a>, <a href="/search.php?search=&cat=All"> </a>, ...</span></div>`
     * The real genre text is always the first `search.php` anchor; the
     * remaining anchors in that span are empty decorative ones. Note a
     * second, unrelated `div.L` also exists further down the page (wrapping
     * the screenshot gallery), but since it has no `a[href*=search.php]`
     * inside it, selectFirst here can't accidentally match it.
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
            .map { sanitizeHeaderValue(it.attr("href")) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
    }

    // ---------------------------------------------------------------------
    // Link resolution
    // ---------------------------------------------------------------------

    /**
     * All the non-core hosts (hubcloud, vcloud, gdflix + mirrors, hubdrive,
     * driveleech/driveseed, gofile, howblogs) are registered as
     * ExtractorApi implementations by SkyBapPlugin.load(), and everything
     * else (streamtape, doodstream, voe, etc.) is already recognized by
     * Cloudstream's built-in extractor set. So loadExtractor() alone is
     * enough to route every link correctly - including "howblogs" pages,
     * whose own extractor internally re-calls loadExtractor() on whatever
     * real streaming/download links it finds on that page.
     *
     * Every resulting ExtractorLink/SubtitleFile is passed through a
     * sanitizing wrapper before reaching Cloudstream's player. Some source
     * pages (seen in the wild on StreamTape's own watch page) have a
     * filename/title with a raw embedded newline, and core extractors that
     * build their referer from that text pass the newline straight through.
     * Cronet then rejects the resulting HTTP header outright
     * ("Invalid header with headername: referer"), which silently kills
     * playback even though a valid link was actually found. This isn't
     * something we can fix at the source (it happens inside Cloudstream's
     * own StreamTape extractor), so we scrub every link that passes through
     * here regardless of which extractor produced it.
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawLinks = data.split("||").map { it.trim() }.filter { it.isNotBlank() }
        if (rawLinks.isEmpty()) return false

        val safeCallback: (ExtractorLink) -> Unit = { callback(sanitizeExtractorLink(it)) }
        val safeSubtitleCallback: (SubtitleFile) -> Unit = { subtitleCallback(sanitizeSubtitle(it)) }

        val outcomes = rawLinks.mapConcurrent(concurrency = 6) { link ->
            try {
                loadExtractor(link, link, safeSubtitleCallback, safeCallback)
                true
            } catch (_: Exception) {
                false
            }
        }

        return outcomes.any { it }
    }

    /** Strips control characters (\r, \n, \t) that would otherwise break HTTP headers. */
    private fun sanitizeHeaderValue(value: String): String =
        value.replace(Regex("[\\r\\n\\t]+"), "").trim()

    private fun sanitizeExtractorLink(link: ExtractorLink): ExtractorLink {
        val cleanedUrl = sanitizeHeaderValue(link.url)
        val cleanedReferer = sanitizeHeaderValue(link.referer)

        // Nothing to fix - return as-is rather than reconstructing needlessly.
        if (cleanedUrl == link.url && cleanedReferer == link.referer) return link

        return try {
            newExtractorLink(link.source, link.name, cleanedUrl, link.type) {
                this.quality = link.quality
                this.headers = link.headers
                this.referer = cleanedReferer
            }
        } catch (_: Exception) {
            // If reconstruction ever fails for any reason, fall back to the
            // original link rather than dropping it entirely.
            link
        }
    }

    private fun sanitizeSubtitle(sub: SubtitleFile): SubtitleFile {
        val cleanedUrl = sanitizeHeaderValue(sub.url)
        return if (cleanedUrl == sub.url) sub else SubtitleFile(sub.lang, cleanedUrl)
    }
}
