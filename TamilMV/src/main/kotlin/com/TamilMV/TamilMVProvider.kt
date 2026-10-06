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

    /** Cache: topic URL → poster URL. Survives across navigation. */
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
        return newHomePageResponse(request.name, enrichWithPosters(items))
    }

    private fun parseBangerSection(doc: Document, headerKeyword: String): List<SearchResponse> {
        val out  = mutableListOf<SearchResponse>()
        val seen = mutableSetOf<String>()

        doc.select("div.banger-container").forEach { container ->
            val header = container.selectFirst("div.banger-header")?.text()?.trim().orEmpty()
            if (!header.contains(headerKeyword, ignoreCase = true)) return@forEach

            val row = container.selectFirst("div.banger-row") ?: return@forEach

            row.select("a[href*=/forums/topic/]").forEach { a ->
                val href = a.attr("href").trim()
                if (href.isBlank()) return@forEach

                val url = if (href.startsWith("http")) href else "$mainUrl$href"
                if (!seen.add(url)) return@forEach

                val fullTitle = a.text().trim().ifEmpty { return@forEach }
                out.add(
                    newMovieSearchResponse(fullTitle.toShortTitle(), url, TvType.Movie) {
                        this.posterUrl = posterCache[url]
                    }
                )
            }
        }
        return out
    }

    // ────────────────────────────────────────────────────────────
    // SEARCH  →  /search/api/search.php (JSON)
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
            Log.e(TAG, "raw head: ${json.take(400)}")
        }

        return out
    }

    // ────────────────────────────────────────────────────────────
    // LOAD
    //   name  == same trimmed title as home/search card
    //   plot  == full release title from <h1.ipsType_pageTitle>
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
    // LOAD LINKS
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

        doc.select("a.download-button, a[href*=cyberloom]").forEach { a ->
            val href = a.attr("href").trim()
            if (!CYBERLOOM_RX.containsMatchIn(href)) return@forEach
            if (!seen.add(href)) return@forEach

            val info = findBlockInfo(a)
            if (info.isOversized) {
                Log.d(TAG, "skip oversized block: $href  (${info.size})")
                return@forEach
            }
            candidates.add(href to info)
        }

        if (candidates.isEmpty()) {
            CYBERLOOM_RX.findAll(doc.html()).forEach { m ->
                if (seen.add(m.value)) candidates.add(m.value to BlockInfo(null, null, false))
            }
        }

        Log.d(TAG, "cyberloom candidates: ${candidates.size}")

        var any = false
        for ((cyber, info) in candidates) {
            try {
                val direct = resolveCyberloom(cyber) ?: continue

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
                Log.e(TAG, "resolve failed for $cyber: ${e.message}")
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
    // POSTER EXTRACTION
    //
    //   <article class="cPost">
    //     <div data-role="commentContent">
    //       <p>
    //         <span><strong>
    //           <a data-ipslightbox href="https://pbs.twimg.com/...">
    //             <img class="ipsImage ipsImage_thumbnailed" src="...">
    //           </a>
    //         </strong></span>
    //       </p>
    //     </div>
    //   </article>
    //
    //   Also handles:  img.ipsImage  |  img.ipsImage_thumbnailed  |
    //                  img.ipsImage_thumbnailed_colorized  |
    //                  img[src*="pbs.twimg"]  |  img[src*="pixelbb"]
    // ────────────────────────────────────────────────────────────
    private fun extractPoster(doc: Document): String? {
        val content = doc.selectFirst("article.cPost div[data-role=commentContent]")
            ?: doc.selectFirst("div[data-role=commentContent]")
            ?: return null

        // 1. Any <img> with an image class
        content.selectFirst(
            "img.ipsImage, img.ipsImage_thumbnailed, " +
            "img.ipsImage_thumbnailed_colorized, " +
            "img[src*=pbs.twimg], img[src*=pixelbb]"
        )?.let { img ->
            listOf("src", "data-src", "data-original")
                .asSequence()
                .map { img.attr(it).trim() }
                .firstOrNull { it.startsWith("http") }
                ?.let { return it }
        }

        // 2. Lightbox anchor wrapping an image
        content.selectFirst("a[data-ipslightbox], a[data-lightbox-group]")
            ?.attr("href")
            ?.trim()
            ?.takeIf { it.startsWith("http") && isImageUrl(it) }
            ?.let { return it }

        // 3. Regex sweep on the post HTML
        IMAGE_URL_RX.find(content.html())?.value?.let { return it }

        return null
    }

    private fun isImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("pbs.twimg.com") ||
               lower.contains("pixelbb") ||
               lower.contains("imgur") ||
               lower.contains("postimg") ||
               IMAGE_EXT_RX.containsMatchIn(lower)
    }

    // ────────────────────────────────────────────────────────────
    // Cyberloom resolver
    // ────────────────────────────────────────────────────────────
    private suspend fun resolveCyberloom(cyberUrl: String): String? {
        val hop1 = app.get(cyberUrl)
        val doc1 = hop1.document
        val url1 = hop1.url

        Log.d(TAG, "hop1 → $url1")
        if (url1.contains("messycloud")) return extractFromMessycloud(doc1)

        val ctaHref = doc1.selectFirst("a#cta")?.attr("href")
            ?: doc1.selectFirst("a[href*=/out?t=]")?.attr("href")
            ?: doc1.selectFirst("a[href*=cyberloom.best/out]")?.attr("href")

        if (ctaHref.isNullOrBlank()) {
            Log.d(TAG, "no CTA on $url1")
            return null
        }

        val ctaUrl = when {
            ctaHref.startsWith("http") -> ctaHref
            ctaHref.startsWith("//")   -> "https:$ctaHref"
            else                       -> "https://www.cyberloom.best$ctaHref"
        }.replace("&amp;", "&")

        Log.d(TAG, "cta → $ctaUrl")

        val hop3 = app.get(ctaUrl)
        Log.d(TAG, "hop3 → ${hop3.url}")

        val jsRedirect = JS_REDIRECT_RX.find(hop3.text)?.groupValues?.get(1)
        val messyDoc = if (!jsRedirect.isNullOrBlank() && jsRedirect.contains("messycloud")) {
            Log.d(TAG, "js redirect → $jsRedirect")
            app.get(jsRedirect).document
        } else {
            hop3.document
        }

        return extractFromMessycloud(messyDoc)
    }

    private fun extractFromMessycloud(doc: Document): String? {
        doc.select("a[href]").forEach { a ->
            val h = a.attr("href").trim()
            if (isDirectDownloadUrl(h)) return h
        }
        CDN_RX.find(doc.html())?.let { return it.value }
        return null
    }

    // ────────────────────────────────────────────────────────────
    // Poster enrichment (parallel, cached)
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

    /**
     * Build an IPB topic URL that the site accepts.
     *
     *   tid   = 199155
     *   title = "Vishwanath and Sons (2026) Tamil Audio launch TRUE WEB-DL - [1080p & 720p - AVC - 2.9GB - 1.3GB & 500MB]"
     *   →
     *   https://www.1tamilmv.capital/index.php?/forums/topic/199155-vishwanath-and-sons-2026-tamil-audio-launch-true-web-dl-1080p-720p-avc-29gb-13gb-500mb/
     *
     * Slug rules (verified against live URLs):
     *   • lowercase
     *   • every non-[a-z0-9.] char → '-'
     *   • every '.' removed
     *   • collapse runs of '-'
     *   • strip leading/trailing '-'
     */
    private fun buildTopicUrl(tid: Int, title: String): String {
        val slug = title
            .lowercase()
            .replace(Regex("[^a-z0-9.]+"), "-")
            .replace(".", "")
            .replace(Regex("-+"), "-")
            .trim('-')
        return "$mainUrl/index.php?/forums/topic/$tid-$slug/"
    }

    private fun isDirectDownloadUrl(url: String): Boolean {
        if (!url.startsWith("http")) return false
        if (url.contains("juicybits")) return true
        if (url.contains("cdn.") && FILE_EXT_RX.containsMatchIn(url)) return true
        return FILE_EXT_RX.containsMatchIn(url)
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

    /**
     * Trim "Title (YYYY)" plus an optional language + release-type suffix.
     *
     *   "Spider Man: Brand New Day (2026) (HD + Org Auds) - […]"
     *       → "Spider Man: Brand New Day (2026)"
     *
     *   "Meesaya Murukku 2 (2026) Tamil PreDVD - […]"
     *       → "Meesaya Murukku 2 (2026) • Tamil • PreDVD"
     *
     * Both home/search cards AND load-response name call this, so they always
     * resolve to the identical string.
     */
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

        /** Threshold in GB above which a block is skipped. */
        private const val OVERSIZE_GB = 10.0

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
        private val JS_REDIRECT_RX = Regex(
            """location\s*(?:\.\s*href\s*=\s*|\.replace\s*\(\s*|\.assign\s*\(\s*)["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
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
        private val IMAGE_EXT_RX = Regex(
            """\.(jpg|jpeg|png|webp|gif)(\?|$)""",
            RegexOption.IGNORE_CASE
        )
        private val IMAGE_URL_RX = Regex(
            """https?://(?:pbs\.twimg\.com[^\s"'<>`\\]+|[^\s"'<>`\\]+?\.(?:jpg|jpeg|png|webp|gif)(?:\?[^\s"'<>`\\]*)?)""",
            RegexOption.IGNORE_CASE
        )
    }
}
