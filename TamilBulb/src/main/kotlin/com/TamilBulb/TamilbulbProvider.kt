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

    /** In-memory title cache: detail URL → exact card title from list pages. */
    private val titleCache = ConcurrentHashMap<String, String>()

    // ============ LOGGING — tag "TamilBulb" (visible in logcat as TamilBulb:V) ============
    private fun log(tag: String, msg: String) = Log.d("TamilBulb", "[$tag] $msg")
    private fun logEx(tag: String, e: Throwable) {
        Log.e("TamilBulb", "[$tag] EXCEPTION: ${e.javaClass.simpleName}: ${e.message}", e)
    }

    // ===================================================================
    // HOME
    // ===================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        log("home", "getMainPage(page=$page, name='${request.name}')")
        val home = mutableListOf<HomePageList>()
        listOf(
            "New Movies" to "/video-category/new1movies/",
            "HD Movies"  to "/video-category/movies/",
            "Dubbed"     to "/video-category/dmovie/",
            "Trending"   to "/video-category/trending/",
            "CAM"        to "/video-category/cam/",
        ).forEach { (label, path) ->
            val items = fetchCategoryPage(path, page)
            log("home", "  section '$label' → ${items.size} items")
            if (items.isNotEmpty()) home.add(HomePageList(label, items, isHorizontalImages = true))
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
            logEx("search", e); return emptyList()
        }

        val cards = doc.select("article.post-item").mapNotNull { el ->
            val a = el.selectFirst("h3.entry-title a.post-listing-title") ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = (a.attr("title").ifBlank { a.text() }).trim().ifBlank { return@mapNotNull null }
            Triple(title, href, extractCardPoster(el))
        }
        log("search", "found ${cards.size} raw cards")

        val out = coroutineScope {
            cards.map { (title, href, thumb) ->
                async {
                    val poster = fetchDetailPoster(href) ?: thumb
                    titleCache[href] = title
                    log("search", "  → '$title'")
                    newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
                }
            }.awaitAll()
        }
        log("search", "search done → ${out.size} results (cache=${titleCache.size})")
        return out
    }

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

    private suspend fun fetchDetailPoster(url: String): String? = try {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        doc.selectFirst("main article > header.entry-header .pp-image img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) { logEx("poster", e); null }

    // ===================================================================
    // LOAD
    // ===================================================================
    override suspend fun load(url: String): LoadResponse? {
        log("load", "load($url)")
        val html = try {
            val resp = app.get(url, headers = mapOf("User-Agent" to UA))
            log("load", "GET → HTTP ${resp.code} (${resp.text.length} bytes)")
            resp.text
        } catch (e: Exception) { logEx("load", e); return null }

        val doc = Jsoup.parse(html)

        val cachedTitle = titleCache[url]
        val wpTitle = doc.selectFirst("main .beeteam368-single-meta header.single-post-title h1.entry-title")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        val tmdbTitle = doc.selectFirst("main article > header.entry-header h2.entry-title")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        val genericTitle = doc.selectFirst("h1.entry-title")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        val title = cachedTitle ?: wpTitle ?: tmdbTitle ?: genericTitle
        log("load", "  title: cache=$cachedTitle wp=$wpTitle tmdb=$tmdbTitle")
        if (title.isNullOrBlank()) { log("load", "✗ no title"); return null }

        val poster = doc.selectFirst("main article > header.entry-header .pp-image img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("main article > div:nth-of-type(2)")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
        val yearText = doc.selectFirst(
            "main article > header.entry-header .pp-content-wrapper > .ft-post-meta .post-footer-item:nth-of-type(2) .item-text"
        )?.text()?.trim()
        val year = Regex("""(19|20)\d{2}""").find(yearText ?: "")?.value?.toIntOrNull()
        val genres = doc.select(
            "main article > header.entry-header .pp-content-wrapper > .ft-post-meta:nth-of-type(2) .post-footer-item .item-text"
        ).map { it.text().trim() }.filter { it.isNotBlank() }
        val backgroundUrl = doc.selectFirst("main article > header.entry-header")?.attr("style")
            ?.let { Regex("""url\(["']?([^)"']+)["']?\)""").find(it)?.groupValues?.get(1) }
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

        // 1. Fetch detail page
        val detailHtml = try {
            val resp = app.get(data, headers = mapOf("User-Agent" to UA))
            log("links", "detail page: HTTP ${resp.code} (${resp.text.length} bytes)")
            resp.text
        } catch (e: Exception) { logEx("links", e); return false }

        // 2. Extract stream_id
        val streamId = extractStreamId(detailHtml)
        if (streamId == null) {
            log("links", "✗ FAILED to extract stream_id")
            return false
        }
        log("links", "stream_id=$streamId")

        // 3. Build embed URL
        val ts = System.currentTimeMillis() / 1000
        val urlSafe = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$streamId$ts".toByteArray())
        val embedUrl = "$mainUrl/embed/$urlSafe"
        log("links", "embedUrl=$embedUrl")

        // 4. Fetch embed page
        val embedHtml = try {
            val resp = app.get(embedUrl, headers = mapOf(
                "User-Agent" to UA, "Referer" to data
            ))
            log("links", "embed page: HTTP ${resp.code} (${resp.text.length} bytes)")
            resp.text
        } catch (e: Exception) { logEx("links", e); return false }

        val embedDoc = Jsoup.parse(embedHtml)
        val playerIds = (1..20).filter { embedDoc.selectFirst("#player$it") != null }
        log("links", "players detected: $playerIds")
        if (playerIds.isEmpty()) {
            log("links", "✗ no #playerN elements")
            return false
        }

        // 5. Enumerate players — track success per player via a wrapped callback
        for (n in playerIds) {
            val player = embedDoc.selectFirst("#player$n") ?: continue
            val iframeUrl = player.selectFirst(".player-wrapper iframe")?.attr("src")
                ?.takeIf { it.isNotBlank() } ?: continue
            val playerName = player.selectFirst(".player-name")?.text()?.trim() ?: "Player $n"
            log("links", "  player$n [$playerName] → $iframeUrl")

            // Wrap the callback so we know if a link was actually emitted
            var emittedForThisPlayer = false
            val trackCallback: (ExtractorLink) -> Unit = { link ->
                emittedForThisPlayer = true
                callback.invoke(link)
            }

            try {
                when {
                    iframeUrl.contains("tamilgun.space") || iframeUrl.contains("vidhide") -> {
                        log("links", "    ↳ TamilgunExtractor")
                        TamilgunExtractor().extract(
                            iframeUrl, embedUrl, playerName, subtitleCallback, trackCallback
                        )
                    }
                    iframeUrl.contains("byseraguci.com") ||
                    iframeUrl.contains("filemoon")      ||
                    iframeUrl.contains("n1mwq.org") -> {
                        log("links", "    ↳ N1mwqExtractor")
                        N1mwqExtractor().extract(
                            iframeUrl, embedUrl, playerName, subtitleCallback, trackCallback
                        )
                    }
                    else -> {
                        log("links", "    ↳ loadExtractor (generic) — may not support this host")
                        loadExtractor(iframeUrl, embedUrl, subtitleCallback, trackCallback)
                    }
                }
            } catch (e: Exception) {
                logEx("links", e)
            }

            if (emittedForThisPlayer) {
                log("links", "    ✓ player$n emitted a link")
                any = true
            } else {
                log("links", "    ✗ player$n produced no link")
            }
        }

        log("links", if (any) "✓ loadLinks finished — ${if (any) "links emitted" else "no links"}"
            else "✗ no links produced")
        log("links", "════════════════════════════════════════════")
        return any
    }

    // ===================================================================
    // STREAM_ID EXTRACTION
    // ===================================================================
    private fun extractStreamId(html: String): String? {
        val normalized = html
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("\\\"", "\"")

        Regex("""index\.php\?id=([A-Za-z0-9_\-]+)""")
            .find(normalized)?.groupValues?.get(1)?.let {
                log("sid", "hit strategy 1 (index.php?id=): $it"); return it
            }

        Regex("""beeteam368_pro_player\(\{.*?"video_url":"[^"]*?[?&]id=([A-Za-z0-9_\-]+)""",
              RegexOption.DOT_MATCHES_ALL)
            .find(normalized)?.groupValues?.get(1)?.let {
                log("sid", "hit strategy 2 (JSON config): $it"); return it
            }

        Regex("""streambulb\.site[^"'\s]*?[?&]id=([A-Za-z0-9_\-]+)""")
            .find(normalized)?.groupValues?.get(1)?.let {
                log("sid", "hit strategy 3 (streambulb URL): $it"); return it
            }

        try {
            val doc = Jsoup.parse(html)
            for (script in doc.select("script")) {
                val body = script.data().ifBlank { script.html() }
                if (body.contains("beeteam368_pro_player") || body.contains("index.php")) {
                    Regex("""index\.php\?id=([A-Za-z0-9_\-]+)""")
                        .find(body)?.groupValues?.get(1)?.let {
                            log("sid", "hit strategy 4 (Jsoup script): $it"); return it
                        }
                }
            }
        } catch (e: Exception) { logEx("sid", e) }

        // Debug dump
        val idx = normalized.indexOf("beeteam368_pro_player")
        if (idx >= 0) {
            val end = minOf(normalized.length, idx + 800)
            log("sid", "DEBUG block: ${normalized.substring(maxOf(0, idx - 40), end)}")
        } else {
            log("sid", "DEBUG no beeteam368_pro_player — scanning for iframe:")
            Regex("""<iframe[^>]{0,200}""").findAll(normalized).take(3).forEach {
                log("sid", "  ${it.value}")
            }
        }
        return null
    }

    // ===================================================================
    // CARD
    // ===================================================================
    private fun Element.toSearchCard(): SearchResponse? {
        val a = selectFirst("h3.entry-title a.post-listing-title") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = (a.attr("title").ifBlank { a.text() }).trim().ifBlank { return null }
        val poster = extractCardPoster(this)
        titleCache[href] = title
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }
}
