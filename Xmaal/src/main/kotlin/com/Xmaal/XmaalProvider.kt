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

class XmaalProvider : MainAPI() {

    // ─────────────────────────────────────────────────────────────────────
    // Sites
    // ─────────────────────────────────────────────────────────────────────
    private val siteOttdude: String   = "https://ottdude.com"
    private val siteMaalvdo: String   = "https://maalvdo.co"
    private val siteXmaza: String     = "https://xmaza.xxx"
    private val siteZmaal: String     = "https://zmaal.net"
    private val siteUncutmaza: String = "https://uncutmaza.movie"
    private val siteXmaza2: String    = "https://xmaza2.net"

    private val wpMirrors: List<String>  = listOf(siteXmaza, siteUncutmaza, siteOttdude, siteMaalvdo, siteZmaal)
    private val rscMirrors: List<String> = listOf(siteXmaza2)
    private val allMirrors: List<String> = wpMirrors + rscMirrors

    // ─────────────────────────────────────────────────────────────────────
    // HTTP
    // ─────────────────────────────────────────────────────────────────────
    private val requestHeaders: Map<String, String> = mapOf(
        "User-Agent"      to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Accept"          to "application/json, text/html, */*",
        "Accept-Language" to "en-US,en;q=0.9"
    )
    private val timeoutMs: Long = 20000L
    private val pageSize: Int   = 100

    // ─────────────────────────────────────────────────────────────────────
    // Regexes
    // ─────────────────────────────────────────────────────────────────────
    private val rscChunkRe: Regex = Regex(
        "self\\.__next_f\\.push\\(\\s*\\[\\s*\\d+\\s*,\\s*(\".*?\")\\s*\\]\\s*\\)",
        RegexOption.DOT_MATCHES_ALL
    )
    private val watchSlugRe: Regex = Regex("/watch/([a-z0-9\\-]+)")
    private val rscImageRe: Regex = Regex(
        "https?://[^\\s\"'\\\\<>]+?\\.(?:webp|jpg|jpeg|png)(?:\\?[^\\s\"'\\\\<>]*)?",
        RegexOption.IGNORE_CASE
    )
    private val rscStreamRe: Regex = Regex(
        "https?://[^\\s\"'<>\\\\]+?\\.(?:mp4|m3u8)(?:\\?[^\\s\"'<>\\\\]*)?",
        RegexOption.IGNORE_CASE
    )
    private val scriptStreamRe: Regex = Regex(
        "[\"'](https?://[^\"']+?\\.(?:mp4|m3u8)(?:\\?[^\"']*)?)[\"']",
        RegexOption.IGNORE_CASE
    )
    private val episodeTitleRe: Regex = Regex(
        "^(.+?)(?:\\s+(\\d+))?\\s+Episode\\s+(\\d+)\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val titleTrimRe: Regex = Regex("(?i)(?:[-:|]\\s*)?\\bEpisode\\s*\\d+\\b")
    private val jsonTitleRe: Regex  = Regex("\"title\"\\s*:\\s*\"([^\"]+)\"")
    private val nonAlnumRe: Regex   = Regex("[^a-z0-9]")
    private val trailTrimRe: Regex  = Regex("[\\s\\-_:|]+$")
    private val leadTrimRe: Regex   = Regex("^[\\s\\-_:|]+")
    private val spaceRunRe: Regex   = Regex("\\s+")
    private val seriesBaseRe: Regex = Regex("^(.*?)-episode-\\d+$")

    // ─────────────────────────────────────────────────────────────────────
    // Provider metadata
    // ─────────────────────────────────────────────────────────────────────
    override var mainUrl: String = "https://xmaza.xxx"
    override var name: String    = "Xmaza"
    override val hasMainPage: Boolean = true
    override var lang: String = "hi"
    override val hasDownloadSupport: Boolean = true
    override val supportedTypes: Set<TvType> = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "https://xmaza.xxx/category/ullu/"         to "ULLU",
        "https://xmaza.xxx/category/atrangii/"     to "Atrangii",
        "https://xmaza.xxx/category/altt/"         to "ALTT",
        "https://xmaza.xxx/category/bigshots/"     to "BigShots",
        "https://xmaza.xxx/category/boom-movies/"  to "Boom Movies",
        "https://xmaza.xxx/category/besharams/"    to "Besharams"
    )

    // ─────────────────────────────────────────────────────────────────────
    // Small helpers
    // ─────────────────────────────────────────────────────────────────────
    private fun domainOf(url: String): String {
        val i = url.indexOf("//") + 2
        return if (i < 2) url
        else url.substring(0, i) + url.substring(i).substringBefore("/")
    }

    private fun hostOf(url: String): String =
        domainOf(url).removePrefix("https://").removePrefix("http://")

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun normalizeTitle(t: String): String =
        t.lowercase().replace(nonAlnumRe, "")

    private fun cleanTitle(t: String): String {
        var out = t.replace(titleTrimRe, "")
        out = out.replace(trailTrimRe, "")
        out = out.replace(leadTrimRe, "")
        out = out.replace(spaceRunRe, " ")
        out = out.trim()
        return out.ifBlank { t.trim() }
    }

    private fun isStreamUrl(v: String?): Boolean {
        if (v.isNullOrBlank()) return false
        if (!v.startsWith("http")) return false
        val lower = v.lowercase()
        return lower.contains(".mp4") || lower.contains(".m3u8")
    }

    private fun resolveHref(href: String, base: String): String {
        return when {
            href.startsWith("http") -> href
            href.startsWith("//")   -> "https:$href"
            href.startsWith("/")    -> domainOf(base) + href
            else                    -> base.trimEnd('/') + "/" + href
        }
    }

    private suspend fun fetch(url: String): String? {
        var attempt = 0
        while (attempt < 3) {
            try {
                return app.get(url, headers = requestHeaders, timeout = timeoutMs).text
            } catch (e: Exception) {
                attempt++
                if (attempt < 3) delay(500L * attempt)
            }
        }
        return null
    }

    // ─────────────────────────────────────────────────────────────────────
    // JSON-LD / DOM / RSC stream extraction
    // ─────────────────────────────────────────────────────────────────────
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
            is JSONArray -> {
                for (i in 0 until value.length()) {
                    collectStreams(value.opt(i), out)
                }
            }
        }
    }

    private fun extractFromJsonLd(doc: Document): List<String> {
        val out = linkedSetOf<String>()
        val scripts = doc.select("script[type=application/ld+json]")
        for (script in scripts) {
            var body = script.data()
            if (body.isBlank()) body = script.html()
            body = body.trim()
            if (body.isEmpty()) continue
            try {
                if (body.startsWith("{")) {
                    collectStreams(JSONObject(body), out)
                } else if (body.startsWith("[")) {
                    collectStreams(JSONArray(body), out)
                }
            } catch (e: Exception) {
                // malformed JSON-LD — skip
            }
        }
        return out.toList()
    }

    private fun extractFromDom(doc: Document): List<String> {
        val out = linkedSetOf<String>()

        fun add(v: String?) {
            if (isStreamUrl(v)) out.add(v!!)
        }

        for (el in doc.select("video[src]")) add(el.attr("src"))
        for (el in doc.select("video source[src]")) add(el.attr("src"))
        for (el in doc.select("iframe[src]")) add(el.attr("src"))
        for (el in doc.select("a[href]")) add(el.attr("href"))

        val attrs = listOf(
            "data-src", "data-url", "data-video", "data-file", "data-stream",
            "data-mp4", "data-m3u8", "data-source", "data-player"
        )
        for (attr in attrs) {
            for (el in doc.select("[$attr]")) add(el.attr(attr))
        }
        return out.toList()
    }

    private fun extractFromScripts(doc: Document): List<String> {
        val out = linkedSetOf<String>()
        for (s in doc.select("script")) {
            var body = s.data()
            if (body.isBlank()) body = s.html()
            for (m in scriptStreamRe.findAll(body)) {
                out.add(Parser.unescapeEntities(m.groupValues[1], false))
            }
        }
        return out.toList()
    }

    private fun extractRscBlob(html: String): String {
        val sb = StringBuilder()
        for (m in rscChunkRe.findAll(html)) {
            val raw = m.groupValues[1]
            val decoded: String = try {
                JSONArray("[$raw]").getString(0)
            } catch (e: Exception) {
                raw.trim('"')
            }
            sb.append(decoded).append('\n')
        }
        return sb.toString()
    }

    private fun extractFromRsc(html: String): List<String> {
        val blob = extractRscBlob(html)
        return rscStreamRe.findAll(blob)
            .map { Parser.unescapeEntities(it.value, false) }
            .distinct()
            .toList()
    }

    private fun extractStreams(doc: Document, html: String, isRsc: Boolean): List<String> {
        val jsonLd = extractFromJsonLd(doc)
        if (jsonLd.isNotEmpty()) return jsonLd

        val dom = extractFromDom(doc)
        if (dom.isNotEmpty()) return dom

        if (isRsc) {
            val rsc = extractFromRsc(html)
            if (rsc.isNotEmpty()) return rsc
        }

        return extractFromScripts(doc)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Homepage
    // ─────────────────────────────────────────────────────────────────────
    private fun pickImage(el: Element): String? {
        val img = el.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifBlank { img.attr("src") }
        return src.ifBlank { null }
    }

    private fun extractCards(doc: Document, site: String): List<Triple<String, String, String?>> {
        val results = mutableListOf<Triple<String, String, String?>>()

        if (site.contains(siteXmaza2)) {
            for (a in doc.select("a.group.block")) {
                val t = a.selectFirst("h4")?.text()?.trim() ?: a.attr("title")
                val h = a.attr("href")
                if (t.isNotBlank() && h.isNotBlank()) results.add(Triple(t, h, pickImage(a)))
            }
        } else if (site.contains(siteZmaal)) {
            for (art in doc.select("article")) {
                val a = art.selectFirst("a.link") ?: continue
                val t = a.attr("title").ifBlank { a.attr("aria-label") }.ifBlank { a.text() }
                val h = a.attr("href")
                if (t.isNotBlank() && h.isNotBlank()) results.add(Triple(t, h, pickImage(art)))
            }
        } else {
            for (a in doc.select("a.video")) {
                val t = a.selectFirst("h2.vtitle")?.text()?.trim() ?: a.attr("title")
                val h = a.attr("href")
                val raw = a.attr("data-bg").ifBlank { pickImage(a) ?: "" }
                if (t.isNotBlank() && h.isNotBlank()) {
                    results.add(Triple(t, h, raw.ifBlank { null }))
                }
            }
        }
        return results
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(request.data, headers = requestHeaders, timeout = timeoutMs).document
        val home = mutableListOf<SearchResponse>()
        for (card in extractCards(doc, request.data)) {
            val (title, href, poster) = card
            val url = resolveHref(href, request.data)
            if (title.isBlank() || url.isBlank()) continue
            home.add(newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = poster
            })
        }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true),
            hasNext = false
        )
    }

    // ─────────────────────────────────────────────────────────────────────
    // Search
    // ─────────────────────────────────────────────────────────────────────
    private suspend fun searchWordPress(site: String, q: String): List<Triple<String, String, String?>> {
        val txt = fetch("$site/wp-json/wp/v2/search?search=${encode(q)}&per_page=20") ?: return emptyList()
        return try {
            val arr = JSONArray(txt)
            val out = mutableListOf<Triple<String, String, String?>>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("title").trim()
                val u = o.optString("url").trim()
                if (t.isNotBlank() && u.isNotBlank()) out.add(Triple(t, u, null))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun searchNextJs(site: String, q: String): List<Triple<String, String, String?>> {
        val html = fetch("$site/search/${encode(q)}") ?: return emptyList()
        val slugs = watchSlugRe.findAll(extractRscBlob(html)).map { it.groupValues[1] }.distinct()
        val out = mutableListOf<Triple<String, String, String?>>()
        for (slug in slugs) {
            val t = slug.split("-").joinToString(" ") { w ->
                w.replaceFirstChar { c -> c.uppercase() }
            }
            out.add(Triple(t, "$site/watch/$slug", null))
        }
        return out
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = linkedMapOf<String, SearchResponse>()
        val mutex = Mutex()

        coroutineScope {
            val tasks = allMirrors.map { site ->
                async {
                    val cards: List<Triple<String, String, String?>> = try {
                        if (site in rscMirrors) searchNextJs(site, query)
                        else searchWordPress(site, query)
                    } catch (e: Exception) {
                        emptyList()
                    }
                    for (card in cards) {
                        val (title, url, _) = card
                        val key = normalizeTitle(title)
                        if (key.isBlank() || url.isBlank()) continue
                        mutex.withLock {
                            if (!results.containsKey(key)) {
                                results[key] = newTvSeriesSearchResponse(title, url, TvType.TvSeries)
                            }
                        }
                    }
                }
            }
            tasks.awaitAll()
        }
        return results.values.toList()
    }

    // ─────────────────────────────────────────────────────────────────────
    // WordPress load
    // ─────────────────────────────────────────────────────────────────────
    private fun featuredMedia(p: JSONObject): String? {
        val embedded = p.optJSONObject("_embedded") ?: return null
        val media = embedded.optJSONArray("wp:featuredmedia") ?: return null
        if (media.length() == 0) return null
        val src = media.getJSONObject(0).optString("source_url")
        return src.ifBlank { null }
    }

    private fun seriesTerm(p: JSONObject): Triple<String, Int, String>? {
        val embedded = p.optJSONObject("_embedded") ?: return null
        val groups = embedded.optJSONArray("wp:term") ?: return null
        for (i in 0 until groups.length()) {
            val g = groups.optJSONArray(i) ?: continue
            for (j in 0 until g.length()) {
                val t = g.getJSONObject(j)
                val tax = t.optString("taxonomy")
                if (tax == "series" || tax == "web_series") {
                    return Triple(tax, t.optInt("id"), t.optString("name"))
                }
            }
        }
        return null
    }

    private fun titleOf(p: JSONObject): String {
        val t = p.optJSONObject("title") ?: return ""
        return t.optString("rendered", "").trim()
    }

    private data class ParsedEpisode(val season: Int, val episode: Int)

    private fun parseEpisodeTitle(rawTitle: String): ParsedEpisode {
        val m = episodeTitleRe.matchEntire(rawTitle.trim())
        if (m != null) {
            val s = m.groupValues[2].toIntOrNull() ?: 1
            val e = m.groupValues[3].toIntOrNull() ?: 0
            return ParsedEpisode(s, e)
        }
        return ParsedEpisode(1, 0)
    }

    private suspend fun loadWordPress(url: String): LoadResponse? {
        val site = domainOf(url)
        val slug = url.trimEnd('/').substringAfterLast("/")

        val postTxt = fetch("$site/wp-json/wp/v2/posts?slug=${encode(slug)}&_embed=1") ?: return null
        val postArr = try { JSONArray(postTxt) } catch (e: Exception) { return null }
        if (postArr.length() == 0) return null
        val post = postArr.getJSONObject(0)

        val mediaTitle = titleOf(post).ifBlank { cleanTitle(slug.replace("-", " ")) }
        val poster = featuredMedia(post)

        val series = seriesTerm(post)
        if (series == null) {
            return newMovieLoadResponse(mediaTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = mediaTitle
            }
        }

        val tax = series.first
        val termId = series.second
        val termName = series.third

        val epsTxt = fetch("$site/wp-json/wp/v2/posts?$tax=$termId&per_page=$pageSize&_embed=1&orderby=date&order=asc")
            ?: return null
        val epsArr = try { JSONArray(epsTxt) } catch (e: Exception) { return null }

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        for (i in 0 until epsArr.length()) {
            val ep = epsArr.getJSONObject(i)
            val t = titleOf(ep)
            val s = ep.optString("slug")
            if (t.isBlank() || s.isBlank()) continue
            if (!seen.add(normalizeTitle(t))) continue

            val parsed = parseEpisodeTitle(t)
            episodes.add(newEpisode(s) {
                this.name = t
                this.episode = if (parsed.episode > 0) parsed.episode else null
                this.season = parsed.season
                this.posterUrl = featuredMedia(ep)
            })
        }

        if (episodes.isEmpty()) {
            return newMovieLoadResponse(mediaTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = mediaTitle
            }
        }

        return newTvSeriesLoadResponse(mediaTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = "Series: $termName"
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Next.js load
    // ─────────────────────────────────────────────────────────────────────
    private suspend fun loadNextJs(url: String): LoadResponse? {
        val site = domainOf(url)
        val slug = url.trimEnd('/').substringAfterLast("/")
        val html = fetch("$site/watch/$slug") ?: return null
        val blob = extractRscBlob(html)

        val titleMatch = jsonTitleRe.find(blob)
        val mediaTitle = titleMatch?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?: cleanTitle(slug.replace("-", " "))

        val images = rscImageRe.findAll(blob).map { it.value }.distinct().toList()
        val poster = images.firstOrNull { it.contains(slug, true) } ?: images.firstOrNull()

        val baseMatch = seriesBaseRe.find(slug)
        val base = baseMatch?.groupValues?.get(1)

        val related = watchSlugRe.findAll(blob)
            .map { it.groupValues[1] }
            .distinct()
            .filter { s -> s != slug && base != null && s.startsWith("$base-") }
            .toList()

        if (related.isEmpty()) {
            return newMovieLoadResponse(mediaTitle, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = mediaTitle
            }
        }

        val episodes = mutableListOf<Episode>()
        for (s in related) {
            val pretty = s.split("-").joinToString(" ") { w ->
                w.replaceFirstChar { c -> c.uppercase() }
            }
            val parsed = parseEpisodeTitle(pretty)
            episodes.add(newEpisode(s) {
                this.name = pretty
                this.episode = if (parsed.episode > 0) parsed.episode else null
                this.season = parsed.season
            })
        }

        return newTvSeriesLoadResponse(mediaTitle, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = mediaTitle
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return if (domainOf(url) in rscMirrors) loadNextJs(url) else loadWordPress(url)
    }

    // ─────────────────────────────────────────────────────────────────────
    // Load links
    // ─────────────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data.trimEnd('/').substringAfterLast("/")
        if (slug.isBlank()) return false

        val collected = linkedMapOf<String, String>()
        val mutex = Mutex()

        coroutineScope {
            val tasks = allMirrors.map { site ->
                async {
                    val sourceName = hostOf(site)
                    try {
                        val isRsc = site in rscMirrors
                        val pageUrl = if (isRsc) "$site/watch/$slug" else "$site/$slug/"
                        val html = fetch(pageUrl) ?: return@async
                        val doc = Parser.htmlParser().parseInput(html, pageUrl)
                        val streams = extractStreams(doc, html, isRsc)
                        mutex.withLock {
                            for (s in streams) {
                                if (!collected.containsKey(s)) {
                                    collected[s] = sourceName
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // mirror failed — move on
                    }
                }
            }
            tasks.awaitAll()
        }

        for ((videoUrl, sourceName) in collected) {
            val isM3u8 = videoUrl.lowercase().contains(".m3u8")
            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = sourceName,
                    url = videoUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = domainOf(videoUrl)
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return collected.isNotEmpty()
    }
}
