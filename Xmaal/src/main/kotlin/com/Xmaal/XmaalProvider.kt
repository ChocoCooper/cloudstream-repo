package com.Xmaal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.net.URLEncoder

/**
 * CloudStream provider for the Maza family of OTT aggregator sites.
 *
 * Two architectures are supported transparently:
 *   1. WordPress REST API mirrors (xmaza.xxx, uncutmaza.movie, ottdude.com,
 *      maalvdo.co, zmaal.net) — search + metadata come from `/wp-json/wp/v2/*`,
 *      stream URL comes from schema.org VideoObject JSON-LD on the media page.
 *   2. Next.js App Router mirror (xmaza2.net) — everything is extracted from
 *      the streaming RSC payload embedded in the initial HTML.
 *
 * All metadata is parsed as JSON (no regex) except for:
 *   - The RSC payload split (Next.js has no JSON alternative)
 *   - Episode-number parsing from titles (cosmetic, for sorting)
 *   - A scoped last-resort scan of <script> bodies if JSON-LD + DOM both fail
 */
class XmaalProvider : MainAPI() {

    // ────────────────────────────────────────────────────────────────────
    // Constants
    // ────────────────────────────────────────────────────────────────────
    companion object {
        private const val TIMEOUT_MS = 20_000L
        private const val PAGE_SIZE = 100

        // Canonical domains — every redirect already resolved.
        private const val OTTDUDE   = "https://ottdude.com"
        private const val MAALVDO   = "https://maalvdo.co"
        private const val XMAZA     = "https://xmaza.xxx"
        private const val ZMAAL     = "https://zmaal.net"
        private const val UNCUTMAZA = "https://uncutmaza.movie"
        private const val XMAZA2    = "https://xmaza2.net"

        // Cloudflare-safe headers. Some mirrors 403 without a real UA.
        private val HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            "Accept" to "application/json, text/html, */*",
            "Accept-Language" to "en-US,en;q=0.9",
        )

