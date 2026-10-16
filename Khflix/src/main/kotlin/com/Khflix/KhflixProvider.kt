// KhflixProvider.kt
package com.Khflix

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Khflix provider.
 *
 * Confirmed URL schema (from css.khflix.top/Khflix.Playlist.js):
 *   TV video    : https://cdn1.khflixcdnvideos.top/{series}{EE}.mp4
 *   TV subtitle : https://subtitle.khflix.top/{series}{EE}.vtt     (English only)
 *
 * Only English subtitles are emitted (no language suffix).
 */
class KhflixProvider : MainAPI() {
    override var mainUrl = "https://khflix.com"
    override var name = "Khflix"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.AsianDrama
    )

    companion object {
        private const val TAG = "Khflix"
        private const val DEBUG = true

        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"

        private const val FALLBACK_VIDEO_HOST = "cdn1.khflixcdnvideos.top"
        private const val FALLBACK_SUB_HOST = "subtitle.khflix.top"

        private val HOST_BLOCKLIST = listOf(
            "tmdb", "google", "gstatic", "cloudflare", "jsdelivr", "tailwind",
            "unpkg", "ampproject", "w.org", "wordpress", "gravatar", "facebook",
            "twitter", "youtube", "youtu.be", "schema.org", "jquery", "bootstrap",
            "fontawesome", "wp.com", "imdb", "instagram", "tiktok",
            "acscdn", "jwplayer", "jwplatform", "vimeo", "vimeocdn", "ok.ru",
            "javip", "tithebesew", "pinterest", "amazon", "media-amazon",
            "kissflix", "t.me", "w3.org", "blogger", "blogspot"
        )

        private val SCRIPT_SKIP = listOf(
            "wp-includes", "jquery", "google", "cloudflare", "gstatic",
            "wp-content/plugins", "bootstrap", "tailwind", "unpkg", "jsdelivr",
            "googletagmanager", "acscdn", "cloudflareinsights"
        )
    }

    private val playerApi get() = "$mainUrl/wp-json/dooplayer/v2/"
    private val ajaxUrl get() = "$mainUrl/wp-admin/admin-ajax.php"

    private data class Hosts(val video: String?, val sub: String?)

    @Volatile
    private var hostCache: Hosts? = null

    // ------------------------------------------------------------------
    // Logging
    // ------------------------------------------------------------------
    private fun dbg(stage: String, msg: String) {
        if (DEBUG) Log.e(TAG, "[$stage] $msg")
    }

    private fun err(stage: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, "[$stage] $msg", t) else Log.e(TAG, "[$stage] $msg")
    }

    private fun String.cut(n: Int = 300): String =
        if (length <= n) this else take(n) + "...(+${length - n} chars)"

    private fun headers(referer: String = "$mainUrl/", ajax: Boolean = false): Map<String, String> {
        val h = mutableMapOf(
            "User-Agent" to UA,
            "Referer" to referer,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,application/json;q=0.9,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        )
        if (ajax) h["X-Requested-With"] = "XMLHttpRequest"
        return h
    }

    // ==================================================================
    // HOME PAGE
    // ==================================================================
    override val mainPage = listOf(
        MainPageData("Latest", "", isHorizontalImages = true),
        MainPageData("Movies", "movies", isHorizontalImages = true),
        MainPageData("TV Shows", "tvshows", isHorizontalImages = true),
        MainPageData("Action", "genre/action"),
        MainPageData("Comedy", "genre/comedy"),
        MainPageData("Drama", "genre/drama"),
        MainPageData("Horror", "genre/horror"),
        MainPageData("Romance", "genre/romance"),
        MainPageData("Thriller", "genre/thriller"),
        MainPageData("K-Drama", "genre/k-drama"),
        MainPageData("C-Drama", "genre/c-drama"),
        MainPageData("J-Drama", "genre/j-drama"),
        MainPageData("Khmer Dubbed", "genre/movie-speak-khmer"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty())
            "$mainUrl/page/$page/"
        else
            "$mainUrl/${request.data}/page/$page/"

        val doc = fetchDocument(url)
        val items = doc.select("article.item").mapNotNull { it.toSearchResult() }
        dbg("HOME", "${request.name} page=$page cards=${items.size}")
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    // ==================================================================
    // SEARCH
    // ==================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = fetchDocument("$mainUrl/?s=$q")
        val out = mutableListOf<SearchResponse>()

        doc.select("div.result-item").forEach { el -> el.toSearchResult()?.let { out.add(it) } }
        if (out.isEmpty()) {
            doc.select("article.item").forEach { el -> el.toSearchResult()?.let { out.add(it) } }
        }
        dbg("SEARCH", "query='$query' results=${out.size}")
        return out.distinctBy { it.url }
    }

    // ==================================================================
    // LOAD
    // ==================================================================
    override suspend fun load(url: String): LoadResponse? {
        val html = fetchHtml(url)
        if (html.isNullOrBlank()) {
            err("LOAD", "empty HTML for $url")
            return null
        }
        val doc = Jsoup.parse(html, url)

        val title = doc.selectFirst("div.sheader h1")?.text()?.trim()
            ?: doc.selectFirst("div.data h1")?.text()?.trim()
            ?: doc.title().substringBefore(" - ").trim()
        if (title.isBlank()) {
            err("LOAD", "no title found for $url")
            return null
        }

        val poster = doc.selectFirst("div.poster img")?.let { imgSrc(it) }

        val ogImages = doc.select("meta[property='og:image']")
            .mapNotNull { it.attr("content").trim().takeIf { c -> c.isNotBlank() } }
        val backdrop = ogImages.getOrNull(1) ?: ogImages.lastOrNull() ?: poster

        val year = Regex("""(\d{4})""")
            .find(doc.selectFirst("span.date")?.text() ?: "")?.value?.toIntOrNull()

        val genres = doc.select("div.sgeneros a")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        val score: Score? = extractImdbRating(doc)?.let { Score.from10(it) }

        val plot = doc.selectFirst("div#info .wp-content p")?.text()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        val isTv = url.contains("/tvshows/")
        val isAsian = genres.any {
            it.contains("k-drama", true) || it.contains("c-drama", true) ||
                    it.contains("j-drama", true) || it.contains("t-drama", true)
        }

        val cfg = extractKhflixConfig(html)
        val postId = extractPostId(html, doc)
        dbg("LOAD", "url=$url isTv=$isTv title='$title' postId='$postId' cfg=$cfg")

        return if (isTv) {
            val episodes = mutableListOf<Episode>()

            val seriesPrefix = cfg?.get("series")
            val episodeCount = cfg?.get("episodes")?.toIntOrNull() ?: 0
            if (!seriesPrefix.isNullOrBlank() && episodeCount > 0) {
                val season = Regex("""S(\d+)E""", RegexOption.IGNORE_CASE)
                    .find(seriesPrefix)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                for (n in 1..episodeCount) {
                    val padded = n.toString().padStart(2, '0')
                    episodes.add(newEpisode("$url|tv|$seriesPrefix|$padded") {
                        this.name = "Episode $n"
                        this.episode = n
                        this.season = season
                    })
                }
                dbg("LOAD", "episodes from khflixConfig: ${episodes.size} prefix=$seriesPrefix")
            }

            if (episodes.isEmpty()) {
                episodes.addAll(parseEpisodeList(doc))
            }

            if (episodes.isEmpty()) {
                diagnoseTvPage(html, doc)
                if (findMediaUrls(html).isNotEmpty()) {
                    episodes.add(newEpisode("$url|page") { this.name = "Episode 1"; this.episode = 1 })
                }
            }

            newTvSeriesLoadResponse(
                title, url, if (isAsian) TvType.AsianDrama else TvType.TvSeries, episodes
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = genres
                this.score = score
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, "$url|movie|$postId") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = genres
                this.score = score
            }
        }
    }

    // ==================================================================
    // LOAD LINKS
    // ==================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        dbg("LINKS", "START data='$data'")
        val parts = data.split("|")
        val pageUrl = parts[0]
        val kind = parts.getOrNull(1) ?: "page"
        if (!pageUrl.startsWith("http")) return false

        val direct = mutableListOf<Pair<String, String>>()
        val embeds = mutableListOf<String>()
        var baseName: String? = null

        val pageHtml = fetchHtml(pageUrl).orEmpty()
        val hosts = resolveHosts(pageUrl, pageHtml)

        when (kind) {
            "tv" -> {
                val seriesPrefix = parts.getOrNull(2).orEmpty()
                val padded = parts.getOrNull(3) ?: "01"
                val base = "$seriesPrefix$padded"
                baseName = base

                val videoHost = FALLBACK_VIDEO_HOST.ifBlank { hosts.video.orEmpty() }
                dbg("TV", "base='$base' usingVideoHost=$videoHost")

                if (videoHost.isNotBlank()) {
                    val videoUrl = "https://$videoHost/$base.mp4"
                    dbg("TV", "video url=$videoUrl")
                    direct.add("tv-cdn" to videoUrl)
                }
            }

            else -> {
                val postIdHint = if (kind == "movie") parts.getOrNull(2).orEmpty() else ""
                resolveFromPage(
                    pageUrl,
                    if (kind == "movie") "movie" else "tv",
                    postIdHint, pageHtml, direct, embeds
                )
                baseName = direct
                    .sortedBy { if (it.second.contains(".m3u8")) 0 else 1 }
                    .firstOrNull { it.second.contains("khflix") }
                    ?.second?.let { baseNameFromMedia(it) }
                dbg("MOVIE", "baseName='$baseName'")
            }
        }

        // ---- Emit English subtitle only ----
        emitEnglishSubtitle(baseName, hosts.sub ?: FALLBACK_SUB_HOST, pageUrl, subtitleCallback)

        // ---- Emit video sources ----
        var count = 0
        val seen = mutableSetOf<String>()
        for ((label, src) in direct.sortedBy { if (it.second.contains(".m3u8")) 0 else 1 }) {
            if (src.isBlank() || !seen.add(src)) continue
            val type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            dbg("EMIT", "[$label] type=$type url=$src")
            callback.invoke(
                newExtractorLink(this.name, this.name, src, type) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers()
                }
            )
            count++
        }

        // ---- Hand non-direct embeds to extractors ----
        for (embed in embeds.distinct()) {
            try {
                loadExtractor(embed, "$mainUrl/", subtitleCallback) { link ->
                    count++
                    callback.invoke(link)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err("EMIT", "loadExtractor FAILED for $embed", e)
            }
        }

        dbg("LINKS", "DONE links=$count")
        return count > 0
    }

    // ==================================================================
    // SUBTITLES — English only
    // ==================================================================
    private suspend fun emitEnglishSubtitle(
        base: String?,
        subHost: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (base.isNullOrBlank() || subHost.isBlank()) {
            dbg("SUB", "no base or subHost — skipping English subtitle")
            return
        }

        // Canonical English URL: no language suffix
        val url = "https://$subHost/$base.vtt"
        val code = quickProbe(url, referer)
        dbg("SUB", "probe code=$code url=$url")

        if (code in 200..299 || code == 206) {
            dbg("SUB", "→ English: $url")
            subtitleCallback.invoke(newSubtitleFile("English", url))
        } else {
            dbg("SUB", "no English subtitle (code=$code)")
        }
    }

    private suspend fun quickProbe(url: String, referer: String): Int {
        return try {
            app.head(url, headers = headers(referer)).code
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            -1
        }
    }

    // ==================================================================
    // SOURCE RESOLUTION FOR PAGES
    // ==================================================================
    private suspend fun resolveFromPage(
        pageUrl: String,
        type: String,
        postIdHint: String,
        html: String,
        direct: MutableList<Pair<String, String>>,
        embeds: MutableList<String>
    ) {
        val doc = if (html.isNotBlank()) Jsoup.parse(html, pageUrl) else null
        var postId = postIdHint
        if (postId.isBlank() && doc != null) postId = extractPostId(html, doc)
        dbg("MOVIE", "resolve type=$type postId='$postId'")

        // Strategy 1: DooPlay REST API
        if (postId.isNotBlank()) {
            for (nume in 1..5) {
                try {
                    val res = app.get("$playerApi$postId/$type/$nume",
                        headers = headers(pageUrl, ajax = true))
                    dbg("MOVIE", "REST nume=$nume code=${res.code}")
                    if (!res.isSuccessful) break
                    val embed = extractEmbedUrl(res.text)
                    if (embed.isBlank()) break
                    handleEmbed(embed, direct, embeds, "rest#$nume")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    break
                }
            }
        }

        // Strategy 1b: page HTML for canonical media URLs
        val pageMedia = findMediaUrls(html)
        if (pageMedia.isNotEmpty()) {
            dbg("MOVIE", "page media: $pageMedia")
            pageMedia.forEach { direct.add("page" to it) }
        }

        // Strategy 2: admin-ajax
        if (direct.isEmpty() && embeds.isEmpty() && doc != null) {
            val options = doc.select("li.dooplay_player_option, li[data-post][data-nume]")
            for (opt in options) {
                val post = opt.attr("data-post").ifBlank { postId }
                val nume = opt.attr("data-nume").ifBlank { "1" }
                val optType = opt.attr("data-type").ifBlank { type }
                try {
                    val res = app.post(
                        ajaxUrl,
                        headers = headers(pageUrl, ajax = true),
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to post, "nume" to nume, "type" to optType
                        )
                    )
                    val embed = extractEmbedUrl(res.text)
                    if (embed.isNotBlank()) handleEmbed(embed, direct, embeds, "ajax#$nume")
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) { }
            }
        }

        // Strategy 3: iframes
        if (direct.isEmpty() && embeds.isEmpty() && doc != null) {
            doc.select("iframe[src], iframe[data-src]")
                .map { it.attr("src").ifBlank { it.attr("data-src") } }
                .filter { it.isNotBlank() }
                .forEach { handleEmbed(fixUrl(it), direct, embeds, "iframe") }
        }
    }

    // ==================================================================
    // HOST DISCOVERY
    // ==================================================================
    private suspend fun resolveHosts(pageUrl: String, html: String): Hosts {
        hostCache?.let { return it }

        val texts = mutableListOf(html.replace("\\/", "/"))
        val doc = Jsoup.parse(html, pageUrl)
        val scripts = doc.select("script[src]").map { it.absUrl("src") }
            .filter { s -> s.isNotBlank() && SCRIPT_SKIP.none { s.contains(it, true) } }
            .distinct().take(20)
        for (s in scripts) {
            try {
                texts.add(app.get(s, headers = headers(pageUrl)).text.replace("\\/", "/"))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { }
        }

        val mainHost = try { URI(mainUrl).host.orEmpty().removePrefix("www.") } catch (_: Exception) { "" }
        val brand = mainHost.substringBefore('.')

        val urlHost = Regex("""(?:https?:)?//([a-z0-9][a-z0-9.-]*\.[a-z]{2,})""", RegexOption.IGNORE_CASE)
        val bareHost = Regex(
            """["']([a-z0-9][a-z0-9-]*(?:\.[a-z0-9-]+)*\.(?:top|xyz|cc|site|online|net|com|io|me|tv|club|live|pro|fun))["']""",
            RegexOption.IGNORE_CASE
        )
        val found = linkedSetOf<String>()
        for (t in texts) {
            urlHost.findAll(t).forEach { found.add(it.groupValues[1].lowercase()) }
            bareHost.findAll(t).forEach { found.add(it.groupValues[1].lowercase()) }
        }
        val valid = found.filter { h ->
            h != mainHost && h != "www.$mainHost" && HOST_BLOCKLIST.none { h.contains(it) }
        }
        val brandHosts = valid.filter { brand.isNotBlank() && it.contains(brand) }
        val otherHosts = valid.filter { !brandHosts.contains(it) }

        var video = brandHosts.firstOrNull {
            (it.contains("cdn") || it.contains("video")) && !it.contains("hls")
        } ?: brandHosts.firstOrNull { it.contains("hls") }
        ?: otherHosts.firstOrNull { (it.contains("cdn") || it.contains("video")) && !it.contains("hls") }

        var sub = brandHosts.firstOrNull { it.contains("sub") }
            ?: otherHosts.firstOrNull { it.contains("sub") }

        if (video.isNullOrBlank() && FALLBACK_VIDEO_HOST.isNotBlank()) video = FALLBACK_VIDEO_HOST
        if (sub.isNullOrBlank() && FALLBACK_SUB_HOST.isNotBlank()) sub = FALLBACK_SUB_HOST

        val result = Hosts(video, sub)
        dbg("HOST", "resolved video=$video sub=$sub")
        hostCache = result
        return result
    }

    // ==================================================================
    // HELPERS
    // ==================================================================

    private fun findMediaUrls(html: String): List<String> =
        Regex("""https?://[^"'\s<>\\]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>\\]*)?""")
            .findAll(html.replace("\\/", "/"))
            .map { it.value }
            .distinct()
            .toList()

    private fun baseNameFromMedia(url: String): String? {
        val path = url.substringBefore("?").substringAfter("://").substringAfter("/", "")
        val segs = path.split("/").filter { it.isNotBlank() }
        if (segs.isEmpty()) return null
        val last = segs.last()
        return if (last.endsWith(".m3u8") && segs.size >= 2) segs[segs.size - 2]
        else last.substringBeforeLast(".")
    }

    private fun handleEmbed(
        embed: String,
        direct: MutableList<Pair<String, String>>,
        embeds: MutableList<String>,
        label: String
    ) {
        val decoded = decodeJwPlayerUrl(embed)
        if (decoded.isNotBlank()) direct.add(label to decoded)
        else if (embed.startsWith("http")) embeds.add(embed)
    }

    private fun extractEmbedUrl(body: String): String {
        val raw = Regex(""""embed_url"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .find(body)?.groupValues?.get(1) ?: return ""
        val cleaned = raw
            .replace("\\/", "/").replace("\\u0026", "&")
            .replace("\\u003d", "=").replace("\\u003f", "?")
            .replace("\\\"", "\"")
        if (cleaned == "null") return ""
        if (cleaned.contains("<iframe")) {
            return Regex("""src=["']([^"']+)["']""").find(cleaned)
                ?.groupValues?.get(1)?.let { fixUrl(it) } ?: ""
        }
        return cleaned
    }

    private fun extractKhflixConfig(html: String): Map<String, String>? {
        val m = Regex("""khflixConfig\s*=\s*\{(.*?)\}\s*;?""", RegexOption.DOT_MATCHES_ALL)
            .find(html) ?: return null
        val body = m.groupValues[1]
        val out = mutableMapOf<String, String>()
        Regex("""["']?(\w+)["']?\s*:\s*(?:"([^"]*)"|'([^']*)'|(\d+))""").findAll(body).forEach {
            val key = it.groupValues[1]
            val value = it.groupValues[2].ifEmpty { it.groupValues[3].ifEmpty { it.groupValues[4] } }
            out[key] = value
        }
        return out.takeIf { it.isNotEmpty() }
    }

    private fun extractPostId(html: String, doc: Document? = null): String {
        doc?.selectFirst("li.dooplay_player_option[data-post]")?.attr("data-post")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        doc?.selectFirst("body")?.classNames()
            ?.firstOrNull { it.startsWith("postid-") }?.removePrefix("postid-")
            ?.takeIf { it.isNotBlank() }?.let { return it }

        val patterns = listOf(
            Regex("""<body[^>]*class="[^"]*postid-(\d+)"""),
            Regex("""data-post=["'](\d+)["']"""),
            Regex(""""post_id"\s*:\s*"?(\d+)"?"""),
            Regex("""postid-(\d+)"""),
            Regex("""id="post-(\d+)""""),
        )
        for (p in patterns) p.find(html)?.let { return it.groupValues[1] }
        return ""
    }

    private fun decodeJwPlayerUrl(embedUrl: String): String {
        if (!embedUrl.contains("jwplayer/?source=")) {
            return if (embedUrl.startsWith("http") &&
                (embedUrl.contains(".mp4") || embedUrl.contains(".m3u8") || embedUrl.contains(".mkv"))
            ) embedUrl else ""
        }
        val sourceParam = embedUrl.substringAfter("source=").substringBefore("&")
        return try { URLDecoder.decode(sourceParam, "UTF-8") } catch (_: Exception) { sourceParam }
    }

    private fun parseEpisodeList(doc: Document): List<Episode> {
        val out = mutableListOf<Episode>()
        doc.select("ul.episodios li, div.episodios li, #episodes li").forEach { li ->
            val a = li.selectFirst("a[href]") ?: return@forEach
            val href = fixUrl(a.attr("href"))
            if (!href.startsWith("http")) return@forEach
            val numerando = li.selectFirst("div.numerando")?.text()?.trim().orEmpty()
            val m = Regex("""(\d+)\s*-\s*(\d+)""").find(numerando)
            val s = m?.groupValues?.get(1)?.toIntOrNull()
            val e = m?.groupValues?.get(2)?.toIntOrNull()
            val epName = li.selectFirst(".episodiotitle a")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: a.text().trim().ifEmpty { "Episode ${e ?: out.size + 1}" }
            val thumb = li.selectFirst("img")?.let { imgSrc(it) }
            out.add(newEpisode("$href|page") {
                this.name = epName
                this.season = s
                this.episode = e ?: (out.size + 1)
                this.posterUrl = thumb
            })
        }
        return out
    }

    private fun diagnoseTvPage(html: String, doc: Document) {
        dbg("DIAG", "ul.episodios=${doc.select("ul.episodios").size} " +
                "playerOptions=${doc.select("li.dooplay_player_option").size} " +
                "iframes=${doc.select("iframe").size} " +
                "media=${findMediaUrls(html)}")
    }

    private fun imgSrc(img: Element): String? =
        img.attr("data-src").ifBlank { img.attr("data-lazy-src") }.ifBlank { img.attr("src") }
            .trim().takeIf { it.isNotBlank() }?.let { fixUrl(it) }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: return null
        if (!href.contains("/movies/") && !href.contains("/tvshows/")) return null

        val title = selectFirst("h3.title, h3, div.title, .data h3, .title")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: return null

        val poster = selectFirst("img")?.let { imgSrc(it) }

        return if (href.contains("/tvshows/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
        }
    }

    private fun extractImdbRating(doc: Document): Double? {
        doc.select("div.custom_fields").forEach { field ->
            val label = field.selectFirst("b.variante")?.text()?.lowercase() ?: ""
            if (label.contains("imdb")) {
                field.selectFirst("span.valor")?.text()?.trim()?.toDoubleOrNull()?.let { return it }
            }
        }
        return null
    }

    private suspend fun fetchHtml(url: String): String? {
        return try {
            val res = app.get(url, headers = headers())
            dbg("NET", "GET ${res.code} len=${res.text.length} $url")
            res.text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err("NET", "fetchHtml failed: $url", e)
            null
        }
    }

    private suspend fun fetchDocument(url: String): Document =
        Jsoup.parse(fetchHtml(url) ?: "", url)
}
