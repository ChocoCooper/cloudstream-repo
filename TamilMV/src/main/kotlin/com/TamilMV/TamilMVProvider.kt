// TamilMVProvider.kt
package com.tamilmv

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import java.net.URLEncoder

class TamilMV : MainAPI() {

    override var mainUrl = "https://www.1tamilmv.capital"
    override var name = "TamilMV"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "top_releases"   to "Top Releases This Week",
        "recently_added" to "Recently Added"
    )

    // ────────────────────────────────────────────────────────────
    // HOME PAGE
    // ────────────────────────────────────────────────────────────
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(mainUrl).document
        val items = when (request.data) {
            "top_releases"   -> parseBangerSection(doc, "TOP RELEASES THIS WEEK")
            "recently_added" -> parseBangerSection(doc, "RECENTLY ADDED")
            else -> emptyList()
        }
        return newHomePageResponse(request.name, items)
    }

    /**
     * Both home sections share the same `.banger-container` widget.
     * We match by the header text to keep them separate.
     */
    private fun parseBangerSection(doc: Document, headerKeyword: String): List<SearchResponse> {
        val out = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        doc.select("div.banger-container").forEach { container ->
            val header = container.selectFirst("div.banger-header")?.text()?.trim().orEmpty()
            if (!header.contains(headerKeyword, ignoreCase = true)) return@forEach

            val row = container.selectFirst("div.banger-row") ?: return@forEach

            // Every topic is wrapped in <strong><a href=".../forums/topic/...">Title</a></strong>
            row.select("a[href*=/forums/topic/]").forEach { a ->
                val href  = a.attr("href").trim()
                if (href.isBlank()) return@forEach
                val url   = if (href.startsWith("http")) href else "$mainUrl$href"
                if (!seen.add(url)) return@forEach

                val title = a.text().trim().ifEmpty { return@forEach }
                out.add(
                    newMovieSearchResponse(title, url, TvType.Movie) {
                        // Poster comes from the media page (see load())
                        this.posterUrl = null
                    }
                )
            }
        }
        return out
    }

    // ────────────────────────────────────────────────────────────
    // SEARCH  →  /search/?q=<query>&direct=1
    // ────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search/?q=$q&direct=1"
        Log.d(TAG, "search: $searchUrl")
        val doc = app.get(searchUrl).document
        return parseSearchResults(doc, searchUrl)
    }

    private fun parseSearchResults(doc: Document, baseUrl: String): List<SearchResponse> {
        val out  = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        fun addItem(href: String, title: String) {
            if (!href.contains("/forums/topic/")) return
            if (title.isBlank() || title.length < 5) return
            val url = if (href.startsWith("http")) href else "$mainUrl$href"
            if (!seen.add(url)) return
            out.add(newMovieSearchResponse(title, url, TvType.Movie) {})
        }

        // Strategy A — sRow template (custom search page)
        doc.select("a.sRow[href]").forEach { row ->
            val href  = row.attr("href")
            val title = row.selectFirst(".sTitle")?.text()?.trim()
                ?: row.selectFirst("h3, h4, strong")?.text()?.trim()
                ?: row.text().trim().take(180)
            addItem(href, title.removeSuffix("Direct Link").trim())
        }

        // Strategy B — standard IPS stream results
        if (out.isEmpty()) {
            doc.select(
                "li.ipsStreamItem h2 a, " +
                "li.ipsStreamItem a.ipsContained, " +
                "a.ipsDataItem_title[href*=forums/topic]"
            ).forEach { addItem(it.attr("href"), it.text().trim()) }
        }

        // Strategy C — plain topic links fallback
        if (out.isEmpty()) {
            doc.select("a[href*=forums/topic/]").forEach { a ->
                addItem(a.attr("href"), a.text().trim())
            }
        }

        Log.d(TAG, "search → ${out.size} results")
        return out
    }

    // ────────────────────────────────────────────────────────────
    // LOAD  →  Topic page: poster, plot, title
    // ────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val rawTitle = doc.selectFirst("h1.ipsType_pageTitle")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()?.trim()
            ?: "Unknown"
        val title = rawTitle.cleanTitle()

        // Poster: first large image inside the first post
        val poster = doc.selectFirst("article.cPost div[data-role=commentContent] img.ipsImage")
            ?.attr("src")
            ?.takeIf { it.startsWith("http") }

        // Plot: text inside first post
        val plot = doc.selectFirst("article.cPost div[data-role=commentContent]")
            ?.text()
            ?.trim()
            ?.take(800)

        // TV-series detection: "S01", "S01 EP", "S01E01", etc.
        val isSeries = SERIES_RX.containsMatchIn(title)

        return if (isSeries) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
                this.plot = plot
                this.episodes = listOf(
                    newEpisode(url) { this.name = "Play" }
                )
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // ────────────────────────────────────────────────────────────
    // LOAD LINKS  →  resolve cyberloom → messycloud → juicybits
    // ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // Collect every cyberloom short link from the first post
        val cyberLinks = linkedSetOf<String>()

        doc.select("a.download-button, a[href*=cyberloom]").forEach { a ->
            val href = a.attr("href").trim()
            if (CYBERLOOM_RX.containsMatchIn(href)) cyberLinks.add(href)
        }
        // Raw HTML fallback (some buttons inject href via JS)
        if (cyberLinks.isEmpty()) {
            CYBERLOOM_RX.findAll(doc.html()).forEach { cyberLinks.add(it.value) }
        }

        Log.d(TAG, "cyberloom candidates: ${cyberLinks.size}")

        var any = false
        for (cyber in cyberLinks) {
            try {
                val direct = resolveCyberloom(cyber)
                if (direct.isNullOrBlank()) continue

                // Extract a "quality label" from the direct link or filename
                val label = detectLabel(direct)
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = "TamilMV • $label",
                        url    = direct,
                        type   = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = detectQuality(direct)
                    }
                )
                any = true
            } catch (e: Exception) {
                Log.e(TAG, "resolve failed for $cyber: ${e.message}")
            }
        }
        return any
    }

    // ────────────────────────────────────────────────────────────
    // CYBERLOOM CHAIN RESOLVER
    //
    //   cyberloom.best/l/xxx
    //     ↓ (302)
    //   {inkvoyage|stackmint}.xyz/go?t=…
    //     ↓ plaintext <a id="cta" href="…/out?t=…">
    //   cyberloom.best/out?t=…
    //     ↓ (302)
    //   messycloud.ink/<hash>
    //     ↓ plaintext
    //   https://cdn.juicybits.site/files/…mkv?token=…&exp=…
    // ────────────────────────────────────────────────────────────
    private suspend fun resolveCyberloom(cyberUrl: String): String? {
        // Hop 1: follow redirects to the gateway page
        val hop1 = app.get(cyberUrl)
        var currentDoc  = hop1.document
        var currentUrl  = hop1.url

        Log.d(TAG, "hop1 → $currentUrl")

        // If the redirect already landed on messycloud, extract immediately
        if (currentUrl.contains("messycloud")) return extractFromMessycloud(currentDoc)

        // Hop 2: parse the CTA href
        val ctaHref = currentDoc.selectFirst("a#cta")?.attr("href")
            ?: currentDoc.selectFirst("a[href*=/out?t=]")?.attr("href")
            ?: currentDoc.selectFirst("a[href*=cyberloom.best/out]")?.attr("href")

        if (ctaHref.isNullOrBlank()) {
            Log.d(TAG, "no CTA on $currentUrl")
            return null
        }

        val ctaUrl = when {
            ctaHref.startsWith("http")       -> ctaHref
            ctaHref.startsWith("//")         -> "https:$ctaHref"
            else                             -> "https://www.cyberloom.best$ctaHref"
        }.replace("&amp;", "&")

        Log.d(TAG, "cta → $ctaUrl")

        // Hop 3: follow the out link → messycloud
        val hop3 = app.get(ctaUrl)
        Log.d(TAG, "hop3 → ${hop3.url}")

        // Sometimes the /out endpoint returns 200 with a JS redirect;
        // scan its HTML for a location.assign / location.href first.
        val jsRedirect = JS_REDIRECT_RX.find(hop3.text)?.groupValues?.get(1)
        val messyDoc = if (jsRedirect != null && jsRedirect.contains("messycloud")) {
            Log.d(TAG, "js redirect → $jsRedirect")
            app.get(jsRedirect).document
        } else {
            hop3.document
        }

        return extractFromMessycloud(messyDoc)
    }

    /**
     * MessyCloud: find any direct CDN URL. The page has multiple anchors
     * (`class="download-button"`) — grab the first juicybits one.
     */
    private fun extractFromMessycloud(doc: Document): String? {
        // Anchor pass
        doc.select("a[href]").forEach { a ->
            val h = a.attr("href").trim()
            if (isDirectDownloadUrl(h)) return h
        }
        // Raw HTML sweep
        CDN_RX.find(doc.html())?.let { return it.value }
        return null
    }

    // ────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────
    private fun isDirectDownloadUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (url.contains("juicybits")) return true
        if (url.contains("cdn.") && FILE_EXT_RX.containsMatchIn(url)) return true
        return FILE_EXT_RX.containsMatchIn(url)
    }

    private fun detectQuality(url: String): Int = when {
        url.contains("2160p", true) || url.contains("4K",   true) -> Qualities.P2160.value
        url.contains("1080p", true)                                 -> Qualities.P1080.value
        url.contains("720p",  true)                                 -> Qualities.P720.value
        url.contains("480p",  true)                                 -> Qualities.P480.value
        url.contains("360p",  true)                                 -> Qualities.P360.value
        else                                                        -> Qualities.Unknown.value
    }

    private fun detectLabel(url: String): String = when {
        url.contains("2160p", true) || url.contains("4K", true) -> "4K"
        url.contains("1080p", true)                               -> "1080p"
        url.contains("720p",  true)                               -> "720p"
        url.contains("480p",  true)                               -> "480p"
        else                                                      -> "Direct"
    }

    private fun String.cleanTitle(): String = this
        .replace(Regex("""\s*\|\s*x264.*$"""), "")
        .replace(Regex("""\s*-\s*ESub.*$"""), "")
        .trim()

    companion object {
        private const val TAG = "TamilMV"

        private val CYBERLOOM_RX = Regex(
            """https?://(?:www\.)?cyberloom\.best/l/[A-Za-z0-9]+"""
        )
        private val CDN_RX = Regex(
            """https?://[^\s"'<>`\\]*?juicybits[^\s"'<>`\\]*""",
            RegexOption.IGNORE_CASE
        )
        private val FILE_EXT_RX = Regex(
            """\.(mkv|mp4|avi|mov|zip|rar|7z|webm|m4v)(\?|$)""",
            RegexOption.IGNORE_CASE
        )
        private val SERIES_RX = Regex(
            """\bS\d{1,2}\s*(EP|E)\s*\d|\bS\d{1,2}\b""",
            RegexOption.IGNORE_CASE
        )
        private val JS_REDIRECT_RX = Regex(
            """location\s*(?:\.\s*href\s*=\s*|\.replace\s*\(\s*|\.assign\s*\(\s*)["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
    }
}
