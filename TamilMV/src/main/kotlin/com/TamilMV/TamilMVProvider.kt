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

    override var mainUrl  = "https://www.1tamilmv.fi"
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
    private val titleCache  = ConcurrentHashMap<String, String>()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(mainUrl).document
        val items = when (request.data) {
            "top_releases"   -> parseBangerSection(doc, "TOP RELEASES THIS WEEK").take(HOME_LIMIT)
            "recently_added" -> parseBangerSection(doc, "RECENTLY ADDED").take(HOME_LIMIT)
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
                val href = a.attr("href").trim()
                if (href.isBlank()) return@forEach

                val url = if (href.startsWith("http")) href else "$mainUrl$href"
                if (!seen.add(url)) return@forEach

                val title = extractTitleFromAnchor(a) ?: return@forEach
                if (title.contains("[W]") || title.trim() == "[W]") return@forEach

                val cached = titleCache[url]
                out.add(
                    newMovieSearchResponse(cached ?: title.toShortTitle(), url, TvType.Movie) {
                        this.posterUrl = posterCache[url]
                    }
                )
            }
        }
        return out
    }

    private fun extractTitleFromAnchor(a: Element): String? {
        val anchorText = a.text().trim().trim('\u00A0', '\u200B')
        val normalized = anchorText.replace(Regex("""\s+"""), " ").trim()

        if (normalized.isEmpty() || normalized == "[W]") return null

        if (normalized.length >= 5 && !normalized.startsWith("[")) {
            return normalized
        }

        val strongParent = a.parent()?.takeIf { it.tagName() == "strong" }
        if (strongParent != null) {
            val full = strongParent.text().replace(Regex("""\s+"""), " ").trim()
            if (full.length > normalized.length + 3) {
                val clean = full
                    .replace(normalized, "")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                    .trimEnd('-', '–', '—', ' ', '.')
                    .trim()
                if (clean.length in 5..300 && !clean.startsWith("[") && !clean.contains("[W]")) {
                    return clean
                }
            }
        }

        var node: Element? = a
        for (level in 0 until 3) {
            if (node == null) break
            var sib = node.previousElementSibling()
            while (sib != null) {
                val text = sib.text().replace(Regex("""\s+"""), " ").trim()
                if (text.length >= 5 && !text.startsWith("[") && !text.contains("[W]")) {
                    val clean = text.substringBefore("[")
                        .trim()
                        .trimEnd('-', '–', '—', ' ', '.')
                        .trim()
                    if (clean.length in 5..300) return clean
                }
                sib = sib.previousElementSibling()
            }
            node = node.parent()
        }

        return null
    }

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
                val cached = titleCache[url]
                out.add(
                    newMovieSearchResponse(cached ?: title.toShortTitle(), url, TvType.Movie) {
                        this.posterUrl = posterCache[url]
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse failed: ${e.message}")
        }
        return out
    }

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
        titleCache[url] = shortTitle

        val poster = extractPoster(doc)
        if (poster != null) posterCache[url] = poster

        return newMovieLoadResponse(shortTitle, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot      = fullTitle
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        val candidates = mutableListOf<Pair<String, BlockInfo>>()
        val seen       = mutableSetOf<String>()

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

                val sizeLabel = info.size ?: detectSizeFromUrl(direct)
                if (sizeLabel.isNullOrBlank()) {
                    Log.d(TAG, "skip unknown-size source: $direct")
                    continue
                }

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

    private fun extractPoster(doc: Document): String? {
        val content = doc.selectFirst("article.cPost div[data-role=commentContent]")
            ?: doc.selectFirst("div[data-role=commentContent]")
            ?: return null

        val skipFragments = listOf(
            "spacer", "torrborder", "utorrent",
            "smiley", "blank", "emoji", "emoticon"
        )

        fun isDecorative(url: String): Boolean {
            val l = url.lowercase()
            if (skipFragments.any { l.contains(it) }) return true
            if (l.endsWith(".gif")) return true
            return false
        }

        fun urlFromImg(img: Element): String? {
            for (attr in listOf("data-src", "data-original", "src")) {
                val v = img.attr(attr).trim()
                if (v.isEmpty()) continue
                val n = normalizeUrl(v) ?: continue
                if (isDecorative(n)) continue
                return n
            }
            return null
        }

        content.select("p a").forEach { a ->
            val img = a.selectFirst("img") ?: return@forEach
            if (img.hasAttr("data-emoticon")) return@forEach
            urlFromImg(img)?.let { return it }
        }

        content.selectFirst(
            "img.ipsImage, img.ipsImage_thumbnailed, img.ipsImage_thumbnailed_colorized"
        )?.let { img ->
            if (!img.hasAttr("data-emoticon")) {
                urlFromImg(img)?.let { return it }
            }
        }

        content.selectFirst("img[src], img[data-src]")?.let { img ->
            if (!img.hasAttr("data-emoticon")) {
                urlFromImg(img)?.let { return it }
            }
        }

        return null
    }

    private suspend fun resolveDownloadChain(link: String): String? {
        val hop1 = app.get(link)
        val doc1 = hop1.document
        val url1 = hop1.url

        Log.d(TAG, "hop1 → $url1")

        extractDirectFromPage(doc1)?.let { return it }

        val ctaHref = doc1.selectFirst("a#cta[href]")?.attr("href")?.trim()
            ?: doc1.selectFirst("a[href*=/out?]")?.attr("href")?.trim()

        if (ctaHref.isNullOrBlank()) {
            Log.d(TAG, "no CTA on $url1")
            return null
        }

        val ctaUrl = normalizeUrl(ctaHref)
            ?: if (ctaHref.startsWith("/")) {
                val base = runCatching {
                    java.net.URI(url1).let { "${it.scheme}://${it.host}" }
                }.getOrNull() ?: mainUrl
                "$base$ctaHref"
            } else null

        if (ctaUrl == null) {
            Log.d(TAG, "unparseable CTA: $ctaHref")
            return null
        }

        Log.d(TAG, "cta → $ctaUrl")

        val hop3 = app.get(ctaUrl)
        Log.d(TAG, "hop3 → ${hop3.url}")

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

    private fun extractDirectFromPage(doc: Document): String? {
        doc.select("a[href]").forEach { a ->
            val href = normalizeUrl(a.attr("href")) ?: return@forEach
            if (FILE_EXT_RX.containsMatchIn(href)) return href
        }

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

        FILE_EXT_URL_RX.find(doc.html())?.value?.let { return it }

        return null
    }

    private suspend fun enrichWithPosters(items: List<SearchResponse>): List<SearchResponse> =
        coroutineScope {
            if (items.isEmpty()) return@coroutineScope items
            val sem = Semaphore(6)
            items.map { item ->
                async {
                    sem.withPermit {
                        var poster = posterCache[item.url]
                        var title  = titleCache[item.url]

                        if (poster == null || title == null) {
                            try {
                                val doc = app.get(item.url).document

                                if (poster == null) {
                                    val p = extractPoster(doc)
                                    if (p != null) {
                                        poster = p
                                        posterCache[item.url] = p
                                    }
                                }

                                if (title == null) {
                                    val h1 = doc.selectFirst("h1.ipsType_pageTitle")
                                        ?.text()
                                        ?.replace(Regex("""\s+"""), " ")
                                        ?.trim()
                                    if (!h1.isNullOrBlank()) {
                                        val canonical = h1.toShortTitle()
                                        titleCache[item.url] = canonical
                                        title = canonical
                                    }
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "enrich failed for ${item.url}: ${e.message}")
                            }
                        }

                        val originalName = item.name
                        val finalTitle = title
                            ?: originalName
                            ?: return@withPermit item

                        val titleChanged  = finalTitle != originalName
                        val posterChanged = poster != item.posterUrl

                        if (titleChanged || posterChanged) {
                            if (titleChanged) {
                                Log.d(TAG, "title refine: '$originalName' → '$finalTitle'")
                            }
                            newMovieSearchResponse(finalTitle, item.url, TvType.Movie) {
                                this.posterUrl = poster
                            }
                        } else {
                            item
                        }
                    }
                }
            }.awaitAll()
        }

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
        private const val HOME_LIMIT  = 8

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
        private val FILE_EXT_RX = Regex(
            """\.(mkv|mp4|avi|mov|zip|rar|7z|webm|m4v)(\?|$)""",
            RegexOption.IGNORE_CASE
        )
        private val FILE_EXT_URL_RX = Regex(
            """https?://[^\s"'<>`\\]+?\.(?:mkv|mp4|avi|mov|zip|rar|7z|webm|m4v)(?:\?[^\s"'<>`\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        private val JS_REDIRECT_RX = Regex(
            """location\s*(?:\.\s*href\s*=\s*|\.replace\s*\(\s*|\.assign\s*\(\s*)["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
    }
}
