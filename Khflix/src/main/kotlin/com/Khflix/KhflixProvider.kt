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
 * DATA STRINGS (episode / movie "data" passed to loadLinks) ALWAYS START WITH
 * THE PAGE URL. CloudStream's newEpisode() runs fixUrl() on the data string and
 * prepends mainUrl to anything that does not start with "http" - that is what
 * produced https://khflix.com/My.Bias... in the old build.
 *
 *   {pageUrl}|movie|{postId}
 *   {pageUrl}|tv|{seriesPrefix}|{episodeNumberPadded}
 *   {pageUrl}|page                      (episode page, resolved by scanning it)
 *
 * LOGGING: Log.e on purpose (survives errors-only filters).
 *   adb logcat Khflix:V *:S
 *   Stages: HOME SEARCH LOAD LINKS MOVIE TV HOST EMBED PROBE SUB EMIT NET DIAG
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

        // Used ONLY if host discovery from the page + its scripts finds nothing.
        // Set both to "" to disable. A "[HOST] USING FALLBACK" line is logged
        // whenever they are used.
        private const val FALLBACK_VIDEO_HOST = "cdn1.khflixcdnvideos.top"
        private const val FALLBACK_SUB_HOST = "subtitle.khflix.top"

        // Hosts that are never media/subtitle CDNs
        private val HOST_BLOCKLIST = listOf(
            "tmdb", "google", "gstatic", "cloudflare", "jsdelivr", "tailwind",
            "unpkg", "ampproject", "w.org", "wordpress", "gravatar", "facebook",
            "twitter", "youtube", "youtu.be", "schema.org", "jquery", "bootstrap",
            "fontawesome", "wp.com", "imdb", "instagram", "tiktok"
        )

        // Scripts we do not bother fetching during host discovery
        private val SCRIPT_SKIP = listOf(
            "wp-includes", "jquery", "google", "cloudflare", "gstatic",
            "wp-content/plugins", "bootstrap", "tailwind", "unpkg", "jsdelivr"
        )
    }

    private val playerApi get() = "$mainUrl/wp-json/dooplayer/v2/"
    private val ajaxUrl get() = "$mainUrl/wp-admin/admin-ajax.php"

    private data class Hosts(val video: String?, val sub: String?)

    @Volatile
    private var hostCache: Hosts? = null

    // ------------------------------------------------------------------
    // Logging helpers
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
    override val mainPage = mainPageOf(
        "" to "Latest",
        "movies" to "Movies",
        "tvshows" to "TV Shows",
        "genre/action" to "Action",
        "genre/comedy" to "Comedy",
        "genre/drama" to "Drama",
        "genre/horror" to "Horror",
        "genre/romance" to "Romance",
        "genre/thriller" to "Thriller",
        "genre/k-drama" to "K-Drama",
        "genre/c-drama" to "C-Drama",
        "genre/j-drama" to "J-Drama",
        "genre/movie-speak-khmer" to "Khmer Dubbed",
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
        dbg("LOAD", "url=$url isTv=$isTv title='$title' postId='$postId' cfg=$cfg year=$year genres=$genres")

        return if (isTv) {
            val episodes = mutableListOf<Episode>()

            // Source 1: window.khflixConfig (series prefix + episode count)
            val seriesPrefix = cfg?.get("series")
            val episodeCount = cfg?.get("episodes")?.toIntOrNull() ?: 0
            if (!seriesPrefix.isNullOrBlank() && episodeCount > 0) {
                val season = Regex("""S(\d+)E""", RegexOption.IGNORE_CASE)
                    .find(seriesPrefix)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                for (n in 1..episodeCount) {
                    val padded = n.toString().padStart(2, '0')
                    // MUST start with the page URL (see class comment)
                    episodes.add(newEpisode("$url|tv|$seriesPrefix|$padded") {
                        this.name = "Episode $n"
                        this.episode = n
                        this.season = season
                    })
                }
                dbg("LOAD", "episodes from khflixConfig: ${episodes.size} prefix=$seriesPrefix")
            }

            // Source 2: standard DooPlay episode list
            if (episodes.isEmpty()) {
                episodes.addAll(parseEpisodeList(doc))
                dbg("LOAD", "episodes from DooPlay list: ${episodes.size}")
            }

            // Source 3: page itself carries media links -> single episode
            if (episodes.isEmpty()) {
                diagnoseTvPage(html, doc)
                if (findMediaUrls(html).isNotEmpty()) {
                    episodes.add(newEpisode("$url|page") { this.name = "Episode 1"; this.episode = 1 })
                    dbg("LOAD", "no episode source; page has media URLs, created single episode")
                } else {
                    err("LOAD", "NO EPISODES FOUND for '$title' (see [DIAG] lines above)")
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
            if (postId.isBlank()) err("LOAD", "movie postId BLANK for $url (loadLinks will retry from page)")
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
        if (!pageUrl.startsWith("http")) {
            err("LINKS", "data does not start with a page URL: '$data'")
            return false
        }

        val direct = mutableListOf<Pair<String, String>>() // label to url
        val embeds = mutableListOf<String>()
        var englishBase: String? = null

        val pageHtml = fetchHtml(pageUrl).orEmpty()
        dbg("LINKS", "page len=${pageHtml.length} kind=$kind")
        val hosts = resolveHosts(pageUrl, pageHtml)

        when (kind) {
            "tv" -> {
                val seriesPrefix = parts.getOrNull(2).orEmpty()
                val padded = parts.getOrNull(3) ?: "01"
                val base = "$seriesPrefix$padded"
                dbg("TV", "prefix='$seriesPrefix' ep=$padded base='$base' videoHost=${hosts.video}")
                englishBase = base

                if (hosts.video.isNullOrBlank()) {
                    err("TV", "no video host discovered or configured")
                } else {
                    val videoUrl = "https://${hosts.video}/$base.mp4"
                    val code = probe(videoUrl)
                    dbg("TV", "video probe code=$code url=$videoUrl")
                    if (code !in 200..299 && code != 206) {
                        err("TV", "video probe NOT OK (code=$code); emitting anyway")
                    }
                    direct.add("tv-cdn" to videoUrl)
                }
            }

            else -> { // "movie" or "page"
                val postIdHint = if (kind == "movie") parts.getOrNull(2).orEmpty() else ""
                resolveFromPage(pageUrl, if (kind == "movie") "movie" else "tv", postIdHint, pageHtml, direct, embeds)
                englishBase = direct.firstOrNull()?.second?.let { baseNameFromMedia(it) }
            }
        }

        // ---- English subtitle only ----
        val subOk = emitEnglishSubtitle(englishBase, pageHtml, hosts.sub, pageUrl, subtitleCallback)
        dbg("SUB", "english subtitle emitted=$subOk base='$englishBase'")

        // ---- Emit direct links (m3u8 first) ----
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

        // ---- Hand non-direct embeds to CloudStream extractors ----
        for (embed in embeds.distinct()) {
            dbg("EMIT", "loadExtractor embed=$embed")
            try {
                val ok = loadExtractor(embed, "$mainUrl/", subtitleCallback) { link ->
                    count++
                    dbg("EMIT", "extractor link ${link.source} ${link.url}")
                    callback.invoke(link)
                }
                dbg("EMIT", "loadExtractor result=$ok for $embed")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err("EMIT", "loadExtractor FAILED for $embed", e)
            }
        }

        dbg("LINKS", "DONE links=$count (returning ${count > 0})")
        return count > 0
    }

    // ==================================================================
    // SOURCE RESOLUTION FOR PAGES (movies and episode pages)
    // ==================================================================
    private suspend fun resolveFromPage(
        pageUrl: String,
        type: String, // "movie" or "tv" (DooPlay player type)
        postIdHint: String,
        html: String,
        direct: MutableList<Pair<String, String>>,
        embeds: MutableList<String>
    ) {
        val doc = if (html.isNotBlank()) Jsoup.parse(html, pageUrl) else null
        var postId = postIdHint
        if (postId.isBlank() && doc != null) postId = extractPostId(html, doc)
        dbg("MOVIE", "resolve type=$type postId='$postId' pageLen=${html.length}")

        // ---- Strategy 1: DooPlay REST API (stops at first empty option) ----
        if (postId.isNotBlank()) {
            for (nume in 1..5) {
                val apiUrl = "$playerApi$postId/$type/$nume"
                try {
                    val res = app.get(apiUrl, headers = headers(pageUrl, ajax = true))
                    dbg("MOVIE", "REST nume=$nume code=${res.code} body=${res.text.cut(200)}")
                    if (!res.isSuccessful) break
                    val embed = extractEmbedUrl(res.text)
                    if (embed.isBlank()) break
                    handleEmbed(embed, direct, embeds, "rest#$nume")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    err("MOVIE", "REST nume=$nume FAILED $apiUrl", e)
                    break
                }
            }
        } else {
            err("MOVIE", "skipping REST: postId blank")
        }

        // ---- Strategy 2: admin-ajax with the page's player options ----
        if (direct.isEmpty() && embeds.isEmpty() && doc != null) {
            val options = doc.select("li.dooplay_player_option, li[data-post][data-nume]")
            dbg("MOVIE", "AJAX fallback: ${options.size} player options")
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
                            "post" to post,
                            "nume" to nume,
                            "type" to optType
                        )
                    )
                    dbg("MOVIE", "AJAX post=$post nume=$nume type=$optType code=${res.code} body=${res.text.cut(200)}")
                    val embed = extractEmbedUrl(res.text)
                    if (embed.isNotBlank()) handleEmbed(embed, direct, embeds, "ajax#$nume")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    err("MOVIE", "AJAX FAILED post=$post nume=$nume", e)
                }
            }
        }

        // ---- Strategy 3: iframes + raw media URLs in the page ----
        if (direct.isEmpty() && embeds.isEmpty() && doc != null) {
            val iframes = doc.select("iframe[src], iframe[data-src]")
                .map { it.attr("src").ifBlank { it.attr("data-src") } }
                .filter { it.isNotBlank() }
            dbg("MOVIE", "PAGE fallback: iframes=$iframes")
            iframes.forEach { handleEmbed(fixUrl(it), direct, embeds, "iframe") }

            val raw = findMediaUrls(html)
            dbg("MOVIE", "PAGE fallback: media urls=$raw")
            raw.forEach { direct.add("page" to it) }
        }

        if (direct.isEmpty() && embeds.isEmpty()) {
            err("MOVIE", "NO SOURCES found. postId='$postId' pageLen=${html.length}")
        }
    }

    // ==================================================================
    // SUBTITLES (English only)
    // ==================================================================
    private suspend fun emitEnglishSubtitle(
        base: String?,
        html: String,
        subHost: String?,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val candidates = mutableListOf<String>()

        // 1) .vtt links that already appear in the page, English ones only
        Regex("""https?://[^"'\s<>\\]+?\.vtt""")
            .findAll(html.replace("\\/", "/")).map { it.value }.distinct()
            .filter { isEnglishVtt(it, base) }
            .forEach { candidates.add(it) }

        // 2) names built from the media base name on the discovered subtitle host
        if (!base.isNullOrBlank() && !subHost.isNullOrBlank()) {
            candidates.add("https://$subHost/$base.vtt")          // default track = English
            candidates.add("https://$subHost/$base.English.vtt")
            candidates.add("https://$subHost/$base.en.vtt")
        }

        dbg("SUB", "english candidates=$candidates")
        for (c in candidates.distinct()) {
            val code = probe(c, referer)
            dbg("SUB", "probe code=$code url=$c")
            if (code in 200..299 || code == 206) {
                subtitleCallback.invoke(newSubtitleFile("English", c))
                return true
            }
        }
        return false
    }

    private fun isEnglishVtt(url: String, base: String?): Boolean {
        val name = url.substringAfterLast('/').removeSuffix(".vtt")
        val lower = name.lowercase()
        return lower.contains("english") || lower.endsWith(".en") ||
                (base != null && name == base)
    }

    // ==================================================================
    // HOST DISCOVERY (no hardcoded CDN hosts)
    // ==================================================================
    private suspend fun resolveHosts(pageUrl: String, html: String): Hosts {
        hostCache?.let { return it }

        val texts = mutableListOf(html.replace("\\/", "/"))
        val doc = Jsoup.parse(html, pageUrl)
        val scripts = doc.select("script[src]").map { it.absUrl("src") }
            .filter { s -> s.isNotBlank() && SCRIPT_SKIP.none { s.contains(it, true) } }
            .distinct().take(8)
        dbg("HOST", "scanning ${scripts.size} script(s): $scripts")
        for (s in scripts) {
            try {
                val body = app.get(s, headers = headers(pageUrl)).text
                texts.add(body.replace("\\/", "/"))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                err("HOST", "script fetch failed $s: ${e.message}")
            }
        }

        val mainHost = try { URI(mainUrl).host.orEmpty().removePrefix("www.") } catch (e: Exception) { "" }
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
        val hosts = found
            .filter { h -> h != mainHost && h != "www.$mainHost" && HOST_BLOCKLIST.none { h.contains(it) } }
            // hosts that share the site's brand name first
            .sortedBy { if (brand.isNotBlank() && it.contains(brand)) 0 else 1 }
        dbg("HOST", "candidate hosts=$hosts")

        var video = hosts.firstOrNull { it.contains("cdn") }
            ?: hosts.firstOrNull { it.contains("video") && !it.contains("hls") }
        var sub = hosts.firstOrNull { it.contains("sub") }

        if (video == null && FALLBACK_VIDEO_HOST.isNotEmpty()) {
            err("HOST", "USING FALLBACK video host $FALLBACK_VIDEO_HOST")
            video = FALLBACK_VIDEO_HOST
        }
        if (sub == null && FALLBACK_SUB_HOST.isNotEmpty()) {
            err("HOST", "USING FALLBACK subtitle host $FALLBACK_SUB_HOST")
            sub = FALLBACK_SUB_HOST
        }

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

    /** "https://host/Some.Movie/index.m3u8" -> "Some.Movie"; ".../x.mp4" -> "x". */
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
        if (decoded.isNotBlank()) {
            dbg("EMBED", "[$label] direct media -> $decoded")
            direct.add(label to decoded)
        } else if (embed.startsWith("http")) {
            dbg("EMBED", "[$label] not direct, sending to extractor -> $embed")
            embeds.add(embed)
        } else {
            dbg("EMBED", "[$label] ignored: $embed")
        }
    }

    private fun extractEmbedUrl(body: String): String {
        val raw = Regex(""""embed_url"\s*:\s*"((?:[^"\\]|\\.)*)"""")
            .find(body)?.groupValues?.get(1) ?: return ""
        val cleaned = raw
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
            .replace("\\\"", "\"")
        if (cleaned == "null") return ""
        if (cleaned.contains("<iframe")) {
            return Regex("""src=["']([^"']+)["']""").find(cleaned)?.groupValues?.get(1)?.let { fixUrl(it) } ?: ""
        }
        return cleaned
    }

    /** window.khflixConfig = { series: "abc", episodes: 16 } (quoted or unquoted keys). */
    private fun extractKhflixConfig(html: String): Map<String, String>? {
        val m = Regex("""khflixConfig\s*=\s*\{(.*?)\}\s*;?""", RegexOption.DOT_MATCHES_ALL).find(html)
            ?: return null
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
            Regex("""rel=["']shortlink["'][^>]*href=["'][^"']*\?p=(\d+)"""),
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
        return try {
            URLDecoder.decode(sourceParam, "UTF-8")
        } catch (e: Exception) {
            sourceParam
        }
    }

    /** HTTP status of a URL (HEAD, then ranged GET). -1 on failure. */
    private suspend fun probe(url: String, referer: String = "$mainUrl/"): Int {
        return try {
            val h = app.head(url, headers = headers(referer))
            if (h.code in 200..299) {
                h.code
            } else {
                val g = app.get(url, headers = headers(referer) + ("Range" to "bytes=0-0"))
                dbg("PROBE", "HEAD=${h.code} GET(range)=${g.code} $url")
                g.code
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            err("PROBE", "failed $url: ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
    }

    /** Standard DooPlay episode list: ul.episodios > li. */
    private fun parseEpisodeList(doc: Document): List<Episode> {
        val out = mutableListOf<Episode>()
        doc.select("ul.episodios li, div.episodios li, #episodes li").forEach { li ->
            val a = li.selectFirst("a[href]") ?: return@forEach
            val href = fixUrl(a.attr("href"))
            if (!href.startsWith("http")) return@forEach

            val numerando = li.selectFirst("div.numerando")?.text()?.trim().orEmpty() // "1 - 3"
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

    /** Logs what a TV page contains when no episode source was recognised. */
    private fun diagnoseTvPage(html: String, doc: Document) {
        val scripts = doc.select("script[src]").map { it.attr("src") }.take(20)
        dbg("DIAG", "script srcs=$scripts")
        val globals = Regex("""window\.(\w+)\s*=""").findAll(html).map { it.groupValues[1] }.distinct().toList()
        dbg("DIAG", "window globals=$globals")
        dbg("DIAG", "ul.episodios=${doc.select("ul.episodios").size} li=${doc.select("ul.episodios li").size} " +
                "player options=${doc.select("li.dooplay_player_option").size} iframes=${doc.select("iframe").size}")
        dbg("DIAG", "media urls in html=${findMediaUrls(html)}")
        Regex(""".{0,80}episode.{0,120}""", RegexOption.IGNORE_CASE).findAll(html).take(5).forEach {
            dbg("DIAG", "episode ctx: ${it.value.replace("\n", " ").cut(220)}")
        }
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

    // ------------------------------------------------------------------
    // Network helpers
    // ------------------------------------------------------------------
    private suspend fun fetchHtml(url: String): String? {
        return try {
            val res = app.get(url, headers = headers())
            dbg("NET", "GET ${res.code} len=${res.text.length} $url")
            if (!res.isSuccessful) err("NET", "non-2xx ${res.code} for $url body=${res.text.cut(200)}")
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
