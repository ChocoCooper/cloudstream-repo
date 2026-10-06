// src/TamilMV/src/main/kotlin/com/TamilMV/TamilMVProvider.kt
package com.TamilMV

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class TamilMV : MainAPI() {

    override var mainUrl  = "https://www.1tamilmv.capital"
    override var name     = "TamilMV"
    override var lang     = "ta"
    override val hasMainPage        = true
    override val hasDownloadSupport = true
    override val supportedTypes     = setOf(TvType.Movie)

    override val mainPage = mainPageOf(
        "top_releases"   to "Top Releases This Week",
        "recently_added" to "Recently Added"
    )

    private val posterCache = ConcurrentHashMap<String, String>()

    // ────────────────────────────────────────────────────────────
    // HOME
    // ────────────────────────────────────────────────────────────
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(mainUrl).document
        val items = when (request.data) {
            "top_releases"   -> parseBangerSection(doc, "TOP RELEASES THIS WEEK")
            "recently_added" -> parseBangerSection(doc, "RECENTLY ADDED")
            else             -> emptyList()
        }
        Log.d(TAG, "${request.data}: ${items.size} items")
        return newHomePageResponse(request.name, enrichWithPosters(items))
    }

    private fun parseBangerSection(doc: Document, headerKeyword: String): List<SearchResponse> {
        val out  = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        doc.select("div.banger-container").forEach { container ->
            val header = container.selectFirst("div.banger-header")
                ?.text()?.trim().orEmpty()
            if (!header.contains(headerKeyword, ignoreCase = true)) return@forEach

            val scope = container.selectFirst("div.banger-row") ?: container

            scope.select("a[href*=/forums/topic/]").forEach { a ->
                val href = normalizeUrl(a.attr("href")) ?: return@forEach
                val url  = if (href.startsWith("http")) href else "$mainUrl$href"
                if (!seen.add(url)) return@forEach

                val title = extractTitleFromAnchor(a) ?: return@forEach
                out.add(
                    newMovieSearchResponse(title.toShortTitle(), url, TvType.Movie) {
                        this.posterUrl = posterCache[url]
                    }
                )
            }
        }
        return out
    }

    /** Extract the human title from around a topic anchor (see comments). */
    private fun extractTitleFromAnchor(a: Element): String? {
        val anchorText = a.text().trim().trim('\u00A0', '\u200B')
        val normalized = anchorText.replace(Regex("""\s+"""), " ").trim()

        if (normalized.length >= 5 && !normalized.startsWith("[")) {
            return normalized
        }

        // Title lives in an ancestor text, anchor is only the variant suffix
        var anc: Element? = a.parent()
        for (depth in 0 until 4) {
            if (anc == null) break
            val full = anc.text().replace(Regex("""\s+"""), " ").trim()
            if (full.length > normalized.length + 3) {
                val clean = full
                    .replace(normalized, "")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                    .trimEnd('-', '–', '—', ' ')
                    .trim()
                if (clean.length >= 5 && !clean.startsWith("[")) return clean
            }
            anc = anc.parent()
        }

        // Title lives in a sibling <strong>
        var node: Element? = a
        for (level in 0 until 4) {
            if (node == null) break
            var sib = node.previousElementSibling()
            while (sib != null) {
                val text = sib.text().replace(Regex("""\s+"""), " ").trim()
                if (text.length >= 5 && !text.startsWith("[")) {
                    val clean = text.substringBefore("[").trim()
                        .trimEnd('-', '–', '—', ' ').trim()
                    if (clean.length >= 5) return clean
                }
                sib = sib.previousElementSibling()
            }
            node = node.parent()
        }

        return null
    }

    // ────────────────────────────────────────────────────────────
    // SEARCH
    // ────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val q   = URLEncoder.encode(query, "UTF-8")
        val api = "$mainUrl/search/api/search.php" +
                  "?q=$q&direct=1&priority=1&sort=title_asc&page=1&per_page=25"

        Log.d(TAG, "search: $api")

        val json = try {
            app.get(
                api,
                referer = "$mainUrl/search/?q=$q&direct=1",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text
        } catch (e: Exception) {
            Log.e(TAG, "search request failed: ${e.message}")
            return emptyList()
        }

        val parsed = parseSearchJson(json)
        Log.d(TAG, "search → ${parsed.size} results")
        return enrichWithPosters(parsed)
    }

    private fun parseSearchJson(json: String): List<SearchResponse> {
        val out  = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<Int>()

        try {
            val root = JSONObject(json)
            val arr: JSONArray = root.optJSONArray("results") ?: return emptyList()

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val tid   = obj.optInt("tid", 0)
                val title = obj.optString("title").trim()
                if (tid <= 0 || title.isBlank()) continue
                if (!seen.add(tid)) continue

                val url = buildTopicUrl(tid, title)
                out.add(
                    newMovieSearchResponse(title.toShortTitle(), url, TvType.Movie) {
                        this.posterUrl = posterCache[url]
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message}")
        }
        return out
    }

    // ────────────────────────────────────────────────────────────
    // LOAD
    // ────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        val rawH1 = doc.selectFirst("h1.ipsType_pageTitle")
            ?.text()
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val fullTitle = rawH1
            ?: doc.selectFirst("title")?.text()
                ?.replace(Regex("""\s+"""), " ")
                ?.substringBefore(" - Hollywood Movies")
                ?.substringBefore(" - Tamil Language")
                ?.substringBefore(" - Telugu Language")
                ?.substringBefore(" - Hindi Language")
                ?.substringBefore(" - Malayalam Language")
                ?.substringBefore(" - Kannada Language")
                ?.substringBefore(" - English Language")
                ?.trim()
            ?: "Unknown"

        val shortTitle = fullTitle.toShortTitle()

        val poster = extractPoster(doc)
        if (poster != null) posterCache[url] = poster

        return newMovieLoadResponse(shortTitle, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = fullTitle
        }
    }

    // ────────────────────────────────────────────────────────────
    // LOAD LINKS  — pick any <a class="download-button" href="…">
    // No domain filtering: the site's own class is the contract.
    // ────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        val candidates = mutableListOf<Pair<String, BlockInfo>>()
        val seen       = mutableSetOf<String>()

        // Primary selector — the site's own download button class
        doc.select("a.download-button[href]").forEach { a ->
            val href = normalizeUrl(a.attr("href")) ?: return@forEach
            if (!seen.add(href)) return@forEach
            val info = findBlockInfo(a)
            if (info.isOversized) {
                Log.d(TAG, "skip oversized block: $href  (${info.size})")
                return@forEach
            }
            candidates.add(href to info)
        }

        // Fallback — anchors with a `download` attribute or that sit inside
        // an IPS download-ish container
        if (candidates.isEmpty()) {
            doc.select(
                "a[download][href], " +
                "[class*=download] a[href], " +
                "a[class*=download][href]"
            ).forEach { a ->
                val href = normalizeUrl(a.attr("href")) ?: return@forEach
                if (href == "#" || href.startsWith("javascript:")) return@forEach
                if (!seen.add(href)) return@forEach
                candidates.add(href to findBlockInfo(a))
            }
        }

        Log.d(TAG, "download candidates: ${candidates.size}")

        var any = false
        for ((link, info) in candidates) {
            try {
                val direct = resolveDownloadChain(link) ?: continue

                val sizeLabel = info.size
                    ?: detectSizeFromUrl(direct)
                    ?: "Direct"
                val quality = info.quality.toQualityValue()
                    .takeIf { it != Qualities.Unknown.value }
                    ?: detectQuality(direct)

                Log.d(TAG, "link → $sizeLabel  ($quality)  $direct")

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = "TamilMV • $sizeLabel",
                        url    = direct,
                        type   = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = quality
                    }
                )
                any = true
            } catch (e: Exception) {
                Log.e(TAG, "resolve failed for $link: ${e.message}")
            }
        }
        return any
    }

    // ────────────────────────────────────────────────────────────
    // Block info (size + resolution from the block header)
    // ────────────────────────────────────────────────────────────
    private data class BlockInfo(
        val size: String?,
        val quality: String?,
        val isOversized: Boolean
    )

    private fun findBlockInfo(anchor: Element): BlockInfo {
        var cur = anchor.previousElementSibling()
        var depth = 0
        while (cur != null && depth < 15) {
            if (cur.tagName() == "strong") {
                val text  = cur.text()
                val sizeM = SIZE_RX.find(text)?.value?.replace(" ", "")
                if (sizeM != null) {
                    return BlockInfo(
                        size        = sizeM,
                        quality     = RES_RX.find(text)?.groupValues?.get(1),
                        isOversized = isOversized(sizeM)
                    )
                }
            }
            cur = cur.previousElementSibling()
            depth++
        }
        return BlockInfo(null, null, false)
    }

    private fun isOversized(sizeLabel: String): Boolean {
        val m = SIZE_RX.find(sizeLabel) ?: return false
        val value = m.groupValues[1].toDoubleOrNull() ?: return false
        val unit  = m.groupValues[2].uppercase()
        val gb    = if (unit == "GB") value else value / 1024.0
        return gb >= OVERSIZE_GB
    }

    // ────────────────────────────────────────────────────────────
    // POSTER EXTRACTION  — 100 % DOM-driven
    //
    //   • First image with an IPS image class inside the first post.
    //   • Fallback: first non-emoticon <img src> inside the post.
    //   • Fallback: lightbox anchor href.
    //
    // No domain filter — any http(s) URL returned by these selectors
    // is assumed to be the poster, regardless of CDN.
    // ────────────────────────────────────────────────────────────
    private fun extractPoster(doc: Document): String? {
        val content = doc.selectFirst("article.cPost div[data-role=commentContent]")
            ?: doc.selectFirst("div[data-role=commentContent]")
            ?: return null

        // Preferred — IPS image class
        content.selectFirst(
            "img.ipsImage, img.ipsImage_thumbnailed, img.ipsImage_thumbnailed_colorized"
        )?.let { img ->
            firstHttpAttr(img, "src", "data-src", "data-original")
                ?.let { return it }
        }

        // Fallback — any img that is not a smiley
        content.select("img[src]").firstOrNull { img ->
            !img.hasAttr("data-emoticon")
        }?.let { img ->
            normalizeUrl(img.attr("src"))?.let { return it }
        }

        // Fallback — lightbox anchor
        content.selectFirst("a[data-ipslightbox][href], a[data-lightbox-group][href]")
            ?.attr("href")
            ?.let { normalizeUrl(it) }
            ?.let { return it }

        return null
    }

    private fun firstHttpAttr(el: Element, vararg attrs: String): String? {
        for (attr in attrs) {
            val v = el.attr(attr).trim()
            if (v.isEmpty()) continue
            normalizeUrl(v)?.let { return it }
        }
        return null
    }

    // ────────────────────────────────────────────────────────────
    // Download-chain resolver  — 100 % DOM-driven
    //
    //   media page  →  [link]  (whatever href the site put on the button)
    //     ↓ follow redirects / JS location assignments
    //   gateway page → <a id="cta" href="…">   (site-provided ID)
    //     ↓
    //   final page   → anchor with a media extension
    // ────────────────────────────────────────────────────────────
    private suspend fun resolveDownloadChain(link: String): String? {
        val hop1 = app.get(link)
        var doc  = hop1.document
        val url1 = hop1.url

        Log.d(TAG, "hop1 → $url1")

        // If hop1 already IS the final page (anchor with a media extension)
        extractDirectFromPage(doc)?.let { return it }

        // Gateway page: site-provided CTA id is stable across domains
        val ctaHref = doc.selectFirst("a#cta[href]")?.attr("href")?.trim()
            ?: doc.selectFirst("a[href*=/out?]")?.attr("href")?.trim()

        if (ctaHref.isNullOrBlank()) {
            Log.d(TAG, "no CTA on $url1")
            return null
        }

        val ctaUrl = normalizeUrl(ctaHref)
            ?: if (ctaHref.startsWith("/")) {
                val base = runCatching { java.net.URI(url1).let { "${it.scheme}://${it.host}" } }
                    .getOrNull() ?: mainUrl
                "$base$ctaHref"
            } else null

        if (ctaUrl == null) {
            Log.d(TAG, "unparseable CTA: $ctaHref")
            return null
        }

        Log.d(TAG, "cta → $ctaUrl")

        val hop3 = app.get(ctaUrl)
        Log.d(TAG, "hop3 → ${hop3.url}")

        // Follow any JS location redirect the page performs
        val jsUrl = JS_REDIRECT_RX.find(hop3.text)?.groupValues?.get(1)
            ?.let { normalizeUrl(it) }

        val pageDoc = if (jsUrl != null) {
            Log.d(TAG, "js redirect → $jsUrl")
            app.get(jsUrl).document
        } else {
            hop3.document
        }

        return extractDirectFromPage(pageDoc)
    }

    /**
     * Extract a media-file link from any page:
     *
     *   1. Anchors whose href ends with a known media extension.
     *   2. Anchors inside download-like containers.
     *   3. Regex sweep of the raw HTML for media URLs.
     *
     * No domain filter — only file extensions, which are stable.
     */
    private fun extractDirectFromPage(doc: Document): String? {
        // 1. Anchor with a media extension
        doc.select("a[href]").forEach { a ->
            val href = normalizeUrl(a.attr("href")) ?: return@forEach
            if (FILE_EXT_RX.containsMatchIn(href)) return href
        }

        // 2. Anchor inside a download-ish container
        doc.select(
            "a[download][href], " +
            "[class*=download] a[href], " +
            "a[class*=download][href], " +
            "[class*=btn] a[href]"
        ).forEach { a ->
            val href = normalizeUrl(a.attr("href")) ?: return@forEach
            if (href == "#" || href.startsWith("javascript:")) return@forEach
            return href
        }

        // 3. Regex sweep for a media URL anywhere in the HTML
        FILE_EXT_URL_RX.find(doc.html())?.value?.let { return it }

        return null
    }

    // ────────────────────────────────────────────────────────────
    // Poster enrichment
    // ────────────────────────────────────────────────────────────
    private suspend fun enrichWithPosters(items: List<SearchResponse>): List<SearchResponse> =
        coroutineScope {
            if (items.isEmpty()) return@coroutineScope items
            val sem = Semaphore(6)
            items.map { item ->
                async {
                    sem.withPermit {
                        posterCache[item.url]?.let {
                            item.posterUrl = it
                            return@withPermit item
                        }
                        try {
                            val doc    = app.get(item.url).document
                            val poster = extractPoster(doc)
                            if (poster != null) {
                                item.posterUrl = poster
                                posterCache[item.url] = poster
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "poster fetch failed for ${item.url}: ${e.message}")
                        }
                        item
                    }
                }
            }.awaitAll()
        }

    // ────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────

    /** Return an absolute http(s) URL, or null. */
    private fun normalizeUrl(u: String?): String? {
        val t = u?.trim() ?: return null
        if (t.isEmpty()) return null
        return when {
            t.startsWith("http://")  -> t
            t.startsWith("https://") -> t
            t.startsWith("//")       -> "https:$t"
            else                     -> null
        }
    }

    private fun buildTopicUrl(tid: Int, title: String): String {
        val slug = title
            .lowercase()
            .replace(Regex("[^a-z0-9.]+"), "-")
            .replace(".", "")
            .replace(Regex("-+"), "-")
            .trim('-')
        return "$mainUrl/index.php?/forums/topic/$tid-$slug/"
    }

    private fun String?.toQualityValue(): Int = when (this?.lowercase()) {
        "1080p" -> Qualities.P1080.value
        "720p"  -> Qualities.P720.value
        "480p"  -> Qualities.P480.value
        "360p"  -> Qualities.P360.value
        "240p"  -> Qualities.P240.value
        else    -> Qualities.Unknown.value
    }

    private fun detectQuality(url: String): Int = when {
        url.contains("1080p", true) -> Qualities.P1080.value
        url.contains("720p",  true) -> Qualities.P720.value
        url.contains("480p",  true) -> Qualities.P480.value
        url.contains("360p",  true) -> Qualities.P360.value
        url.contains("240p",  true) -> Qualities.P240.value
        else                        -> Qualities.Unknown.value
    }

    private fun detectSizeFromUrl(url: String): String? {
        val m = Regex("""[-_](\d+(?:\.\d+)?)\s*(GB|MB)(?:[_-]|\.|$)""", RegexOption.IGNORE_CASE)
            .find(url)
        return m?.let { "${it.groupValues[1]}${it.groupValues[2].uppercase()}" }
    }

    private fun String.toShortTitle(): String {
        val cleaned = this.replace(Regex("""\s+"""), " ").trim()
        val m = Regex("""^(.+?\(\d{4}(?:[–\-]\d{4})?\))\s*(.*)""").find(cleaned)
            ?: return cleaned.take(100)

        val base = m.groupValues[1].trim()
        val rest = m.groupValues[2].trim()
        if (rest.isBlank()) return base

        val lang = LANG_RX.find(rest)?.value
        val type = TYPE_RX.find(rest)?.value

        val parts = listOfNotNull(lang, type)
            .map { it.replace(Regex("""\s+"""), " ").trim() }
            .distinct()

        return if (parts.isEmpty()) base
               else "$base • ${parts.joinToString(" • ")}"
    }

    companion object {
        private const val TAG = "TamilMV"
        private const val OVERSIZE_GB = 10.0

        /** Content patterns — no domains. */
        private val SIZE_RX = Regex(
            """(\d+(?:\.\d+)?)\s*(GB|MB)""",
            RegexOption.IGNORE_CASE
        )
        private val RES_RX = Regex(
            """\b(1080p|720p|480p|360p|240p)\b""",
            RegexOption.IGNORE_CASE
        )
        private val LANG_RX = Regex(
            """\b(Tamil|Telugu|Hindi|Malayalam|Kannada|English|Multi)\b""",
            RegexOption.IGNORE_CASE
        )
        private val TYPE_RX = Regex(
            """\b(HQ\s+PreDVD|PreDVD|HQ\s+HDRip|HDRip|HQ\s+HDTS|HDTS|TRUE\s+WEB-DL|WEB-DL|WEBRip|BluRay|Audio\s+launch|HDTV|HQ\s+Clean)\b""",
            RegexOption.IGNORE_CASE
        )

        /** Media file extensions — stable identifiers, not domains. */
        private val FILE_EXT_RX = Regex(
            """\.(mkv|mp4|avi|mov|zip|rar|7z|webm|m4v)(\?|$)""",
            RegexOption.IGNORE_CASE
        )
        private val FILE_EXT_URL_RX = Regex(
            """https?://[^\s"'<>`\\]+?\.(?:mkv|mp4|avi|mov|zip|rar|7z|webm|m4v)(?:\?[^\s"'<>`\\]*)?""",
            RegexOption.IGNORE_CASE
        )

        /** Generic JS location redirect. */
        private val JS_REDIRECT_RX = Regex(
            """location\s*(?:\.\s*href\s*=\s*|\.replace\s*\(\s*|\.assign\s*\(\s*)["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
    }
}
