package com.tamilbulb

import android.util.Log
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
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class TamilbulbProvider : MainAPI() {

    override var mainUrl = "https://tamilbulb.cc"
    override var name = "TamilBulb"
    override val hasMainPage = true
    override var lang = "ta"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                     "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** In-memory title cache: detail URL → exact card title shown on home / search. */
    private val titleCache = ConcurrentHashMap<String, String>()

    // ===================================================================
    // LOGGING
    // ===================================================================
    private fun log(tag: String, msg: String) = Log.d("TamilBulb", "[$tag] $msg")

    private fun logEx(tag: String, e: Throwable) {
        Log.e("TamilBulb", "[$tag] EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
    }

    // ===================================================================
    // HOME PAGE
    // ===================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        log("home", "getMainPage(page=$page, name='${request.name}')")
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

        log("home", "getMainPage done → ${home.size} sections")
        return newHomePageResponse(home, hasNext = true)
    }

    private suspend fun fetchCategoryPage(path: String, page: Int): List<SearchResponse> {
        val url = if (page <= 1) "$mainUrl$path" else "$mainUrl$path/page/$page/"
        return try {
            val resp = app.get(url, headers = mapOf("User-Agent" to UA))
            log("category", "GET $url → HTTP ${resp.code} (${resp.text.length} bytes)")
            val cards = resp.document.select("article.post-item")
            log("category", "  ${cards.size} cards found")
            cards.mapNotNull { it.toSearchCard() }
        } catch (e: Exception) {
            logEx("category", e)
            emptyList()
        }
    }

    // ===================================================================
    // SEARCH
    // ===================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        log("search", "search('$query')")
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"

        val doc = try {
            val resp = app.get(url, headers = mapOf("User-Agent" to UA))
            log("search", "GET $url → HTTP ${resp.code} (${resp.text.length} bytes)")
            resp.document
        } catch (e: Exception) {
            logEx("search", e)
            return emptyList()
        }

        // First pass — read cards synchronously
        val cards = doc.select("article.post-item").mapNotNull { el ->
            val a = el.selectFirst("h3.entry-title a.post-listing-title") ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = (a.attr("title").ifBlank { a.text() }).trim()
                .ifBlank { return@mapNotNull null }
            Triple(title, href, extractCardPoster(el))
        }
        log("search", "found ${cards.size} raw cards")

        // Second pass — crawl each detail page for the TMDB JPEG poster
        val out = coroutineScope {
            cards.map { (title, href, thumb) ->
                async {
                    val poster = fetchDetailPoster(href) ?: thumb
                    titleCache[href] = title
                    log("search", "  → '$title'")
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }.awaitAll()
        }

        log("search", "search done → ${out.size} results (cache=${titleCache.size})")
        return out
    }

    /** Prefer JPG/WebP from `data-srcset` to avoid .avif images that break older Android devices. */
    private fun extractCardPoster(el: Element): String? {
        val img = el.selectFirst("img.blog-img") ?: return null

        val srcset = img.attr("data-srcset")
        if (srcset.isNotBlank()) {
            val jpg = Regex("""(https?://\S+?\.(?:jpg|jpeg|png|webp))\s+\d+w""",
                            RegexOption.IGNORE_CASE)
                .find(srcset)?.groupValues?.get(1)
            if (jpg != null) return jpg
        }

        return img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
    }

    /** Crawl the detail page for the real TMDB poster (JPEG, always renders). */
    private suspend fun fetchDetailPoster(url: String): String? = try {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        doc.selectFirst("main article > header.entry-header .pp-image img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        logEx("poster", e)
        null
    }

    // ===================================================================
    // LOAD
    // ===================================================================
    override suspend fun load(url: String): LoadResponse? {
        log("load", "load($url)")

        val html = try {
            val resp = app.get(url, headers = mapOf("User-Agent" to UA))
            log("load", "GET → HTTP ${resp.code} (${resp.text.length} bytes)")
            resp.text
        } catch (e: Exception) {
            logEx("load", e)
            return null
        }

        val doc = Jsoup.parse(html)

        // ---- Title (prefer cached card title so it matches exactly) ----
        val cachedTitle  = titleCache[url]
        val wpTitle      = doc.selectFirst("main .beeteam368-single-meta header.single-post-title h1.entry-title")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        val tmdbTitle    = doc.selectFirst("main article > header.entry-header h2.entry-title")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        val genericTitle = doc.selectFirst("h1.entry-title")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }

        val title = cachedTitle ?: wpTitle ?: tmdbTitle ?: genericTitle
        log("load", "  title: cache=$cachedTitle wp=$wpTitle tmdb=$tmdbTitle")
        if (title.isNullOrBlank()) {
            log("load", "✗ no title found")
            return null
        }

        // ---- Poster ----
        val poster = doc.selectFirst("main article > header.entry-header .pp-image img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        // ---- Plot ----
        val plot = doc.selectFirst("main article > div:nth-of-type(2)")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }

        // ---- Year ----
        val yearText = doc.selectFirst(
            "main article > header.entry-header .pp-content-wrapper > .ft-post-meta " +
            ".post-footer-item:nth-of-type(2) .item-text"
        )?.text()?.trim()
        val year = Regex("""(19|20)\d{2}""").find(yearText ?: "")?.value?.toIntOrNull()

        // ---- Genres ----
        val genres = doc.select(
            "main article > header.entry-header .pp-content-wrapper > .ft-post-meta:nth-of-type(2) " +
            ".post-footer-item .item-text"
        ).map { it.text().trim() }.filter { it.isNotBlank() }

        // ---- Background ----
        val backgroundUrl = doc.selectFirst("main article > header.entry-header")?.attr("style")
            ?.let { Regex("""url\(["']?([^)"']+)["']?\)""").find(it)?.groupValues?.get(1) }

        // ---- Fallback tags ----
        val fallbackTags = doc.select("a.category-item").map { it.text().trim() }
            .filter { it.isNotBlank() }.distinct()

        log("load", "  poster=${poster?.take(60)}… year=$year genres=$genres")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres.ifEmpty { fallbackTags }
            if (backgroundUrl != null) this.backgroundPosterUrl = backgroundUrl
        }
    }

    // ===================================================================
    // PLAYER SOURCE — how the beeteam368 config points at the player
    // ===================================================================
    private sealed class PlayerSource {
        /** Has `streambulb.site/index.php?id=XXX` → we build /embed/base64(id+ts). */
        data class Wrapped(val streamId: String) : PlayerSource()
        /** Has `<iframe src="URL">` → we fetch that URL directly. */
        data class Direct(val url: String) : PlayerSource()
    }

    private fun extractPlayerSource(html: String): PlayerSource? {
        val normalized = html
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("\\\"", "\"")

        // Strategy 1 — streambulb/index.php?id=XXX
        Regex("""index\.php\?id=([A-Za-z0-9_\-]+)""")
            .find(normalized)?.groupValues?.get(1)?.let {
                log("sid", "wrapped stream_id=$it")
                return PlayerSource.Wrapped(it)
            }

        // Strategy 2 — <iframe src="URL"> inside the config
        Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            .find(normalized)?.groupValues?.get(1)?.let { raw ->
                val url = raw.replace("\\/", "/").trim()
                if (url.startsWith("http")) {
                    log("sid", "direct player URL=$url")
                    return PlayerSource.Direct(url)
                }
            }

        // Strategy 3 — bare URL right after video_url
        Regex(""""video_url"\s*:\s*"(https?:[^"\\]+)""")
            .find(normalized)?.groupValues?.get(1)?.let { raw ->
                val url = raw.replace("\\/", "/")
                if (url.startsWith("http") && !url.startsWith("<")) {
                    log("sid", "bare video_url=$url")
                    return PlayerSource.Direct(url)
                }
            }

        val idx = normalized.indexOf("beeteam368_pro_player")
        if (idx >= 0) {
            log("sid", "DEBUG block: ${normalized.substring(maxOf(0, idx - 40), minOf(normalized.length, idx + 600))}")
        } else {
            log("sid", "DEBUG: no beeteam368_pro_player in HTML")
        }
        return null
    }

    private fun extractOrigin(url: String): String? = try {
        val u = URI(url)
        "${u.scheme}://${u.host}"
    } catch (_: Exception) { null }

    // ===================================================================
    // LOAD LINKS
    // ===================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        log("links", "════════ loadLinks() data=$data")
        var any = false

        // ---- 1. Fetch detail page ----
        val detailHtml = try {
            val resp = app.get(data, headers = mapOf("User-Agent" to UA))
            log("links", "detail page: HTTP ${resp.code} (${resp.text.length} bytes)")
            resp.text
        } catch (e: Exception) {
            logEx("links", e); return false
        }

        // ---- 2. Extract player source ----
        val source = extractPlayerSource(detailHtml)
        if (source == null) {
            log("links", "✗ no player source")
            return false
        }

        // ---- 3. Determine which pages hold the actual #playerN divs ----
        val playerPages = mutableListOf<String>()
        when (source) {
            is PlayerSource.Wrapped -> {
                val ts = System.currentTimeMillis() / 1000
                val b64 = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString("${source.streamId}$ts".toByteArray())
                val embedUrl = "$mainUrl/embed/$b64"
                log("links", "wrapped → embedUrl=$embedUrl")
                playerPages.add(embedUrl)
            }
            is PlayerSource.Direct -> {
                log("links", "direct player → ${source.url}")
                playerPages.add(source.url)
            }
        }

        // ---- 4. Walk each player page ----
        for (playerPage in playerPages) {
            val html = try {
                val resp = app.get(playerPage, headers = mapOf(
                    "User-Agent" to UA,
                    "Referer"    to data
                ))
                log("links", "page $playerPage → HTTP ${resp.code} (${resp.text.length} bytes)")
                resp.text
            } catch (e: Exception) {
                logEx("links", e); continue
            }

            val doc = Jsoup.parse(html)
            val playerIds = (1..20).filter { doc.selectFirst("#player$it") != null }
            log("links", "players on page: $playerIds")

            // No #playerN divs → the page itself is the player
            if (playerIds.isEmpty()) {
                log("links", "no #playerN — treating page itself as player")
                val origin = extractOrigin(playerPage)
                var emitted = false
                val track: (ExtractorLink) -> Unit = { emitted = true; callback.invoke(it) }

                try {
                    val tg = TamilgunExtractor().apply { if (origin != null) mainUrl = origin }
                    tg.extract(playerPage, data, "Direct", subtitleCallback, track)
                } catch (e: Exception) { logEx("links", e) }

                if (!emitted) {
                    try { loadExtractor(playerPage, data, subtitleCallback, track) }
                    catch (e: Exception) { logEx("links", e) }
                }

                if (emitted) { any = true; log("links", "  ✓ direct page emitted") }
                else log("links", "  ✗ direct page produced nothing")
                continue
            }

            // Standard #playerN flow
            for (n in playerIds) {
                val player = doc.selectFirst("#player$n") ?: continue
                val iframeUrl = player.selectFirst(".player-wrapper iframe")?.attr("src")
                    ?.takeIf { it.isNotBlank() } ?: continue
                val playerName = player.selectFirst(".player-name")?.text()?.trim() ?: "Player $n"
                log("links", "  player$n [$playerName] → $iframeUrl")

                var emitted = false
                val track: (ExtractorLink) -> Unit = { emitted = true; callback.invoke(it) }

                try {
                    when {
                        // VidHide / tamilgun.space
                        iframeUrl.contains("tamilgun.space") ||
                        iframeUrl.contains("vidhide") -> {
                            log("links", "    ↳ TamilgunExtractor")
                            TamilgunExtractor().extract(
                                iframeUrl, playerPage, playerName, subtitleCallback, track
                            )
                        }

                        // AES-GCM SPA (Bys-Stream / Filemoon / mirror)
                        iframeUrl.contains("n1mwq.org")    ||
                        iframeUrl.contains("filemoon")     ||
                        iframeUrl.contains("byseraguci") -> {
                            log("links", "    ↳ N1mwqExtractor")
                            N1mwqExtractor().extract(
                                iframeUrl, playerPage, playerName, subtitleCallback, track
                            )
                        }

                        // Unknown host: try PACKER-style, then generic
                        else -> {
                            log("links", "    ↳ trying TamilgunExtractor (PACKER reuse)")
                            val origin = extractOrigin(iframeUrl)
                            val tg = TamilgunExtractor().apply {
                                if (origin != null) mainUrl = origin
                            }
                            tg.extract(iframeUrl, playerPage, playerName, subtitleCallback, track)

                            if (!emitted) {
                                log("links", "    ↳ falling back to loadExtractor")
                                loadExtractor(iframeUrl, playerPage, subtitleCallback, track)
                            }
                        }
                    }
                } catch (e: Exception) {
                    logEx("links", e)
                }

                if (emitted) {
                    any = true
                    log("links", "    ✓ player$n emitted")
                } else {
                    log("links", "    ✗ player$n produced nothing")
                }
            }
        }

        log("links", if (any) "✓ loadLinks finished — links emitted" else "✗ no links produced")
        log("links", "════════════════════════════════════════════")
        return any
    }

    // ===================================================================
    // CARD → SearchResponse
    // ===================================================================
    private fun Element.toSearchCard(): SearchResponse? {
        val a = selectFirst("h3.entry-title a.post-listing-title") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = (a.attr("title").ifBlank { a.text() }).trim().ifBlank { return null }
        val poster = extractCardPoster(this)
        titleCache[href] = title
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}
