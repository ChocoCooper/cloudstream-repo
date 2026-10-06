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
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

class XmaalProvider : MainAPI() {

    // ------------------------------------------------------------------
    // Constants
    // ------------------------------------------------------------------

    private companion object {
        const val TAG = "XmaalProvider"

        object Timeouts {
            const val SEARCH_MS = 15_000L
            const val LOAD_MS   = 15_000L
            const val LINKS_MS  = 12_000L
            const val VERIFY_MS = 8_000L
            const val RETRY_BASE_MS = 300L
        }
    }

    private object Domains {
        const val OTTDUDE   = "https://ottdude.cc"
        const val MAALVDO   = "https://maalvdo.co"
        const val XMAZA     = "https://xmaza.adult"
        const val ZMAAL     = "https://zmaal.net"
        const val UNCUTMAZA = "https://uncutmaza.movie"
        const val XMAZA2    = "https://xmaza2.net"
        const val YMAAL     = "https://ymaal.co"
        const val XMASTI    = "https://xmasti.vc"
        const val MASTIWALA = "https://mastiwala.com"
    }

    override var mainUrl = Domains.XMAZA2
    override var name = "Xmaal"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val mirrors = listOf(
        Domains.OTTDUDE,
        Domains.MAALVDO,
        Domains.XMAZA,
        Domains.ZMAAL,
        Domains.UNCUTMAZA,
        Domains.XMAZA2,
        Domains.YMAAL,
        Domains.XMASTI,
        Domains.MASTIWALA
    )

    override val mainPage = mainPageOf(
        "${Domains.XMAZA}/ott/ullu/"               to "ULLU",
        "${Domains.XMAZA}/ott/atrangii/"           to "Atrangii",
        "${Domains.XMAZA}/ott/kooku/"              to "Kooku",
        "${Domains.XMAZA}/ott/moovi/"              to "Moovi",
        "${Domains.XMAZA}/ott/look-entertainment/" to "Look Entertainment",
        "${Domains.XMAZA}/ott/jugnu/"              to "Jugnu",
        "${Domains.XMAZA}/ott/voovi/"              to "Voovi"
    )

    // ------------------------------------------------------------------
    // Regexes
    // ------------------------------------------------------------------

    private val styleUrlRegex = Regex("url\\((['\"]?)(.*?)\\1\\)")

    private val streamPattern = Regex(
        """["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )

    // Episode regexes: try the most specific first.
    private val seasonEpisodeRegex = Regex(
        """^(.*?)\bSeason\s+(\d+)\s+Episode\s+(\d+)\b""",
        RegexOption.IGNORE_CASE
    )
    private val sxxexxRegex = Regex(
        """^(.*?)\bS(\d{1,2})[\s._-]*E(\d{1,3})\b""",
        RegexOption.IGNORE_CASE
    )
    private val episodeRegex = Regex(
        "^(.*?)(?:\\s+(\\d+))?\\s+Episode\\s+(\\d+)\\s*$",
        RegexOption.IGNORE_CASE
    )

    private val titleTrimRegex = Regex(
        """(?i)(?:[-:|–—]\s*)?\bEpisode\s*\d+\b"""
    )

    private val seriesPageTitlePrefix = Regex(
        """^(?:Web\s+Series|Series)\s*[:\-–—]\s*""",
        RegexOption.IGNORE_CASE
    )
    private val seriesPageTitleSuffix = Regex(
        """\s+(?:Full\s+Web\s+Series|Web\s+Series|\(All\s+Episodes\))\s*$""",
        RegexOption.IGNORE_CASE
    )

    // Junk-URL filter (previews, ads, images).
    private val junkUrlPatterns = listOf(
        Regex("""/(?:preview|sample|trailer|teaser|promo)/""", RegexOption.IGNORE_CASE),
        Regex("""\.(?:gif|jpe?g|png|webp|svg)(?:\?|$)""", RegexOption.IGNORE_CASE),
        Regex("""/ads?(?:/|\.)""", RegexOption.IGNORE_CASE),
        Regex("""(?:doubleclick|googlesyndication|adservice)""", RegexOption.IGNORE_CASE)
    )

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------

    private fun logD(msg: String) = println("[$TAG] $msg")
    private fun logE(label: String, e: Throwable) =
        println("[$TAG] $label: ${e.message ?: e.javaClass.simpleName}")

    // ------------------------------------------------------------------
    // Retry helper
    // ------------------------------------------------------------------

    private suspend fun <T> retry(
        times: Int = 3,
        initialDelayMs: Long = Timeouts.RETRY_BASE_MS,
        block: suspend () -> T?
    ): T? {
        var d = initialDelayMs
        repeat(times) { attempt ->
            val result = try {
                block()
            } catch (e: Exception) {
                logD("retry[$attempt] ${e.message}")
                null
            }
            if (result != null) return result
            if (attempt < times - 1) delay(d)
            d *= 2
        }
        return null
    }

    // ------------------------------------------------------------------
    // Basic helpers
    // ------------------------------------------------------------------

    /** Episode-level title cleanup: strips "… Episode N" and trailing punctuation. */
    private fun cleanTitle(title: String): String {
        val cleaned = title
            .replace(seriesPageTitlePrefix, "")
            .replace(seriesPageTitleSuffix, "")
            .replace(titleTrimRegex, "")
            .replace(Regex("""\(\s*\)|\[\s*]"""), "")
            .replace(Regex("""[\s\-_:|–—]+$"""), "")
            .replace(Regex("""^[\s\-_:|–—]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (cleaned.isBlank()) title.trim() else cleaned
    }

