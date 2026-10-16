// KhflixProvider.kt
package com.Khflix

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Khflix provider with verbose diagnostics.
 *
 * LOGGING
 *   All diagnostic lines use Log.e (error level) on purpose so they survive
 *   "errors only" logcat filters. Every line starts with a stage marker:
 *
 *     [HOME] [SEARCH] [LOAD] [LINKS] [MOVIE] [TV] [PROBE] [EMIT] [SUB] [NET]
 *
 *   View them with:   adb logcat Khflix:V *:S
 *   Set DEBUG = false below to silence everything except real errors.
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

        private const val PLAYER_API = "https://khflix.com/wp-json/dooplayer/v2/"
        private const val AJAX_URL = "https://khflix.com/wp-admin/admin-ajax.php"
        private const val CDN_VIDEO_HOST = "cdn1.khflixcdnvideos.top"
        private const val SUB_HOST = "subtitle.khflix.top"

        // Hosts we trust when scanning raw HTML for direct media links
        private val MEDIA_HOSTS = listOf(
            "hls.khflix.top", "videos.khflix.top", "khflixcdnvideos.top"
        )

        private val SUBTITLE_LANGS = listOf(
            "Khmer", "Arabic", "Filipino", "Hindi",
            "Indonesian", "Thai", "Vietnamese", "Malay"
        )

        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Safari/537.36"
    }

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
        dbg("HOME", "${request.name} page=$page url=$url cards=${items.size}")
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    // ==================================================================
    // SEARCH
    // ==================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val q = URLEncoder.encode(query, "UTF-8")
        val doc = fetchDocument("$mainUrl/?s=$q")
        val out = mutableListOf<SearchResponse>()

        doc.select("div.result-item").forEach { el ->
            el.toSearchResult()?.let { out.add(it) }
        }
        if (out.isEmpty()) {
            doc.select("article.item").forEach { el ->
                el.toSearchResult()?.let { out.add(it) }
            }
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

        val poster = (doc.selectFirst("div.poster img")?.let {
            it.attr("data-src").ifBlank { it.attr("data-lazy-src") }.ifBlank { it.attr("src") }
        })?.trim()?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

        val ogImages = doc.select("meta[property='og:image']")
            .mapNotNull { it.attr("content").trim().takeIf { c -> c.isNotBlank() } }
        val backdrop = ogImages.getOrNull(1) ?: ogImages.lastOrNull() ?: poster

        val year = Regex("""(\d{4})""")
            .find(doc.selectFirst("span.date")?.text() ?: "")?.value?.toIntOrNull()

        val genres = doc.select("div.sgeneros a")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        val imdbRating: Double? = extractImdbRating(doc)
        val score: Score? = imdbRating?.let { Score.from10(it) }

        val plot = doc.selectFirst("div#info .wp-content p")?.text()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        val isTv = url.contains("/tvshows/")
        val isAsian = genres.any {
            it.contains("k-drama", true) || it.contains("c-drama", true) ||
                    it.contains("j-drama", true)
        }

        val cfg = extractKhflixConfig(html)
        val postId = extractPostId(html, doc)
        dbg("LOAD", "url=$url isTv=$isTv title='$title' postId='$postId' cfg=$cfg " +
                "htmlLen=${html.length} year=$year genres=$genres")

        return if (isTv) {
            val seriesPrefix = cfg?.get("series")
            val episodeCount = cfg?.get("episodes")?.toIntOrNull() ?: 0
            val episodes = mutableListOf<Episode>()

            if (seriesPrefix != null && episodeCount > 0) {
                for (n in 1..episodeCount) {
                    val padded = n.toString().padStart(2, '0')
                    // data = prefix|tv|episode|pageUrl
                    val data = "$seriesPrefix|tv|$padded|$url"
                    episodes.add(newEpisode(data) {
                        this.name = "Episode $n"
                        this.episode = n
                        this.season = 1
                    })
                }
            } else {
                err("LOAD", "TV page but khflixConfig missing/empty: prefix=$seriesPrefix count=$episodeCount")
            }
            dbg("LOAD", "TV '$title' episodes=${episodes.size} prefix=$seriesPrefix")

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
            if (postId.isBlank()) {
                err("LOAD", "movie postId is BLANK for $url (loadLinks will use page fallback)")
            }
            // data = postId|movie|pageUrl
            newMovieLoadResponse(title, url, TvType.Movie, "$postId|movie|$url") {
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
        val parts = data.split("|", limit = 4)
        if (parts.size < 2) {
            err("LINKS", "bad data format, parts=${parts.size}")
            return false
        }

        val emitted = mutableSetOf<String>()
        var count = 0

        // Collect sources first, emit them after the type-specific work below
        val directUrls = mutableListOf<Pair<String, String>>() // label to url
        val embedUrls = mutableListOf<String>()

        when (parts[1]) {
            // ----------------------------------------------------------
            "movie" -> {
                val postIdFromData = parts[0]
                val pageUrl = parts.getOrNull(2).orEmpty()
                var postId = postIdFromData
                dbg("MOVIE", "postId='$postId' pageUrl='$pageUrl'")

                // Fetch page once: used for fallbacks and to recover a blank post ID
                var pageHtml = ""
                var pageDoc: Document? = null
                if (pageUrl.isNotBlank()) {
                    pageHtml = fetchHtml(pageUrl).orEmpty()
                    if (pageHtml.isNotBlank()) pageDoc = Jsoup.parse(pageHtml, pageUrl)
                    dbg("MOVIE", "page fetched len=${pageHtml.length}")
                }
                if (postId.isBlank() && pageDoc != null) {
                    postId = extractPostId(pageHtml, pageDoc)
                    dbg("MOVIE", "recovered postId='$postId'")
                }

                // ---- Strategy 1: DooPlay REST API ----
                if (postId.isNotBlank()) {
                    for (nume in 1..5) {
                        val apiUrl = "$PLAYER_API$postId/movie/$nume"
                        try {
                            val res = app.get(apiUrl, headers = headers(pageUrl.ifBlank { "$mainUrl/" }, ajax = true))
                            val body = res.text
                            dbg("MOVIE", "REST nume=$nume code=${res.code} url=$apiUrl body=${body.cut()}")
                            if (!res.isSuccessful) continue
                            val embed = extractEmbedUrl(body)
                            dbg("MOVIE", "REST nume=$nume embed='$embed'")
                            if (embed.isNotBlank()) handleEmbed(embed, directUrls, embedUrls, "rest#$nume")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            err("MOVIE", "REST nume=$nume FAILED url=$apiUrl", e)
                        }
                    }
                } else {
                    err("MOVIE", "skipping REST: postId blank")
                }

                // ---- Strategy 2: admin-ajax player options from page ----
                if (directUrls.isEmpty() && embedUrls.isEmpty() && pageDoc != null) {
                    val options = pageDoc.select("li.dooplay_player_option, li[data-post][data-nume]")
                    dbg("MOVIE", "AJAX fallback: ${options.size} player options in page")
                    for (opt in options) {
                        val post = opt.attr("data-post").ifBlank { postId }
                        val nume = opt.attr("data-nume").ifBlank { "1" }
                        val type = opt.attr("data-type").ifBlank { "movie" }
                        try {
                            val res = app.post(
                                AJAX_URL,
                                headers = headers(pageUrl, ajax = true),
                                data = mapOf(
                                    "action" to "doo_player_ajax",
                                    "post" to post,
                                    "nume" to nume,
                                    "type" to type
                                )
                            )
                            dbg("MOVIE", "AJAX post=$post nume=$nume type=$type code=${res.code} body=${res.text.cut()}")
                            val embed = extractEmbedUrl(res.text)
                            if (embed.isNotBlank()) handleEmbed(embed, directUrls, embedUrls, "ajax#$nume")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            err("MOVIE", "AJAX FAILED post=$post nume=$nume", e)
                        }
                    }
                }

                // ---- Strategy 3: iframes and raw media URLs in page ----
                if (directUrls.isEmpty() && embedUrls.isEmpty() && pageDoc != null) {
                    val iframes = pageDoc.select("iframe[src], iframe[data-src]")
                        .map { it.attr("src").ifBlank { it.attr("data-src") } }
                        .filter { it.isNotBlank() }
                    dbg("MOVIE", "PAGE fallback: iframes=$iframes")
                    iframes.forEach { handleEmbed(fixUrl(it), directUrls, embedUrls, "iframe") }

                    val raw = Regex("""https?:\\?/\\?/[^"'\s<>\\]+?\.(?:m3u8|mp4)[^"'\s<>\\]*""")
                        .findAll(pageHtml.replace("\\/", "/"))
                        .map { it.value }
                        .filter { u -> MEDIA_HOSTS.any { u.contains(it) } }
                        .distinct().toList()
                    dbg("MOVIE", "PAGE fallback: raw media urls=$raw")
                    raw.forEach { directUrls.add("page" to it) }
                }

                if (directUrls.isEmpty() && embedUrls.isEmpty()) {
                    err("MOVIE", "NO SOURCES from any strategy. postId='$postId' pageLen=${pageHtml.length}")
                }
            }

            // ----------------------------------------------------------
            "tv" -> {
                val seriesPrefix = parts[0]
                val padded = parts.getOrNull(2) ?: "01"
                dbg("TV", "prefix='$seriesPrefix' ep=$padded")

                val videoUrl = "https://$CDN_VIDEO_HOST/$seriesPrefix$padded.mp4"
                val code = probe(videoUrl)
                dbg("TV", "video probe code=$code url=$videoUrl")
                if (code !in 200..299 && code != 206) {
                    err("TV", "video probe NOT OK (code=$code); emitting anyway so player shows its own error")
                }
                directUrls.add("tv-cdn" to videoUrl)

                // Subtitles: only emit those that really exist
                val subCandidates = SUBTITLE_LANGS.map { it to "https://$SUB_HOST/$seriesPrefix$padded.$it.vtt" } +
                        ("English" to "https://$SUB_HOST/$seriesPrefix$padded.vtt")
                val seen = mutableSetOf<String>()
                subCandidates.forEach { (language, subUrl) ->
                    val c = probe(subUrl)
                    dbg("SUB", "$language code=$c url=$subUrl")
                    if ((c in 200..299 || c == 206) && seen.add(subUrl)) {
                        subtitleCallback.invoke(newSubtitleFile(language, subUrl))
                    }
                }
                dbg("SUB", "emitted ${seen.size} subtitle(s)")
            }

            else -> err("LINKS", "unknown type '${parts[1]}'")
        }

        // ---- Emit direct links ----
        for ((label, src) in directUrls) {
            if (src.isBlank() || !emitted.add(src)) continue
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

        // ---- Hand embeds to CloudStream extractors ----
        for (embed in embedUrls.distinct()) {
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
    // HELPERS
    // ==================================================================

    /** Sort an embed URL into "direct media" or "needs an extractor". */
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

    /** Pull embed_url out of the DooPlay JSON response and un-escape it. */
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
        // Sometimes embed_url is an <iframe src="..."> snippet
        if (cleaned.contains("<iframe")) {
            return Regex("""src=["']([^"']+)["']""").find(cleaned)?.groupValues?.get(1)?.let { fixUrl(it) } ?: ""
        }
        return cleaned
    }

    /** window.khflixConfig = { series: "abc", episodes: 16 }  (quoted or unquoted keys). */
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

    /** Post ID. Most reliable signals first. */
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
        for (p in patterns) {
            p.find(html)?.let { return it.groupValues[1] }
        }
        return ""
    }

    /** jwplayer wrapper -> real URL; plain media URL -> itself; else "". */
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

    /** HTTP status of a URL (HEAD first, ranged GET as fallback). -1 on failure. */
    private suspend fun probe(url: String): Int {
        return try {
            val h = app.head(url, headers = headers())
            if (h.code in 200..299) {
                h.code
            } else {
                val g = app.get(url, headers = headers() + ("Range" to "bytes=0-0"))
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

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() }?.let { fixUrl(it) } ?: return null
        if (!href.contains("/movies/") && !href.contains("/tvshows/")) return null

        val title = selectFirst("h3.title, h3, div.title, .data h3, .title")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: return null

        val img = selectFirst("img")
        val poster = img?.let {
            it.attr("data-src").ifBlank { it.attr("data-lazy-src") }.ifBlank { it.attr("src") }
        }?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }

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
                field.selectFirst("span.valor")?.text()?.trim()?.toDoubleOrNull()
                    ?.let { return it }
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
            if (!res.isSuccessful) {
                err("NET", "non-2xx ${res.code} for $url body=${res.text.cut(200)}")
            }
            res.text
        } catch (e: CancellationException) {
            throw e // normal when the user leaves the page; not an error
        } catch (e: Exception) {
            err("NET", "fetchHtml failed: $url", e)
            null
        }
    }

    private suspend fun fetchDocument(url: String): Document =
        Jsoup.parse(fetchHtml(url) ?: "", url)
}