        // Regex used ONLY where no JSON alternative exists.
        private val RSC_CHUNK = Regex(
            """self\.__next_f\.push\(\s*\[\s*\d+\s*,\s*(".*?")\s*\]\s*\)""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val RSC_WATCH_SLUG = Regex("""/watch/([a-z0-9\-]+)""")
        private val RSC_IMAGE = Regex(
            """https?://[^\s"'\\<>]+?\.(?:webp|jpg|jpeg|png)(?:\?[^\s"'\\<>]*)?""",
            RegexOption.IGNORE_CASE
        )
        private val RSC_STREAM = Regex(
            """https?://[^\s"'<>\\]+?\.(?:mp4|m3u8)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
        private val SCRIPT_STREAM = Regex(
            """["'](https?://[^"']+?\.(?:mp4|m3u8)(?:\?[^"']*)?)["']""",
            RegexOption.IGNORE_CASE
        )

        // Title parsing for sorting / season grouping (cosmetic only)
        private val EPISODE_TITLE = Regex(
            """^(?<base>.+?)(?:\s+(?<season>\d+))?\s+Episode\s+(?<ep>\d+)\s*$""",
            RegexOption.IGNORE_CASE
        )
        private val TITLE_TRIM = Regex("""(?i)(?:[-:|–—]\s*)?\bEpisode\s*\d+\b""")

        // Quality hints in filenames/paths (best-effort)
        private val QUALITY_HINTS = listOf(
            "2160" to Qualities.P2160.value,
            "1440" to Qualities.P1440.value,
            "1080" to Qualities.P1080.value,
            "720"  to Qualities.P720.value,
            "576"  to Qualities.P576.value,
            "480"  to Qualities.P480.value,
            "360"  to Qualities.P360.value,
            "240"  to Qualities.P240.value,
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Provider metadata
    // ────────────────────────────────────────────────────────────────────
    override var mainUrl = XMAZA
    override var name = "Xmaza"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    /**
     * Mirror ordering matters. Unsigned-CDN mirrors respond fastest and never
     * expire; signed-CDN mirrors are kept as backups.
     */
    private val wpMirrors = listOf(XMAZA, UNCUTMAZA, OTTDUDE, MAALVDO, ZMAAL)
    private val rscMirrors = listOf(XMAZA2)
    private val allMirrors = wpMirrors + rscMirrors

    override val mainPage = mainPageOf(
        "$XMAZA/category/ullu/"          to "ULLU",
        "$XMAZA/category/atrangii/"      to "Atrangii",
        "$XMAZA/category/altt/"          to "ALTT",
        "$XMAZA/category/bigshots/"      to "BigShots",
        "$XMAZA/category/boom-movies/"   to "Boom Movies",
        "$XMAZA/category/besharams/"     to "Besharams",
        "$XMAZA/category/chikku/"        to "Chikku",
        "$XMAZA/category/bindastimes/"   to "BindasTimes",
    )

    // ────────────────────────────────────────────────────────────────────
    // Tiny helpers
    // ────────────────────────────────────────────────────────────────────
    private fun domainOf(url: String): String {
        val i = url.indexOf("//") + 2
        return if (i < 2) url
        else url.substring(0, i) + url.substring(i).substringBefore("/")
    }

    private fun hostOf(url: String): String =
        domainOf(url).removePrefix("https://").removePrefix("http://")

    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun normalizeTitle(t: String) =
        t.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun cleanTitle(t: String) = t
        .replace(TITLE_TRIM, "")
        .replace(Regex("""[\s\-_:|–—]+$"""), "")
        .replace(Regex("""^[\s\-_:|–—]+"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .ifBlank { t.trim() }

    private fun isStreamUrl(v: String?) =
        !v.isNullOrBlank() && v.startsWith("http") &&
                (v.contains(".mp4", true) || v.contains(".m3u8", true))

    private fun resolveHref(href: String, base: String) =
        when {
            href.startsWith("http") -> href
            href.startsWith("//")   -> "https:$href"
            href.startsWith("/")    -> domainOf(base) + href
            else                    -> base.trimEnd('/') + "/" + href
        }

    private fun inferQuality(url: String): Int {
        val u = url.lowercase()
        for ((hint, quality) in QUALITY_HINTS) if (hint in u) return quality
        return Qualities.Unknown.value
    }

    /**
     * Best-effort retry — some mirrors occasionally return 429/503 under load.
     */
    private suspend fun fetch(url: String): String? {
        repeat(3) { attempt ->
            try {
                return app.get(url, headers = HEADERS, timeout = TIMEOUT_MS).text
            } catch (_: Exception) {
                if (attempt < 2) delay(500L * (attempt + 1))
            }
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────────
    // JSON-LD stream extraction (WordPress mirrors)
    // ────────────────────────────────────────────────────────────────────

    /** Recursively walk any JSON value collecting all .mp4/.m3u8 strings. */
    private fun collectStreams(value: Any?, out: MutableSet<String>) {
        when (value) {
            is JSONObject -> {
                val keys = value.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = value.opt(k)
                    if (v is String && isStreamUrl(v)) {
                        out.add(v.replace("&amp;", "&"))
                    }
                    collectStreams(v, out)
                }
            }
            is JSONArray -> for (i in 0 until value.length()) collectStreams(value.opt(i), out)
        }
    }

    /** Parse every `<script type=application/ld+json>` and harvest streams. */
    private fun extractFromJsonLd(doc: Document): List<String> {
        val out = linkedSetOf<String>()
        doc.select("script[type=application/ld+json]").forEach { script ->
            val body = script.data().ifBlank { script.html() }.trim()
            if (body.isEmpty()) return@forEach
            try {
                when (body.first()) {
                    '{' -> collectStreams(JSONObject(body), out)
                    '[' -> collectStreams(JSONArray(body), out)
                }
            } catch (_: Exception) { /* malformed, skip */ }
        }
        return out.toList()
    }

    /** DOM fallback — <video>, <source>, <iframe>, data-* attributes, anchors. */
    private fun extractFromDom(doc: Document): List<String> {
        val out = linkedSetOf<String>()
        fun add(v: String?) { if (isStreamUrl(v)) out.add(v!!) }

        doc.select("video[src]").forEach { add(it.attr("src")) }
        doc.select("video source[src]").forEach { add(it.attr("src")) }
        doc.select("iframe[src]").forEach { add(it.attr("src")) }
        doc.select("a[href]").forEach { add(it.attr("href")) }
        for (attr in listOf(
            "data-src", "data-url", "data-video", "data-file", "data-stream",
            "data-mp4", "data-m3u8", "data-source", "data-player",
        )) doc.select("[$attr]").forEach { add(it.attr(attr)) }
        return out.toList()
    }

    /** Last-resort: scan <script> bodies only (not the whole HTML). */
    private fun extractFromScripts(doc: Document): List<String> {
        val out = linkedSetOf<String>()
        doc.select("script").forEach { s ->
            val body = s.data().ifBlank { s.html() }
            SCRIPT_STREAM.findAll(body).forEach { m ->
                out.add(Parser.unescapeEntities(m.groupValues[1], false))
            }
        }
        return out.toList()
    }

    /** Next.js RSC fallback. */
    private fun extractFromRsc(html: String): List<String> {
        val blob = extractRscBlob(html)
        return RSC_STREAM.findAll(blob)
            .map { Parser.unescapeEntities(it.value, false) }
            .distinct()
            .toList()
    }

    /**
     * Unified stream extraction with priority:
     *   1. JSON-LD  (WordPress sites always emit this)
     *   2. DOM      (video/source/iframe/data-*)
     *   3. RSC      (Next.js only)
     *   4. <script> scan (very rare fallback)
     */
    private fun extractStreams(doc: Document, html: String, isRsc: Boolean): List<String> {
        extractFromJsonLd(doc).takeIf { it.isNotEmpty() }?.let { return it }
        extractFromDom(doc).takeIf { it.isNotEmpty() }?.let { return it }
        if (isRsc) extractFromRsc(html).takeIf { it.isNotEmpty() }?.let { return it }
        return extractFromScripts(doc)
    }

    // ────────────────────────────────────────────────────────────────────
    // Homepage (DOM scrape — the category pages are HTML only)
    // ────────────────────────────────────────────────────────────────────
    private fun extractCards(doc: Document, site: String):
        List<Triple<String, String, String?>> {
        val results = mutableListOf<Triple<String, String, String?>>()

        fun pickImg(el: Element): String? {
            val img = el.selectFirst("img") ?: return null
            return img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
        }

        when {
            site.contains(XMAZA2) -> {
                doc.select("a.group.block").forEach { a ->
                    val t = a.selectFirst("h4")?.text()?.trim() ?: a.attr("title")
                    val h = a.attr("href")
                    if (t.isNotBlank() && h.isNotBlank()) results += Triple(t, h, pickImg(a))
                }
            }
            site.contains(ZMAAL) -> {
                doc.select("article").forEach { art ->
                    val a = art.selectFirst("a.link") ?: return@forEach
                    val t = a.attr("title").ifBlank { a.attr("aria-label") }.ifBlank { a.text() }
                    val h = a.attr("href")
                    if (t.isNotBlank() && h.isNotBlank()) results += Triple(t, h, pickImg(art))
                }
            }
            else -> {
                doc.select("a.video").forEach { a ->
                    val t = a.selectFirst("h2.vtitle")?.text()?.trim() ?: a.attr("title")
                    val h = a.attr("href")
                    val raw = a.attr("data-bg").ifBlank { pickImg(a) ?: "" }
                    if (t.isNotBlank() && h.isNotBlank())
                        results += Triple(t, h, raw.ifBlank { null })
                }
            }
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = HEADERS, timeout = TIMEOUT_MS).document
        val home = extractCards(doc, request.data).mapNotNull { (title, href, poster) ->
            val url = resolveHref(href, request.data)
            if (title.isBlank() || url.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true),
            hasNext = false,
        )
    }

    // ────────────────────────────────────────────────────────────────────
    // Search
    // ────────────────────────────────────────────────────────────────────
    private suspend fun searchWordPress(site: String, q: String):
        List<Triple<String, String, String?>> {
        val txt = fetch("$site/wp-json/wp/v2/search?search=${encode(q)}&per_page=20")
            ?: return emptyList()
        return try {
            val arr = JSONArray(txt)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("title").trim()
                val u = o.optString("url").trim()
                if (t.isBlank() || u.isBlank()) null else Triple(t, u, null)
            }
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun searchNextJs(site: String, q: String):
        List<Triple<String, String, String?>> {
        val html = fetch("$site/search/${encode(q)}") ?: return emptyList()
        return RSC_WATCH_SLUG.findAll(extractRscBlob(html))
            .map { it.groupValues[1] }
            .distinct()
            .map { slug ->
                val t = slug.split("-")
                    .joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
                Triple(t, "$site/watch/$slug", null)
            }
            .toList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val mutex = Mutex()

        coroutineScope {
            allMirrors.map { site ->
                async {
                    val cards = try {
                        if (site in rscMirrors) searchNextJs(site, query)
                        else searchWordPress(site, query)
                    } catch (_: Exception) { emptyList() }

                    cards.forEach { (title, url, _) ->
                        val key = normalizeTitle(title)
                        if (key.isBlank() || url.isBlank()) return@forEach
                        mutex.withLock {
                            results.putIfAbsent(key,
                                newTvSeriesSearchResponse(title, url, TvType.TvSeries))
                        }
                    }
                }
            }.awaitAll()
        }
        return results.values.toList()
    }

    // ────────────────────────────────────────────────────────────────────
    // Load — WordPress
    // ────────────────────────────────────────────────────────────────────
    private fun featuredMedia(p: JSONObject): String? =
        p.optJSONObject("_embedded")
            ?.optJSONArray("wp:featuredmedia")
            ?.takeIf { it.length() > 0 }
            ?.getJSONObject(0)
            ?.optString("source_url")
            ?.takeIf { it.isNotBlank() }

    private fun seriesTerm(p: JSONObject): Triple<String, Int, String>? {
        val groups = p.optJSONObject("_embedded")?.optJSONArray("wp:term")
            ?: return null
        for (i in 0 until groups.length()) {
            val g = groups.optJSONArray(i) ?: continue
            for (j in 0 until g.length()) {
                val t = g.getJSONObject(j)
                val tax = t.optString("taxonomy")
                if (tax == "series" || tax == "web_series")
                    return Triple(tax, t.optInt("id"), t.optString("name"))
            }
        }
        return null
    }

    private fun titleOf(p: JSONObject) =
        p.optJSONObject("title")?.optString("rendered", "")?.trim().orEmpty()

    /** Parse "Chawl House 3 Episode 1" → (name, season=3, episode=1). */
    private data class ParsedEpisode(
        val display: String,
        val season: Int,
        val episode: Int,
    )

    private fun parseEpisodeTitle(rawTitle: String): ParsedEpisode {
        val m = EPISODE_TITLE.matchEntire(rawTitle.trim())
        if (m != null) {
            val s = m.groups["season"]?.value?.toIntOrNull() ?: 1
            val e = m.groups["ep"]?.value?.toIntOrNull() ?: 0
            return ParsedEpisode(rawTitle, s, e)
        }
        return ParsedEpisode(rawTitle, 1, 0)
    }

    private suspend fun loadWordPress(url: String): LoadResponse? {
        val site = domainOf(url)
        val slug = url.trimEnd('/').substringAfterLast("/")

        val postTxt = fetch("$site/wp-json/wp/v2/posts?slug=${encode(slug)}&_embed=1")
            ?: return null
        val postArr = try { JSONArray(postTxt) } catch (_: Exception) { return null }
        if (postArr.length() == 0) return null
        val post = postArr.getJSONObject(0)

        val mediaTitle = titleOf(post).ifBlank { cleanTitle(slug.replace("-", " ")) }
        val poster = featuredMedia(post)

        val series = seriesTerm(post)
            ?: return newMovieLoadResponse(mediaTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = mediaTitle
            }

        val (tax, termId, termName) = series
        val epsTxt = fetch(
            "$site/wp-json/wp/v2/posts?$tax=$termId" +
                    "&per_page=$PAGE_SIZE&_embed=1&orderby=date&order=asc"
        ) ?: return null
        val epsArr = try { JSONArray(epsTxt) } catch (_: Exception) { return null }

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        for (i in 0 until epsArr.length()) {
            val ep = epsArr.getJSONObject(i)
            val t = titleOf(ep); val s = ep.optString("slug")
            if (t.isBlank() || s.isBlank()) continue
            if (!seen.add(normalizeTitle(t))) continue

            val parsed = parseEpisodeTitle(t)
            episodes += newEpisode(s) {
                this.name = t
                this.episode = parsed.episode.takeIf { it > 0 }
                this.season = parsed.season
                this.posterUrl = featuredMedia(ep)
            }
        }

        if (episodes.isEmpty()) return newMovieLoadResponse(mediaTitle, url, TvType.Movie, url) {
            this.posterUrl = poster; this.backgroundPosterUrl = poster; this.plot = mediaTitle
        }

        return newTvSeriesLoadResponse(mediaTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = "Series: $termName"
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Load — Next.js RSC
    // ────────────────────────────────────────────────────────────────────
    private suspend fun loadNextJs(url: String): LoadResponse? {
        val site = domainOf(url)
        val slug = url.trimEnd('/').substringAfterLast("/")
        val html = fetch("$site/watch/$slug") ?: return null
        val blob = extractRscBlob(html)

        val titleMatch = Regex(""""title"\s*:\s*"([^"]+)"""").find(blob)
        val mediaTitle = titleMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: cleanTitle(slug.replace("-", " "))

        val images = RSC_IMAGE.findAll(blob).map { it.value }.distinct().toList()
        val poster = images.firstOrNull { it.contains(slug, true) } ?: images.firstOrNull()

        val base = Regex("""^(.*?)-episode-\d+$""").find(slug)?.groupValues?.get(1)
        val related = RSC_WATCH_SLUG.findAll(blob)
            .map { it.groupValues[1] }
            .distinct()
            .filter { it != slug && base != null && it.startsWith("$base-") }
            .toList()

        if (related.isEmpty()) return newMovieLoadResponse(mediaTitle, url, TvType.Movie, url) {
            this.posterUrl = poster; this.backgroundPosterUrl = poster; this.plot = mediaTitle
        }

        val episodes = related.map { s ->
            val pretty = s.split("-").joinToString(" ") { it.replaceFirstChar(Char::uppercase) }
            val parsed = parseEpisodeTitle(pretty)
            newEpisode(s) {
                this.name = pretty
                this.episode = parsed.episode.takeIf { it > 0 }
                this.season = parsed.season
            }
        }
        return newTvSeriesLoadResponse(mediaTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster; this.backgroundPosterUrl = poster; this.plot = mediaTitle
        }
    }

    override suspend fun load(url: String): LoadResponse? =
        if (domainOf(url) in rscMirrors) loadNextJs(url) else loadWordPress(url)

    // ────────────────────────────────────────────────────────────────────
    // Load links — try every mirror, dedupe, sort by quality
    // ────────────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.trimEnd('/').substringAfterLast("/")
        if (slug.isBlank()) return false

        // Collect (url → sourceName) from every mirror concurrently.
        val collected = linkedMapOf<String, String>()
        val mutex = Mutex()

        coroutineScope {
            allMirrors.map { site ->
                async {
                    try {
                        val isRsc = site in rscMirrors
                        val pageUrl = if (isRsc) "$site/watch/$slug" else "$site/$slug/"
                        val html = fetch(pageUrl) ?: return@async
                        val doc = Parser.htmlParser().parseInput(html, pageUrl)
                        val streams = extractStreams(doc, html, isRsc)
                        val sourceName = hostOf(site)
                        mutex.withLock {
                            streams.forEach { s -> collected.putIfAbsent(s, sourceName) }
                        }
                    } catch (_: Exception) { /* mirror failed — move on */ }
                }
            }.awaitAll()
        }

        // Emit each unique URL once.
        for ((videoUrl, sourceName) in collected) {
            val isM3u8 = videoUrl.contains(".m3u8", true)
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = sourceName,
                    url = videoUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = domainOf(videoUrl)
                    this.quality = inferQuality(videoUrl)
                }
            )
        }
        return collected.isNotEmpty()
    }

    // ────────────────────────────────────────────────────────────────────
    // RSC blob decoding
    // ────────────────────────────────────────────────────────────────────
    private fun extractRscBlob(html: String): String {
        val sb = StringBuilder()
        for (m in RSC_CHUNK.findAll(html)) {
            val raw = m.groupValues[1]
            val decoded = try { JSONArray("[$raw]").getString(0) }
                          catch (_: Exception) { raw.trim('"') }
            sb.append(decoded).append('\n')
        }
        return sb.toString()
    }
}
