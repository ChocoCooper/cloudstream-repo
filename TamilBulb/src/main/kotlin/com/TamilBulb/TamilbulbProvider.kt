package com.tamilbulb

import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class TamilbulbProvider : MainAPI() {
    override var mainUrl = "https://tamilbulb.cc"
    override var name = "TamilBulb"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                     "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /**
     * In-memory map: detail URL → the exact mediatitle shown on the homepage/search card.
     * Populated during getMainPage() and search(). Read in load() so the load page
     * title matches the card title exactly.
     *
     * ConcurrentHashMap survives concurrent coroutines.
     */
    private val titleCache = ConcurrentHashMap<String, String>()

    // ===================================================================
    // LOGGING HELPER — every log line starts with "TamilBulb:" so you can
    // filter with:  adb logcat -s System.out | grep TamilBulb
    // ===================================================================
    private fun log(tag: String, msg: String) {
        println("TamilBulb: [$tag] $msg")
    }
    private fun logEx(tag: String, e: Throwable) {
        println("TamilBulb: [$tag] EXCEPTION: ${e.javaClass.simpleName}: ${e.message}")
        e.stackTrace.take(6).forEach { println("TamilBulb: [$tag]   at $it") }
    }

    // ===================================================================
    // HOME PAGE
    // ===================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        log("home", "getMainPage(page=$page, request=${request.name})")
        val home = mutableListOf<HomePageList>()
        val categories = listOf(
            "New Movies" to "/video-category/new1movies/",
            "HD Movies"  to "/video-category/movies/",
            "Dubbed"     to "/video-category/dmovie/",
            "Trending"   to "/video-category/trending/",
            "CAM"        to "/video-category/cam/",
        )
        for ((label, path) in categories) {
            val items = fetchCategoryPage(path, page)
            log("home", "  section '$label' → ${items.size} items")
            if (items.isNotEmpty()) {
                home.add(HomePageList(label, items, isHorizontalImages = true))
            }
        }
        log("home", "getMainPage() done → ${home.size} sections total")
        return newHomePageResponse(home, hasNext = true)
    }

    private suspend fun fetchCategoryPage(path: String, page: Int): List<SearchResponse> {
        val url = if (page <= 1) "$mainUrl$path" else "$mainUrl$path/page/$page/"
        log("category", "fetchCategoryPage url=$url")
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
            val cards = doc.select("article.post-item")
            log("category", "  found ${cards.size} article.post-item cards")
            val results = cards.mapNotNull { it.toSearchCard() }
            log("category", "  parsed ${results.size} valid cards")
            results
        } catch (e: Exception) {
            logEx("category", e)
            emptyList()
        }
    }

    // ===================================================================
    // SEARCH — crawls detail pages to replace AVIF thumbnails with the
    // site's real (TMDB) JPEG poster. Also caches titles.
    // ===================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        log("search", "search(query='$query')")
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = try {
            app.get(url, headers = mapOf("User-Agent" to UA)).document
        } catch (e: Exception) {
            logEx("search", e)
            return emptyList()
        }

        val cards = doc.select("article.post-item").mapNotNull { el ->
            val a = el.selectFirst("h3.entry-title a.post-listing-title") ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = (a.attr("title").ifBlank { a.text() }).trim().ifBlank { return@mapNotNull null }
            Triple(title, href, extractCardPoster(el))
        }
        log("search", "found ${cards.size} raw cards")

        return coroutineScope {
            cards.map { (title, href, thumb) ->
                async {
                    val poster = fetchDetailPoster(href) ?: thumb
                    log("search", "  → '$title'  poster=${poster?.take(60)}…")
                    titleCache[href] = title
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }.awaitAll()
        }.also { log("search", "search() done → ${it.size} results, cache=${titleCache.size}") }
    }

    /** Fast path: pick a JPG/WebP from `data-srcset`, avoiding `.avif`. */
    private fun extractCardPoster(el: Element): String? {
        val img = el.selectFirst("img.blog-img") ?: return null
        val srcset = img.attr("data-srcset")
        if (srcset.isNotBlank()) {
            val jpg = Regex("""(https?://\S+?\.(?:jpg|jpeg|png|webp))\s+\d+w""", RegexOption.IGNORE_CASE)
                .find(srcset)?.groupValues?.get(1)
            if (jpg != null) return jpg
        }
        return img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
    }

    /** Crawl the detail page → grab the poster from the TMDB image the site actually uses. */
    private suspend fun fetchDetailPoster(url: String): String? = try {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        doc.selectFirst("main article > header.entry-header .pp-image img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        logEx("poster-crawl", e)
        null
    }

    // ===================================================================
    // LOAD — very detailed logging + title cache lookup
    // ===================================================================
    override suspend fun load(url: String): LoadResponse? {
        log("load", "load(url=$url)")
        val html = try {
            app.get(url, headers = mapOf("User-Agent" to UA)).text
        } catch (e: Exception) {
            logEx("load", e)
            return null
        }
        log("load", "html size=${html.length}")

        val doc = Jsoup.parse(html)

        // ---- Title ----
        // Prefer: cached title from search/home (matches the card exactly)
        // Fallback 1: h1.entry-title inside main .beeteam368-single-meta (WP post title)
        // Fallback 2: h2.entry-title inside the TMDB banner (has colon — TMDB title)
        val cachedTitle = titleCache[url]
        val wpTitle    = doc.selectFirst("main .beeteam368-single-meta header.single-post-title h1.entry-title")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        val tmdbTitle  = doc.selectFirst("main article > header.entry-header h2.entry-title")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        val genericTitle = doc.selectFirst("h1.entry-title, meta[property=og:title]")
            ?.let {
                if (it.tagName() == "meta") it.attr("content").substringBefore(" - ")
                else it.text().trim()
            }?.takeIf { it.isNotBlank() }
        val title = cachedTitle ?: wpTitle ?: tmdbTitle ?: genericTitle
        log("load", "  title: cache=$cachedTitle | wp=$wpTitle | tmdb=$tmdbTitle | generic=$genericTitle")
        log("load", "  → chosen title = $title")
        if (title.isNullOrBlank()) {
            log("load", "  ✗ no title found, aborting")
            return null
        }

        // ---- Poster ----
        val poster = doc.selectFirst("main article > header.entry-header .pp-image img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        log("load", "  poster = $poster")

        // ---- Plot ----
        // XPath: /main[1]/article[1]/div[2] — 2nd <div> child of <article>
        val plot = doc.selectFirst("main article > div:nth-of-type(2)")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        log("load", "  plot = ${plot?.take(120)}…")

        // ---- Release year ----
        val yearText = doc.selectFirst(
            "main article > header.entry-header .pp-content-wrapper > .ft-post-meta .post-footer-item:nth-of-type(2) .item-text"
        )?.text()?.trim()
        val year = Regex("""(19|20)\d{2}""").find(yearText ?: "")?.value?.toIntOrNull()
        log("load", "  year: raw='$yearText' → parsed=$year")

        // ---- Genres ----
        val genres = doc.select(
            "main article > header.entry-header .pp-content-wrapper > .ft-post-meta:nth-of-type(2) .post-footer-item .item-text"
        ).map { it.text().trim() }.filter { it.isNotBlank() }
        log("load", "  genres = $genres")

        // ---- Background ----
        val backgroundUrl = doc.selectFirst("main article > header.entry-header")?.attr("style")
            ?.let { Regex("""url\(["']?([^)"']+)["']?\)""").find(it)?.groupValues?.get(1) }
        log("load", "  background = $backgroundUrl")

        // ---- Fallback genre tags ----
        val fallbackTags = doc.select("a.category-item").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()
        log("load", "  fallback tags = $fallbackTags")

        val response = newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres.ifEmpty { fallbackTags }
            if (backgroundUrl != null) {
                this.backgroundPosterUrl = backgroundUrl
            }
        }
        log("load", "  ✓ LoadResponse built for '$title'")
        return response
    }

    // ===================================================================
    // LOAD LINKS
    // ===================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        log("links", "══════════════════════════════════════════════")
        log("links", "loadLinks() data=$data isCasting=$isCasting")

        // ---- 1. Fetch the detail page ----
        val detailHtml = try {
            app.get(data, headers = mapOf("User-Agent" to UA)).text
        } catch (e: Exception) {
            logEx("links", e)
            return false
        }
        log("links", "detail html size=${detailHtml.length}")

        // ---- 2. Extract stream_id ----
        val streamId = extractStreamId(detailHtml)
        if (streamId == null) {
            log("links", "✗ FAILED to extract stream_id")
            return false
        }
        log("links", "stream_id=$streamId")

        // ---- 3. Build the embed URL ----
        val ts = System.currentTimeMillis() / 1000
        val b64 = Base64.getEncoder().encodeToString("$streamId$ts".toByteArray())
        val embedUrl = "$mainUrl/embed/$b64"
        log("links", "embedUrl=$embedUrl")

        // ---- 4. Fetch the embed page ----
        val embedHtml = try {
            app.get(embedUrl, headers = mapOf("User-Agent" to UA)).text
        } catch (e: Exception) {
            logEx("links", e)
            return false
        }
        log("links", "embed html size=${embedHtml.length}")

        val embedDoc = Jsoup.parse(embedHtml)

        // ---- 4b. Pre-dump all #playerN ids on the page for context ----
        val playerIds = (1..20).mapNotNull { n ->
            embedDoc.selectFirst("#player$n")?.let { n }
        }
        log("links", "detected player IDs: $playerIds")
        if (playerIds.isEmpty()) {
            log("links", "✗ no #playerN elements found — the embed page may be an error/consent page")
            val preview = embedHtml.take(400).replace("\n", " ")
            log("links", "embed preview: $preview…")
            return false
        }

        // ---- 5. Enumerate and extract ----
        var any = false
        for (n in playerIds) {
            val player = embedDoc.selectFirst("#player$n") ?: continue
            val iframeUrl = player.selectFirst(".player-wrapper iframe")
                ?.attr("src")?.takeIf { it.isNotBlank() }
            if (iframeUrl == null) {
                log("links", "  player$n has no iframe src")
                continue
            }
            val playerName = player.selectFirst(".player-name")?.text()?.trim() ?: "Player $n"
            val domain = try { java.net.URI(iframeUrl).host } catch (_: Exception) { "?" }
            log("links", "  player$n [$playerName] domain=$domain → $iframeUrl")

            try {
                when {
                    iframeUrl.contains("tamilgun.space") || iframeUrl.contains("vidhide") -> {
                        log("links", "    ↳ dispatching to TamilgunExtractor")
                        TamilgunExtractor().extract(
                            iframeUrl, embedUrl, playerName, subtitleCallback, callback
                        )
                        any = true
                        log("links", "    ↳ TamilgunExtractor done")
                    }
                    iframeUrl.contains("byseraguci.com") ||
                    iframeUrl.contains("filemoon")      ||
                    iframeUrl.contains("n1mwq.org") -> {
                        log("links", "    ↳ dispatching to N1mwqExtractor")
                        N1mwqExtractor().extract(
                            iframeUrl, embedUrl, playerName, subtitleCallback, callback
                        )
                        any = true
                        log("links", "    ↳ N1mwqExtractor done")
                    }
                    else -> {
                        log("links", "    ↳ dispatching to loadExtractor (generic)")
                        loadExtractor(iframeUrl, embedUrl, subtitleCallback, callback)
                        any = true
                    }
                }
            } catch (e: Exception) {
                logEx("links", e)
            }
        }

        log("links", if (any) "✓ loadLinks() finished — links emitted" else "✗ no links produced")
        log("links", "══════════════════════════════════════════════")
        return any
    }

    /**
     * Extract stream_id from the detail HTML.
     * Strategies (in order):
     *   1. Normalize HTML entities then run 3 regexes
     *   2. Jsoup-parse the whole page and read any <script> that mentions beeteam368
     *   3. Regex the raw bytes as a last resort
     */
    private fun extractStreamId(html: String): String? {
        val normalized = html
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("\\\"", "\"")

        // Strategy 1 — raw `index.php?id=`
        Regex("""index\.php\?id=([A-Za-z0-9_\-]+)""")
            .find(normalized)?.groupValues?.get(1)?.let {
                log("sid", "strategy 1 (raw index.php?id=) hit: $it")
                return it
            }

        // Strategy 2 — JSON config regex
        Regex("""beeteam368_pro_player\(\{.*?"video_url":"[^"]*?[?&]id=([A-Za-z0-9_\-]+)""",
              RegexOption.DOT_MATCHES_ALL)
            .find(normalized)?.groupValues?.get(1)?.let {
                log("sid", "strategy 2 (JSON config) hit: $it")
                return it
            }

        // Strategy 3 — streambulb.site URL
        Regex("""streambulb\.site[^"'\s]*?[?&]id=([A-Za-z0-9_\-]+)""")
            .find(normalized)?.groupValues?.get(1)?.let {
                log("sid", "strategy 3 (streambulb URL) hit: $it")
                return it
            }

        // Strategy 4 — Jsoup + script scan
        try {
            val doc = Jsoup.parse(html)
            for (script in doc.select("script")) {
                val body = script.data().ifBlank { script.html() }
                if (body.contains("beeteam368_pro_player") || body.contains("index.php")) {
                    Regex("""index\.php\?id=([A-Za-z0-9_\-]+)""")
                        .find(body)?.groupValues?.get(1)?.let {
                            log("sid", "strategy 4 (Jsoup script scan) hit: $it")
                            return it
                        }
                }
            }
        } catch (e: Exception) {
            logEx("sid", e)
        }

        // Debug dump
        val idx = normalized.indexOf("beeteam368_pro_player")
        if (idx >= 0) {
            val end = minOf(normalized.length, idx + 800)
            log("sid", "DEBUG beeteam368 block: ${normalized.substring(maxOf(0, idx - 40), end)}")
        } else {
            log("sid", "DEBUG no beeteam368_pro_player in HTML — dumping any 'iframe' lines:")
            Regex("""<iframe[^>]{0,200}""").findAll(normalized).take(3).forEach {
                log("sid", "  ${it.value}")
            }
        }
        return null
    }

    // ===================================================================
    // CARD → SearchResponse
    // ===================================================================
    private fun Element.toSearchCard(): SearchResponse? {
        val a = selectFirst("h3.entry-title a.post-listing-title") ?: run {
            log("card", "no title anchor found on: ${outerHtml().take(120)}")
            return null
        }
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = (a.attr("title").ifBlank { a.text() }).trim().ifBlank { return null }
        val poster = extractCardPoster(this)
        // Populate the cache so load() can restore the exact card title
        titleCache[href] = title
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}