    /** Series-level cleanup: strips only "Web Series:" / "… Web Series" wrappers. */
    private fun cleanSeriesTitle(title: String): String {
        val cleaned = title
            .replace(seriesPageTitlePrefix, "")
            .replace(seriesPageTitleSuffix, "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return if (cleaned.isBlank()) title.trim() else cleaned
    }

    private fun normalizeTitle(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun domainOf(url: String): String {
        val protocolEnd = url.indexOf("//")
        val start = if (protocolEnd >= 0) protocolEnd + 2 else 0
        val end = url.indexOf('/', start).let { if (it < 0) url.length else it }
        return url.substring(0, end)
    }

    private fun fixImageUrl(raw: String?, pageUrl: String): String? {
        if (raw.isNullOrBlank()) return null
        val url = raw.trim()
        if (url.startsWith("data:")) return null

        val root = domainOf(pageUrl)

        if (url.contains("/_next/image")) {
            val full = if (url.startsWith("http")) url else root + url
            val encoded = Regex("[?&]url=([^&]+)").find(full)?.groupValues?.get(1)
            return if (encoded != null) {
                try {
                    URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8")
                } catch (e: Exception) {
                    full
                }
            } else full
        }

        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/")  -> root + url
            else                 -> url
        }
    }

    private fun resolveHref(hrefRaw: String, site: String): String {
        if (hrefRaw.isBlank()) return hrefRaw
        return when {
            hrefRaw.startsWith("http") -> hrefRaw
            hrefRaw.startsWith("//")   -> "https:$hrefRaw"
            hrefRaw.startsWith("/")    -> domainOf(site) + hrefRaw
            else                       -> hrefRaw
        }
    }

    private fun isYmaal(site: String)     = site.contains(Domains.YMAAL)
    private fun isXmasti(site: String)    = site.contains(Domains.XMASTI)
    private fun isMastiwala(site: String) = site.contains(Domains.MASTIWALA)
    private fun isXmaza2(site: String)    = site.contains(Domains.XMAZA2)
    private fun isZmaal(site: String)     = site.contains(Domains.ZMAAL)

    // ------------------------------------------------------------------
    // Junk-URL filter
    // ------------------------------------------------------------------

    private fun isLikelyRealVideo(url: String): Boolean {
        if (url.length < 20) return false
        if (junkUrlPatterns.any { it.containsMatchIn(url) }) return false
        val lower = url.lowercase()
        return lower.contains(".mp4") ||
                lower.contains(".m3u8") ||
                lower.contains(".webm") ||
                lower.contains(".mkv") ||
                lower.contains("mime=video")
    }

    // ==================================================================
    // Per-site VideoExtractor interface + registry
    // ==================================================================

    private data class StreamCandidate(
        val url: String,
        val isM3u8: Boolean,
        val referer: String?
    )

    private interface VideoExtractor {
        val id: String
        fun extract(doc: Document, html: String, pageUrl: String): List<StreamCandidate>
    }

    /** xplayer.js embed used by XMasti and MastiWala. */
    private class XPlayerExtractor : VideoExtractor {
        override val id = "xplayer"
        override fun extract(doc: Document, html: String, pageUrl: String): List<StreamCandidate> {
            val out = mutableListOf<StreamCandidate>()
            doc.select(".xplayer-lazy-source[data-src]").forEach { el ->
                val src = el.attr("data-src").trim()
                if (src.isNotBlank()) {
                    val mime = el.attr("data-type")
                    val isHls = src.contains(".m3u8", true) ||
                            mime.contains("mpegurl", true)
                    out.add(StreamCandidate(src, isHls, pageUrl))
                }
            }
            return out
        }
    }

    /** Native <video> tag + download button (YMaal). */
    private class NativeVideoExtractor : VideoExtractor {
        override val id = "native-video"
        override fun extract(doc: Document, html: String, pageUrl: String): List<StreamCandidate> {
            val out = mutableListOf<StreamCandidate>()
            doc.select("video source[src]").forEach { el ->
                val src = el.attr("src").trim()
                if (src.isNotBlank())
                    out.add(StreamCandidate(src, src.contains(".m3u8", true), pageUrl))
            }
            doc.select("video[src]").forEach { el ->
                val src = el.attr("src").trim()
                if (src.isNotBlank())
                    out.add(StreamCandidate(src, src.contains(".m3u8", true), pageUrl))
            }
            // YMaal download button
            doc.select("a.sdl[href]").forEach { a ->
                val href = a.attr("href").trim()
                if (href.contains(".mp4", true) || href.contains(".m3u8", true))
                    out.add(StreamCandidate(href, href.contains(".m3u8", true), pageUrl))
            }
            return out
        }
    }

    /** Next.js sites embed the stream URL in a JSON blob (XMaza2). */
    private class NextJsExtractor : VideoExtractor {
        private val rx = Regex(
            """"(?:file|src|url|source)"\s*:\s*"(https?://[^"]+\.(?:mp4|m3u8)[^"]*)"""",
            RegexOption.IGNORE_CASE
        )
        override val id = "nextjs"
        override fun extract(doc: Document, html: String, pageUrl: String): List<StreamCandidate> {
            val normalized = html.replace("\\/", "/")
            return rx.findAll(normalized).map { m ->
                val src = Parser.unescapeEntities(m.groupValues[1], false)
                StreamCandidate(src, src.contains(".m3u8", true), pageUrl)
            }.toList()
        }
    }

    /** Final fallback: bare regex over raw HTML. */
    private class GenericRegexExtractor : VideoExtractor {
        private val rx = Regex(
            """["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        override val id = "generic-regex"
        override fun extract(doc: Document, html: String, pageUrl: String): List<StreamCandidate> {
            val normalized = html.replace("\\/", "/")
            return rx.findAll(normalized).map { m ->
                val src = Parser.unescapeEntities(m.groupValues[1], false)
                StreamCandidate(src, src.contains(".m3u8", true), pageUrl)
            }.toList()
        }
    }

    private val extractors: List<VideoExtractor> = listOf(
        XPlayerExtractor(),
        NativeVideoExtractor(),
        NextJsExtractor(),
        GenericRegexExtractor()
    )

    // ------------------------------------------------------------------
    // Card extraction
    // ------------------------------------------------------------------

    private fun extractCards(doc: Document, site: String): List<Triple<String, String, String?>> {
        val results = mutableListOf<Triple<String, String, String?>>()

        when {
            isXmaza2(site) -> {
                doc.select("a.group.block").forEach { a ->
                    val title = a.selectFirst("h4")?.text()?.trim()
                        ?.takeUnless { it.isBlank() }
                        ?: a.attr("title").trim()
                    val href = a.attr("href")
                    val raw  = a.selectFirst("img")?.attr("src")
                    if (title.isNotBlank() && href.isNotBlank())
                        results.add(Triple(title, href, raw))
                }
            }

            isZmaal(site) -> {
                doc.select("article").forEach { article ->
                    val a = article.selectFirst("a.link") ?: return@forEach
                    val title = a.attr("title")
                        .ifBlank { a.attr("aria-label") }
                        .ifBlank { a.text() }
                        .trim()
                    val href = a.attr("href")
                    val img  = article.selectFirst("img")
                    val raw  = img?.attr("data-src")?.ifBlank { img.attr("src") }
                    if (title.isNotBlank() && href.isNotBlank())
                        results.add(Triple(title, href, raw))
                }
            }

            isYmaal(site) -> {
                doc.select("a.video-card").forEach { a ->
                    val title = a.selectFirst("h2.title")?.text()?.trim()
                        ?.takeUnless { it.isBlank() }
                        ?: a.attr("title").trim()
                    val href = a.attr("href")
                    val raw  = a.selectFirst("img.thumbnail")?.attr("src")
                        ?: a.selectFirst("img")?.attr("src")
                    if (title.isNotBlank() && href.isNotBlank())
                        results.add(Triple(title, href, raw))
                }
            }

            isMastiwala(site) -> {
                doc.select("article.video-card a.post-card-link, a.post-card-link").forEach { a ->
                    val title = a.selectFirst("h2")?.text()?.trim()
                        ?.takeUnless { it.isBlank() }
                        ?: a.attr("title").trim()
                    val href = a.attr("href")
                    val raw  = a.selectFirst("img")?.attr("src")
                    if (title.isNotBlank() && href.isNotBlank())
                        results.add(Triple(title, href, raw))
                }
            }

            else -> {
                doc.select("a.video").forEach { a ->
                    val title = a.selectFirst("h2.vtitle")?.text()?.trim()
                        ?.takeUnless { it.isBlank() }
                        ?: a.attr("title").trim()
                    val href = a.attr("href")
                    val dataBg = a.attr("data-bg")
                    val raw = dataBg.ifBlank {
                        styleUrlRegex.find(a.attr("style"))?.groupValues?.get(2)
                    }
                    if (title.isNotBlank() && href.isNotBlank())
                        results.add(Triple(title, href, raw))
                }
            }
        }

        return results
    }

    // ------------------------------------------------------------------
    // Poster heuristic
    // ------------------------------------------------------------------

    private fun extractSeriesPoster(doc: Document, pageUrl: String, title: String): String? {
        val site = domainOf(pageUrl)

        doc.selectFirst("img.xx-banner-image")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { return fixImageUrl(it, pageUrl) }

        val candidates = mutableListOf<String>()

        doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        doc.selectFirst("meta[name=\"twitter:image\"]")?.attr("content")
            ?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        doc.selectFirst("link[rel=\"image_src\"]")?.attr("href")
            ?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        doc.selectFirst(".wp-post-image, .attachment-post-thumbnail, .post-thumbnail img")?.let {
            val raw = it.attr("data-src").ifBlank { it.attr("src") }
            if (raw.isNotBlank()) candidates.add(raw)
        }

        if (isMastiwala(site)) {
            doc.selectFirst("article.video-card img")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        }

        if (candidates.isEmpty()) return null

        fun tokenize(s: String) =
            s.lowercase().split(Regex("[-_.\\s/%0-9]+")).filter { it.length > 2 }.toSet()

        val titleTokens  = tokenize(title)
        val siteTokens   = tokenize(domainOf(pageUrl))
        val brandingWords = setOf("logo", "icon", "default", "placeholder")

        var bestRaw: String? = null
        var bestScore = Int.MIN_VALUE

        for (raw in candidates) {
            val filename = raw.substringAfterLast("/").substringBefore("?")
            val fileTokens = tokenize(filename)
            val overlap = fileTokens.intersect(titleTokens).size

            var score = overlap * 10
            val isBranding = fileTokens.isNotEmpty() &&
                fileTokens.all { it in siteTokens || it in brandingWords }
            if (isBranding) score -= 50
            val fnLower = filename.lowercase()
            if (fnLower.contains("logo") || fnLower.contains("icon") || fnLower.contains("default"))
                score -= 50

            if (score > bestScore) {
                bestScore = score
                bestRaw = raw
            }
        }

        if (bestScore <= 0 || bestRaw == null) {
            return fixImageUrl(candidates.first(), pageUrl)
        }
        return fixImageUrl(bestRaw, pageUrl)
    }

    // ------------------------------------------------------------------
    // Series URL detection
    // ------------------------------------------------------------------

    private fun findSeriesUrl(epDoc: Document, pageUrl: String, mediaTitle: String): String? {
        val site = domainOf(pageUrl)

        val direct = when {
            isYmaal(site)     -> epDoc.selectFirst("a.series[href*=/series/]")?.attr("href")
            isXmasti(site)    -> epDoc.selectFirst(".series-list a[href*=/series/]")?.attr("href")
            isMastiwala(site) -> epDoc.selectFirst("a.series-link[href*=/web-series/]")?.attr("href")
            else              -> null
        }
        if (!direct.isNullOrBlank()) {
            return if (direct.startsWith("http")) direct else site + direct
        }

        val breadcrumb = epDoc.select("nav.breadcrumb a, .breadcrumb a").map { it.attr("href") }
        for (href in breadcrumb) {
            val full = if (href.startsWith("http")) href else site + href
            val parts = full.substringAfter("://").substringAfter("/")
                .split("/").filter { it.isNotBlank() }
            if (parts.contains("series") || parts.contains("web-series")) {
                if (full.trimEnd('/') != pageUrl.trimEnd('/')) return full
            }
        }

        val titleNorm = normalizeTitle(mediaTitle)
        val candidates = epDoc.select("a[href*=/series/], a[href*=/web-series/]")
            .mapNotNull { a ->
                val href = a.attr("href")
                if (href.isBlank()) return@mapNotNull null
                val full = if (href.startsWith("http")) href else site + href
                val slug = full.trimEnd('/').substringAfterLast("/")
                val slugNorm = normalizeTitle(slug)
                val textNorm = normalizeTitle(a.text())
                var score = 0
                if (titleNorm.isNotBlank()) {
                    if (slugNorm.contains(titleNorm) || titleNorm.contains(slugNorm)) score += 100
                    if (textNorm.contains(titleNorm) || titleNorm.contains(textNorm)) score += 100
                }
                if (score > 0) (score to full) else null
            }
            .sortedByDescending { it.first }

        return candidates.firstOrNull()?.second
    }

    // ------------------------------------------------------------------
    // Inline episode list (XMasti)
    // ------------------------------------------------------------------

    private fun extractInlineEpisodes(epDoc: Document, pageUrl: String): List<Episode> {
        val list = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()

        epDoc.select("section.series-episodes article.episode-item, .episodes-grid article.episode-item")
            .forEach { item ->
                val a = item.selectFirst("a[href]") ?: return@forEach
                val href = a.attr("href")
                if (href.isBlank()) return@forEach
                val full = if (href.startsWith("http")) href else domainOf(pageUrl) + href
                val title = item.selectFirst(".episode-title")?.text()?.trim()
                    ?.takeUnless { it.isBlank() }
                    ?: a.attr("title").trim()
                    ?: return@forEach
                val key = normalizeTitle(title)
                if (key.isBlank() || seen.contains(key)) return@forEach
                seen.add(key)
                val poster = fixImageUrl(item.selectFirst("img")?.attr("src"), pageUrl)
                list.add(newEpisode(full) {
                    this.name = title
                    this.posterUrl = poster
                })
            }

        return list
    }

    // ==================================================================
    // Verify links before emitting (prevents "source error")
    // ==================================================================

    private suspend fun verifyVideoLink(url: String, referer: String): Boolean {
        // Do a ranged GET (bytes=0-1) — cheap, and validates the CDN actually serves the file.
        val resp = try {
            withTimeoutOrNull(Timeouts.VERIFY_MS) {
                app.get(
                    url = url,
                    referer = referer,
                    headers = mapOf("Range" to "bytes=0-1")
                )
            }
        } catch (e: Exception) {
            logD("verify threw for $url: ${e.message}")
            null
        } ?: return false

        val code = resp.code
        if (code !in 200..399) {
            logD("verify rejected (code=$code) $url")
            return false
        }

        val ct = (resp.headers["Content-Type"] ?: "").lowercase()
        val cl = resp.headers["Content-Length"]?.toLongOrNull() ?: -1L

        // Reject HTML error pages (Cloudflare, 404 pages, etc.).
        if (ct.contains("text/html") || ct.contains("text/plain")) {
            logD("verify rejected (html) $url")
            return false
        }

        val looksLikeVideo = ct.contains("video/") ||
                ct.contains("audio/") ||
                ct.contains("octet-stream") ||
                ct.contains("mpegurl") ||
                ct.contains("mp2t") ||
                url.contains(".mp4", ignoreCase = true) ||
                url.contains(".m3u8", ignoreCase = true) ||
                url.contains(".webm", ignoreCase = true) ||
                url.contains(".mkv", ignoreCase = true)

        if (!looksLikeVideo) {
            logD("verify rejected (not video, ct=$ct) $url")
            return false
        }

        // If the server returned 200 for a Range request but reports 0 bytes, it's dead.
        if (cl == 0L && code == 200) {
            logD("verify rejected (0 bytes) $url")
            return false
        }

        return true
    }

    // ------------------------------------------------------------------
    // CloudStream API — Main page
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = retry(2) {
            withTimeoutOrNull(Timeouts.SEARCH_MS) { app.get(request.data).document }
        } ?: return newHomePageResponse(
            HomePageList(request.name, emptyList(), isHorizontalImages = true)
        )

        val home = extractCards(document, request.data).mapNotNull { (title, hrefRaw, posterRaw) ->
            val href = resolveHref(hrefRaw, request.data)
            if (title.isBlank() || href.isBlank()) return@mapNotNull null
            newTvSeriesSearchResponse(title, href, TvType.NSFW) {
                this.posterUrl = fixImageUrl(posterRaw, request.data)
            }
        }

        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true)
        )
    }

    // ------------------------------------------------------------------
    // CloudStream API — Search
    // ------------------------------------------------------------------

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableMapOf<String, SearchResponse>()
        val mutex = Mutex()

        coroutineScope {
            mirrors.map { site ->
                async {
                    try {
                        val searchUrl = if (isXmaza2(site)) "$site/search/$query"
                                        else "$site/?s=$query"

                        val doc = retry(2) {
                            withTimeoutOrNull(Timeouts.SEARCH_MS) { app.get(searchUrl).document }
                        } ?: return@async

                        extractCards(doc, site).forEach { (title, hrefRaw, posterRaw) ->
                            val key = normalizeTitle(title)
                            val href = resolveHref(hrefRaw, site)
                            if (key.isBlank() || href.isBlank()) return@forEach

                            mutex.withLock {
                                if (!results.containsKey(key)) {
                                    results[key] = newTvSeriesSearchResponse(title, href, TvType.NSFW) {
                                        this.posterUrl = fixImageUrl(posterRaw, site)
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        logE("search $site", e)
                    }
                }
            }.awaitAll()
        }

        return results.values.toList()
    }

    // ------------------------------------------------------------------
    // CloudStream API — Load
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val epDoc = retry(2) {
            withTimeoutOrNull(Timeouts.LOAD_MS) { app.get(url).document }
        } ?: return null

        val site = domainOf(url)

        // Distinguish episode vs series title pages.
        val h1Episode = epDoc.selectFirst("h1.video-title")
            ?: epDoc.selectFirst("h1.stitle")
            ?: epDoc.selectFirst(".video-container h1")
        val h1Series = epDoc.selectFirst("h1.xx-title")
            ?: epDoc.selectFirst("h1.page-title")

        val rawClickedTitle: String
        val mediaTitle: String
        when {
            h1Episode != null -> {
                rawClickedTitle = h1Episode.text().trim()
                mediaTitle = cleanTitle(rawClickedTitle)
            }
            h1Series != null -> {
                rawClickedTitle = h1Series.text().trim()
                mediaTitle = cleanSeriesTitle(rawClickedTitle)
            }
            else -> {
                rawClickedTitle = epDoc.selectFirst("h1, .entry-title, h2")
                    ?.text()?.trim() ?: "Unknown Title"
                mediaTitle = cleanTitle(rawClickedTitle)
            }
        }

        val clickedPoster = extractSeriesPoster(epDoc, url, rawClickedTitle)

        // ---- Inline episodes (XMasti) — no extra fetch ----
        val seriesUrl = findSeriesUrl(epDoc, url, rawClickedTitle)
        if (isXmasti(site) && seriesUrl != null) {
            val inline = extractInlineEpisodes(epDoc, url)
            if (inline.isNotEmpty()) {
                val sorted = inline.sortedWith(SeasonAwareComparator())
                return newTvSeriesLoadResponse(mediaTitle, seriesUrl, TvType.NSFW, sorted) {
                    this.posterUrl = clickedPoster
                    this.backgroundPosterUrl = clickedPoster
                    this.plot = mediaTitle
                }
            }
        }

        // ---- Standalone movie (no series link) ----
        if (seriesUrl == null) {
            return newMovieLoadResponse(mediaTitle, url, TvType.NSFW, url) {
                this.posterUrl = clickedPoster
                this.backgroundPosterUrl = clickedPoster
                this.plot = mediaTitle
            }
        }

        // ---- Series page load ----
        val seriesDoc = retry(2) {
            withTimeoutOrNull(Timeouts.LOAD_MS) { app.get(seriesUrl).document }
        }

        // Fallback: series page fetch failed → return a single-episode series.
        if (seriesDoc == null) {
            logD("series fetch failed for $seriesUrl — falling back to single-episode response")
            val fallbackEp = newEpisode(url) {
                this.name = mediaTitle
                this.posterUrl = clickedPoster
            }
            return newTvSeriesLoadResponse(mediaTitle, seriesUrl, TvType.NSFW, listOf(fallbackEp)) {
                this.posterUrl = clickedPoster
                this.backgroundPosterUrl = clickedPoster
                this.plot = mediaTitle
            }
        }

        val seriesPoster = if (seriesDoc !== epDoc)
            extractSeriesPoster(seriesDoc, seriesUrl, rawClickedTitle) else null
        val poster = clickedPoster ?: seriesPoster

        val episodesList = mutableListOf<Episode>()
        val seenEpTitles = mutableSetOf<String>()

        extractCards(seriesDoc, seriesUrl).forEach { (epTitle, hrefRaw, posterRaw) ->
            val epUrl = resolveHref(hrefRaw, seriesUrl)
            if (epTitle.isBlank() || epUrl.isBlank()) return@forEach
            val normEpTitle = normalizeTitle(epTitle)
            if (seenEpTitles.contains(normEpTitle)) return@forEach
            seenEpTitles.add(normEpTitle)

            episodesList.add(newEpisode(epUrl) {
                this.name = epTitle
                this.posterUrl = fixImageUrl(posterRaw, seriesUrl)
            })
        }

        if (episodesList.isEmpty()) {
            return newMovieLoadResponse(mediaTitle, url, TvType.NSFW, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = mediaTitle
            }
        }

        val sortedEpisodes = episodesList.sortedWith(SeasonAwareComparator())

        return newTvSeriesLoadResponse(mediaTitle, seriesUrl, TvType.NSFW, sortedEpisodes) {
            this.posterUrl = poster
            this.backgroundPosterUrl = poster
            this.plot = mediaTitle
        }
    }

    // ------------------------------------------------------------------
    // CloudStream API — Load links
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val sourceDomain = domainOf(data)
        val found = AtomicBoolean(false)
        val seenUrls: MutableSet<String> = Collections.synchronizedSet(HashSet())

        // Build candidate page URLs in priority order:
        //   1. The URL we were given.
        //   2. Same path, different mirror domain.
        //   3. Slug-based fallbacks per site template.
        val candidates = LinkedHashSet<String>()
        candidates.add(data)
        mirrors.forEach { site ->
            if (site != sourceDomain && data.startsWith(sourceDomain)) {
                candidates.add(site + data.substring(sourceDomain.length))
            }
        }
        val slug = data.trimEnd('/').substringAfterLast("/")
        mirrors.forEach { site ->
            candidates.add(
                if (isXmaza2(site)) "$site/watch/$slug"
                else "$site/$slug/"
            )
        }

        coroutineScope {
            candidates.map { pageUrl ->
                async {
                    if (found.get()) return@async
                    tryExtractFromPage(pageUrl, seenUrls, callback, found)
                }
            }.awaitAll()
        }

        if (!found.get()) logD("no playable link found for $data")
        return found.get()
    }

    private suspend fun tryExtractFromPage(
        pageUrl: String,
        seenUrls: MutableSet<String>,
        callback: (ExtractorLink) -> Unit,
        found: AtomicBoolean
    ) {
        if (found.get()) return

        val html = retry(2) {
            withTimeoutOrNull(Timeouts.LINKS_MS) { app.get(pageUrl).text }
        } ?: return

        val doc = Jsoup.parse(html, pageUrl)

        val extracted = extractors
            .flatMap { it.extract(doc, html, pageUrl) }
            .distinctBy { it.url }
            .filter { isLikelyRealVideo(it.url) }

        if (extracted.isEmpty()) return

        val sourceName = domainOf(pageUrl)
            .removePrefix("https://")
            .removePrefix("http://")

        for (stream in extracted) {
            if (found.get()) return

            // De-dupe across mirrors.
            if (!seenUrls.add(stream.url)) continue

            val referer = stream.referer ?: pageUrl

            // VERIFY the link actually serves video content before emitting.
            val ok = verifyVideoLink(stream.url, referer)
            if (!ok) {
                logD("link failed verification: ${stream.url}")
                continue
            }

            callback.invoke(
                newExtractorLink(
                    source = sourceName,
                    name = sourceName,
                    url = stream.url,
                    type = if (stream.isM3u8) ExtractorLinkType.M3U8
                           else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                }
            )
            found.set(true)
        }
    }

    // ------------------------------------------------------------------
    // Season-aware comparator with modern episode regexes
    // ------------------------------------------------------------------

    private inner class SeasonAwareComparator : Comparator<Episode> {
        override fun compare(s1: Episode, s2: Episode): Int {
            val (base1, season1, ep1) = parseKey(s1.name ?: "")
            val (base2, season2, ep2) = parseKey(s2.name ?: "")
            if (base1 != base2) return base1.compareTo(base2)
            if (season1 != season2) return season1.compareTo(season2)
            return ep1.compareTo(ep2)
        }

        private fun parseKey(title: String): Triple<String, Int, Int> {
            val t = title.trim()

            // 1) "Show Season N Episode M"
            seasonEpisodeRegex.find(t)?.let { m ->
                return Triple(
                    normalizeTitle(m.groupValues[1]),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt()
                )
            }
            // 2) "Show SxxExx"
            sxxexxRegex.find(t)?.let { m ->
                return Triple(
                    normalizeTitle(m.groupValues[1]),
                    m.groupValues[2].toInt(),
                    m.groupValues[3].toInt()
                )
            }
            // 3) "Show N Episode M"
            episodeRegex.matchEntire(t)?.let { m ->
                val base = normalizeTitle(m.groupValues[1])
                val season = m.groupValues[2].toIntOrNull() ?: 1
                val ep = m.groupValues[3].toIntOrNull() ?: 0
                return Triple(base, season, ep)
            }
            // 4) Fallback: sort by normalized title.
            return Triple(normalizeTitle(t), 0, 0)
        }
    }
}
