package com.Xmaza

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

class XmazaProvider : MainAPI() {
    override var mainUrl = "https://xmaza2.net"
    override var name = "Xmaza"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    companion object {
        private const val TAG = "XmazaProvider"
    }

    init {
        // Exactly the same User-Agent as the Python tester
        app.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
    }

    private val mirrors = listOf(
        "https://ottdude.com",
        "https://maalvdo.net",
        "https://xmaza.gg",
        "https://zmaal.net",
        "https://xmaza2.net"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ott/ullu" to "ULLU",
        "$mainUrl/ott/atrangii" to "Atrangii",
        "$mainUrl/ott/primeplay" to "PrimePlay",
        "$mainUrl/ott/voovi" to "Voovi"
    )

    private val styleUrlRegex = Regex("url\\((['\"]?)(.*?)\\1\\)")
    private val episodeRegex = Regex("^(.*?)(?:\\s+(\\d+))?\\s+Episode\\s+(\\d+)\\s*$", RegexOption.IGNORE_CASE)

    // ---------- Video extraction patterns ----------
    private val jsonLdContentUrlRegex = Regex("\"contentUrl\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
    private val jsonLdEmbedUrlRegex = Regex("\"embedUrl\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)

    private val quotedVideoRegex = Regex(
        "[\"'](/?/?[^\"']+\\.(?:mp4|m3u8)[^\"']*)[\"']",
        RegexOption.IGNORE_CASE
    )
    private val jsVarRegex = Regex(
        "(?:file|source|url|video)\\s*:\\s*[\"']([^\"']+\\.(?:mp4|m3u8)[^\"']*)[\"']",
        RegexOption.IGNORE_CASE
    )
    private val videoUrlLabelRegex = Regex(
        "Video\\s+url\\s*:\\s*(https?://[^\\s]+)",
        RegexOption.IGNORE_CASE
    )
    private val anyVideoRegex = Regex(
        "https?://[^\\s<>\"']+\\.(?:mp4|m3u8)[^\\s<>\"']*",
        RegexOption.IGNORE_CASE
    )

    // Poster cache
    private val posterCache = ConcurrentHashMap<String, String>()

    // ---------- Helper functions ----------
    private fun normalizeTitle(title: String): String =
        title.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun domainOf(url: String): String {
        val protocolEnd = url.indexOf("//") + 2
        return url.substring(0, protocolEnd) + url.substring(protocolEnd).substringBefore("/")
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
                    URLDecoder.decode(encoded, "UTF-8")
                } catch (e: Exception) {
                    full
                }
            } else full
        }

        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> root + url
            else -> url
        }
    }

    private fun resolveHref(hrefRaw: String, site: String): String =
        if (hrefRaw.startsWith("/")) domainOf(site) + hrefRaw else hrefRaw

    // ---------- Card extraction ----------
    private fun extractCards(doc: Document, site: String): List<Triple<String, String, String?>> {
        val results = mutableListOf<Triple<String, String, String?>>()

        when {
            site.contains("xmaza2") -> {
                doc.select("a.group.block").forEach { a ->
                    val title = a.selectFirst("h4")?.text()?.trim() ?: a.attr("title")
                    val href = a.attr("href")
                    val img = a.selectFirst("img")
                    val raw = img?.attr("data-src")?.ifBlank { img.attr("data-lazy-src") }?.ifBlank { img.attr("src") }
                    if (title.isNotBlank() && href.isNotBlank()) results.add(Triple(title, href, raw))
                }
            }

            site.contains("zmaal") -> {
                doc.select("article").forEach { article ->
                    val a = article.selectFirst("a.link") ?: return@forEach
                    val title = a.attr("title").ifBlank { a.attr("aria-label") }.ifBlank { a.text() }
                    val href = a.attr("href")
                    val img = article.selectFirst("img")
                    val raw = img?.attr("data-src")?.ifBlank { img.attr("src") }
                    if (title.isNotBlank() && href.isNotBlank()) results.add(Triple(title, href, raw))
                }
            }

            else -> {
                doc.select("a.video").forEach { a ->
                    val title = a.selectFirst("h2.vtitle")?.text()?.trim() ?: a.attr("title")
                    val href = a.attr("href")
                    val img = a.selectFirst("img")
                    val imgRaw = img?.attr("data-src")?.ifBlank { img.attr("src") }
                    val dataBg = a.attr("data-bg")
                    val styleRaw = styleUrlRegex.find(a.attr("style"))?.groupValues?.get(2)
                    val raw = imgRaw ?: dataBg.ifBlank { styleRaw }
                    if (title.isNotBlank() && href.isNotBlank()) results.add(Triple(title, href, raw))
                }
            }
        }

        return results
    }

    // ---------- Series poster extraction (fallback) ----------
    private fun extractSeriesPoster(doc: Document, pageUrl: String, title: String): String? {
        val candidates = mutableListOf<String>()

        doc.selectFirst("meta[property=\"og:image\"]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        doc.selectFirst("meta[name=\"twitter:image\"]")?.attr("content")?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        doc.selectFirst("link[rel=\"image_src\"]")?.attr("href")?.takeIf { it.isNotBlank() }?.let { candidates.add(it) }
        doc.selectFirst(".wp-post-image, .attachment-post-thumbnail, .post-thumbnail img")?.let {
            val raw = it.attr("data-src").ifBlank { it.attr("src") }
            if (raw.isNotBlank()) candidates.add(raw)
        }

        if (candidates.isEmpty()) return null

        fun tokenize(s: String) =
            s.lowercase().split(Regex("[-_.\\s/%0-9]+")).filter { it.length > 2 }.toSet()

        val titleTokens = tokenize(title)
        val siteTokens = tokenize(domainOf(pageUrl))
        val brandingWords = setOf("logo", "icon", "default", "placeholder")

        var bestRaw: String? = null
        var bestScore = Int.MIN_VALUE

        for (raw in candidates) {
            val filename = raw.substringAfterLast("/").substringBefore("?")
            val fileTokens = tokenize(filename)
            val overlap = fileTokens.intersect(titleTokens).size

            var score = overlap * 10
            val isBranding = fileTokens.isNotEmpty() && fileTokens.all { it in siteTokens || it in brandingWords }
            if (isBranding) score -= 50
            val fnLower = filename.lowercase()
            if (fnLower.contains("logo") || fnLower.contains("icon") || fnLower.contains("default")) score -= 50

            if (score > bestScore) {
                bestScore = score
                bestRaw = raw
            }
        }

        if (bestScore <= 0) return null
        return fixImageUrl(bestRaw, pageUrl)
    }

    // ---------- Main page / search / load ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = extractCards(document, request.data).mapNotNull { (title, hrefRaw, posterRaw) ->
            val href = resolveHref(hrefRaw, request.data)
            if (title.isBlank() || href.isBlank()) return@mapNotNull null
            val poster = fixImageUrl(posterRaw, request.data)
            if (poster != null) posterCache[href] = poster
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true)
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableMapOf<String, SearchResponse>()
        val mutex = Mutex()

        coroutineScope {
            mirrors.map { site ->
                async {
                    try {
                        val searchUrl = if (site.contains("xmaza2")) "$site/search/$query" else "$site/?s=$query"
                        val doc = app.get(searchUrl).document

                        extractCards(doc, site).forEach { (title, hrefRaw, posterRaw) ->
                            val key = normalizeTitle(title)
                            val href = resolveHref(hrefRaw, site)
                            if (key.isBlank() || href.isBlank()) return@forEach
                            val poster = fixImageUrl(posterRaw, site)
                            if (poster != null) posterCache[href] = poster

                            mutex.withLock {
                                if (!results.containsKey(key)) {
                                    results[key] = newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                                        this.posterUrl = poster
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }

        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val epDoc = app.get(url).document

        var seriesUrl: String? = null
        for (a in epDoc.select("a")) {
            val href = a.attr("href")
            if (href.isBlank()) continue
            val full = if (href.startsWith("http")) href else domainOf(url) + href
            val pathParts = full.substringAfter("://").substringAfter("/").split("/").filter { it.isNotBlank() }

            if ((pathParts.contains("series") || pathParts.contains("web-series")) && pathParts.size > 1) {
                seriesUrl = full
                break
            }
        }

        if (seriesUrl == null) return null

        val seriesDoc = app.get(seriesUrl).document
        val title = seriesDoc.selectFirst("h1, h2")?.text()?.trim() ?: "Unknown Series"

        var poster = posterCache[url] ?: posterCache[seriesUrl]
        if (poster == null) {
            poster = extractSeriesPoster(seriesDoc, seriesUrl, title)
        }

        val episodesList = mutableListOf<Episode>()
        val seenEpTitles = mutableSetOf<String>()

        extractCards(seriesDoc, seriesUrl).forEach { (epTitle, hrefRaw, posterRaw) ->
            val epUrl = resolveHref(hrefRaw, seriesUrl)
            if (epTitle.isBlank() || epUrl.isBlank()) return@forEach

            val normEpTitle = normalizeTitle(epTitle)
            if (seenEpTitles.contains(normEpTitle)) return@forEach
            seenEpTitles.add(normEpTitle)

            val epSlug = epUrl.trimEnd('/').substringAfterLast("/")
            val episodePoster = fixImageUrl(posterRaw, seriesUrl) ?: poster
            episodesList.add(
                newEpisode(epSlug) {
                    this.name = epTitle
                    this.posterUrl = episodePoster
                }
            )
        }

        val sortedEpisodes = episodesList.sortedWith(SeasonAwareComparator())

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
            this.posterUrl = poster
        }
    }

    // ---------- Video URL extraction with logging ----------
    private fun extractVideoUrls(html: String, baseUrl: String, sourceName: String): Set<String> {
        Log.d(TAG, "extractVideoUrls called for $sourceName, HTML length: ${html.length}")

        val candidates = mutableSetOf<String>()
        val unescapedHtml = Parser.unescapeEntities(html, false)

        // 1. Parse with Jsoup
        val doc = Parser.parse(unescapedHtml, baseUrl)

        // 1a. <source>, <video>, <iframe>
        val mediaTags = doc.select("source, video")
        Log.d(TAG, "$sourceName: Found ${mediaTags.size} source/video tags")
        mediaTags.forEach { tag ->
            listOf("src", "data-src").forEach { attr ->
                val val_ = tag.attr(attr)
                if (val_.isNotBlank()) {
                    candidates.add(val_)
                    Log.d(TAG, "$sourceName: Added from $tag.name $attr = $val_")
                }
            }
        }
        val iframes = doc.select("iframe")
        Log.d(TAG, "$sourceName: Found ${iframes.size} iframes")
        iframes.forEach { iframe ->
            iframe.attr("src").takeIf { it.isNotBlank() }?.let {
                candidates.add(it)
                Log.d(TAG, "$sourceName: Added from iframe src = $it")
            }
        }

        // 1b. Meta tags: og:video and twitter:player
        val metaTags = doc.select("meta[property='og:video'], meta[name='twitter:player']")
        Log.d(TAG, "$sourceName: Found ${metaTags.size} og:video/twitter:player meta tags")
        metaTags.forEach { meta ->
            meta.attr("content").takeIf { it.isNotBlank() }?.let {
                candidates.add(it)
                Log.d(TAG, "$sourceName: Added from meta ${meta.attr("property")} = $it")
            }
        }

        // 1c. JSON-LD script tag
        val jsonScripts = doc.select("script[type='application/ld+json']")
        Log.d(TAG, "$sourceName: Found ${jsonScripts.size} JSON-LD scripts")
        jsonScripts.forEach { script ->
            val scriptContent = script.data() ?: ""
            Log.d(TAG, "$sourceName: JSON-LD script length: ${scriptContent.length}")
            jsonLdContentUrlRegex.findAll(scriptContent).forEach { match ->
                candidates.add(match.groupValues[1])
                Log.d(TAG, "$sourceName: JSON-LD contentUrl = ${match.groupValues[1]}")
            }
            jsonLdEmbedUrlRegex.findAll(scriptContent).forEach { match ->
                candidates.add(match.groupValues[1])
                Log.d(TAG, "$sourceName: JSON-LD embedUrl = ${match.groupValues[1]}")
            }
            anyVideoRegex.findAll(scriptContent).forEach { match ->
                candidates.add(match.value)
                Log.d(TAG, "$sourceName: JSON-LD anyVideo = ${match.value}")
            }
        }

        // 2. Fallback regex patterns on the whole unescaped HTML
        var count = 0
        quotedVideoRegex.findAll(unescapedHtml).forEach { match ->
            candidates.add(match.groupValues[1])
            count++
        }
        Log.d(TAG, "$sourceName: quotedVideoRegex found $count")
        count = 0
        jsVarRegex.findAll(unescapedHtml).forEach { match ->
            candidates.add(match.groupValues[1])
            count++
        }
        Log.d(TAG, "$sourceName: jsVarRegex found $count")
        count = 0
        videoUrlLabelRegex.findAll(unescapedHtml).forEach { match ->
            candidates.add(match.groupValues[1])
            count++
        }
        Log.d(TAG, "$sourceName: videoUrlLabelRegex found $count")
        count = 0
        anyVideoRegex.findAll(unescapedHtml).forEach { match ->
            candidates.add(match.value)
            count++
        }
        Log.d(TAG, "$sourceName: anyVideoRegex found $count")

        // Resolve and clean URLs
        val resolved = mutableSetOf<String>()
        val baseDomain = domainOf(baseUrl)
        for (raw in candidates) {
            var cleaned = raw.trim()
                .replace(Regex("\\\\+$"), "")   // remove trailing backslashes
                .replace(Regex("^\\\\+"), "")   // remove leading backslashes
                .trim()

            val url = when {
                cleaned.startsWith("//") -> "https:$cleaned"
                cleaned.startsWith("/") -> baseDomain + cleaned
                cleaned.startsWith("http") -> cleaned
                else -> null
            }
            if (url != null) resolved.add(url)
        }
        Log.d(TAG, "$sourceName: Resolved ${resolved.size} URLs")
        resolved.forEach { Log.d(TAG, "$sourceName: final URL = $it") }

        return resolved
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data
        Log.d(TAG, "loadLinks called with slug: $slug")

        val mirrorUrls = mirrors.associateWith { site ->
            when {
                site.contains("xmaza2") -> "$site/watch/$slug"
                else -> "$site/$slug/"
            }
        }

        var foundAny = false
        val allLinks = mutableListOf<ExtractorLink>()

        coroutineScope {
            mirrorUrls.map { (site, mirrorUrl) ->
                async {
                    try {
                        Log.d(TAG, "Fetching: $mirrorUrl")
                        val response = app.get(mirrorUrl)
                        val html = response.text
                        val sourceName = domainOf(site).removePrefix("https://").removePrefix("http://")

                        // Log status code if available
                        try {
                            val code = response.code
                            Log.d(TAG, "$sourceName HTTP status: $code")
                        } catch (e: Exception) {
                            Log.d(TAG, "$sourceName: could not get status code")
                        }

                        // Log first 500 chars of HTML for debugging
                        val preview = html.take(500).replace("\n", " ").replace("\r", "")
                        Log.d(TAG, "$sourceName HTML preview: $preview")

                        val videoUrls = extractVideoUrls(html, mirrorUrl, sourceName)

                        if (videoUrls.isEmpty()) {
                            Log.w(TAG, "$sourceName: No video URLs found")
                        }

                        videoUrls.forEach { videoUrl ->
                            val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
                            val link = newExtractorLink(
                                source = sourceName,
                                name = sourceName,
                                url = videoUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mirrorUrl
                                this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P1080.value
                            }
                            allLinks.add(link)
                            foundAny = true
                            Log.d(TAG, "$sourceName: Added link: $videoUrl")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching $mirrorUrl: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }

        val uniqueLinks = allLinks.distinctBy { it.url }
        Log.d(TAG, "Total unique links found: ${uniqueLinks.size}")
        uniqueLinks.forEach { callback.invoke(it) }

        return foundAny
    }

    // ---------- Episode comparator ----------
    private inner class SeasonAwareComparator : Comparator<Episode> {
        override fun compare(s1: Episode, s2: Episode): Int {
            val (base1, season1, ep1) = parseKey(s1.name ?: "")
            val (base2, season2, ep2) = parseKey(s2.name ?: "")
            if (base1 != base2) return base1.compareTo(base2)
            if (season1 != season2) return season1.compareTo(season2)
            return ep1.compareTo(ep2)
        }

        private fun parseKey(title: String): Triple<String, Int, Int> {
            val m = episodeRegex.matchEntire(title.trim()) ?: return Triple(normalizeTitle(title), 0, 0)
            val base = normalizeTitle(m.groupValues[1])
            val season = m.groupValues[2].toIntOrNull() ?: 1
            val ep = m.groupValues[3].toIntOrNull() ?: 0
            return Triple(base, season, ep)
        }
    }
}
