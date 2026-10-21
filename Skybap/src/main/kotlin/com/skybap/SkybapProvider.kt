package com.skybap

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
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

class SkyBapProvider : MainAPI() {

    private val resolverUrl = "https://skybap.site"

    override var mainUrl = resolverUrl
    override var name = "SkyBap"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var resolvedBaseUrl: String? = null

    private suspend fun getActiveBaseUrl(): String {
        resolvedBaseUrl?.let { return it }
        val doc = app.get(resolverUrl, timeout = 15).document
        val href = doc.selectFirst("body > a")?.attr("href")
            ?: doc.selectFirst("a[href^=http]")?.attr("href")
            ?: throw ErrorLoadingException("SkyBap resolver page did not return a live URL")
        val normalized = when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            else -> "https://$href"
        }.trimEnd('/')
        resolvedBaseUrl = normalized
        return normalized
    }

    private fun absolute(base: String, href: String?): String? {
        if (href.isNullOrBlank()) return null
        return when {
            href.startsWith("http://") || href.startsWith("https://") -> href
            href.startsWith("/") -> "$base$href"
            else -> "$base/$href"
        }
    }

    private val titleMarker = "__sbTitle="

    private fun withEmbeddedTitle(href: String, title: String): String {
        val sep = if (href.contains("?")) "&" else "?"
        return "$href$sep$titleMarker${URLEncoder.encode(title, "UTF-8")}"
    }

    private fun splitEmbeddedTitle(url: String): Pair<String, String?> {
        val parts = url.split(Regex("[?&]${Regex.escape(titleMarker)}"), limit = 2)
        val cleanUrl = parts.getOrNull(0) ?: url
        val title = parts.getOrNull(1)?.let {
            try { URLDecoder.decode(it, "UTF-8") } catch (_: Exception) { null }
        }
        return cleanUrl to title
    }

    private suspend fun <T, R> List<T>.mapConcurrent(
        concurrency: Int = 8,
        transform: suspend (T) -> R
    ): List<R> = coroutineScope {
        val semaphore = Semaphore(concurrency)
        map { item -> async { semaphore.withPermit { transform(item) } } }.awaitAll()
    }

    private data class DetailProbe(val reachable: Boolean, val poster: String?)
    private val detailProbeCache = HashMap<String, DetailProbe>()

    private suspend fun probeDetailPage(detailUrl: String): DetailProbe {
        detailProbeCache[detailUrl]?.let { return it }
        val probe = try {
            val response = app.get(detailUrl, timeout = 8)
            if (response.code !in 200..299) {
                DetailProbe(false, null)
            } else {
                val base = getActiveBaseUrl()
                val src = response.document.selectFirst("div.movielist img")?.attr("src")?.trim()
                DetailProbe(true, absolute(base, src))
            }
        } catch (_: Exception) { DetailProbe(false, null) }
        detailProbeCache[detailUrl] = probe
        return probe
    }

    override val mainPage = mainPageOf(
        "category/Bollywood-Movies.html" to "Bollywood Movies",
        "category/All-Web-Series.html" to "Web Series",
        "category/Korean-and-China-Movies.html" to "Hot Asian Movies",
        "category/Hot-Short-Film.html" to "Hot Short Films"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val base = getActiveBaseUrl()
        val url = if (page <= 1) "$base/${request.data}"
        else {
            val slug = request.data.removePrefix("category/").removeSuffix(".html")
            "$base/category/$slug/$page.html"
        }
        val doc = app.get(url, timeout = 15).document
        val items = parseFolderListing(doc, base)
        val totalPages = Regex("Page\\s+\\d+\\s+of\\s+(\\d+)")
            .find(doc.text())?.groupValues?.get(1)?.toIntOrNull()
        val hasNext = if (totalPages != null) page < totalPages else items.isNotEmpty()
        return newHomePageResponse(HomePageList(request.name, items), hasNext)
    }

    private suspend fun parseFolderListing(doc: Document, base: String): List<SearchResponse> {
        val anchors = doc.select("div.L b a[href]").ifEmpty { doc.select("div.L a[href]") }
        return anchors.mapConcurrent(8) { it.toSearchResult(base) }.filterNotNull()
    }

    private suspend fun Element.toSearchResult(base: String): SearchResponse? {
        val href = absolute(base, sanitizeHeaderValue(this.attr("href"))) ?: return null
        val title = this.text().trim().ifBlank { return null }
        val probe = probeDetailPage(href)
        if (!probe.reachable) return null
        val type = when {
            title.contains("Web Series", true) -> TvType.TvSeries
            title.contains("Short Film", true) -> TvType.NSFW
            else -> TvType.Movie
        }
        return newMovieSearchResponse(title, withEmbeddedTitle(href, title), type) {
            this.posterUrl = probe.poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val base = getActiveBaseUrl()
        val url = "$base/search.php?search=${query.replace(" ", "+")}&cat=All"
        return parseFolderListing(app.get(url, timeout = 15).document, base)
    }

    override suspend fun load(url: String): LoadResponse {
        val (cleanUrl, embeddedTitle) = splitEmbeddedTitle(url)
        val base = getActiveBaseUrl()
        val doc = app.get(cleanUrl, timeout = 15).document
        val title = embeddedTitle ?: extractTitle(doc)
        val poster = doc.selectFirst("div.movielist img")?.let { absolute(base, it.attr("src").trim()) }
        val description = extractStory(doc)
        val tags = extractTags(doc)
        val videoLinks = extractRawLinks(doc)
        val data = videoLinks.joinToString("||")
        return newMovieLoadResponse(title, url, TvType.Movie, data) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    private fun extractTitle(doc: Document): String {
        doc.selectFirst("div.Robiul b")?.text()?.trim()?.let { if (it.isNotBlank()) return it }
        val titleTag = doc.selectFirst("title")?.text()?.trim()
        if (!titleTag.isNullOrBlank()) {
            val stripped = titleTag
                .replace(Regex("\\s*Full Movie Download\\s*$", RegexOption.IGNORE_CASE), "")
                .trim()
            return stripped.ifBlank { titleTag }
        }
        return doc.select("div b").firstOrNull { b ->
            val t = b.text().trim()
            t.length > 8 &&
                !t.contains("Story", true) &&
                !t.contains("Download", true) &&
                !t.contains("SkymoviesHD", true) &&
                !t.contains("Full Movies", true)
        }?.text()?.trim() ?: "Unknown title"
    }

    private fun extractStory(doc: Document): String? {
        val storyLabel = doc.select("b").firstOrNull {
            it.text().trim().startsWith("Story", true)
        } ?: return null
        val container = storyLabel.parent() ?: return null
        val fullText = container.text().trim()
        val idx = fullText.indexOf(":")
        return if (idx != -1) fullText.substring(idx + 1).trim() else fullText
    }

    private fun extractTags(doc: Document): List<String> {
        val genreAnchor = doc.selectFirst("div.L span a[href*=search.php]")
            ?: doc.selectFirst("span a[href*=search.php]")
            ?: return emptyList()
        return genreAnchor.text().split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun extractRawLinks(doc: Document): List<String> {
        val container = doc.selectFirst("div.Bolly") ?: doc.selectFirst("center div") ?: doc
        return container.select("a[href]")
            .map { sanitizeHeaderValue(it.attr("href")) }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
    }

    // ---------------------------------------------------------------------
    // FIX: pass referer = null. Each extractor now decides its own referer
    // (browser UA + panel Referer). Previously we passed `link` as both URL
    // and referer, which caused Cloudstream's fallback to construct
    // "https://howblogs.xyz/... HubCloud ..." as the source name AND left
    // the CDN request without a Referer → 403.
    // ---------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawLinks = data.split("||").map { it.trim() }.filter { it.isNotBlank() }
        if (rawLinks.isEmpty()) return false

        val collectedLinks = java.util.concurrent.CopyOnWriteArrayList<ExtractorLink>()
        val collectedSubs = java.util.concurrent.CopyOnWriteArrayList<SubtitleFile>()

        val collectCallback: (ExtractorLink) -> Unit = { collectedLinks.add(it) }
        val collectSubtitleCallback: (SubtitleFile) -> Unit = { collectedSubs.add(it) }

        val outcomes = rawLinks.mapConcurrent(concurrency = 6) { link ->
            try {
                // referer = null → let each extractor supply its own
                loadExtractor(link, null, collectSubtitleCallback, collectCallback)
                true
            } catch (_: Exception) { false }
        }

        for (sub in collectedSubs) subtitleCallback(sanitizeSubtitle(sub))
        for (link in collectedLinks) callback(sanitizeExtractorLink(link))

        return outcomes.any { it }
    }

    private fun sanitizeHeaderValue(value: String): String =
        value.replace(Regex("[\\r\\n\\t]+"), "").trim()

    private suspend fun sanitizeExtractorLink(link: ExtractorLink): ExtractorLink {
        val cleanedUrl = sanitizeHeaderValue(link.url)
        val cleanedReferer = sanitizeHeaderValue(link.referer)
        if (cleanedUrl == link.url && cleanedReferer == link.referer) return link
        return try {
            newExtractorLink(link.source, link.name, cleanedUrl, link.type) {
                this.quality = link.quality
                this.headers = link.headers
                this.referer = cleanedReferer
            }
        } catch (_: Exception) { link }
    }

    private fun sanitizeSubtitle(sub: SubtitleFile): SubtitleFile {
        val cleanedUrl = sanitizeHeaderValue(sub.url)
        return if (cleanedUrl == sub.url) sub else SubtitleFile(sub.lang, cleanedUrl)
    }
}
