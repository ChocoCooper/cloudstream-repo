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

class TamilbulbProvider : MainAPI() {
    override var mainUrl = "https://tamilbulb.cc"
    override var name = "TamilBulb"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                     "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // ===================================================================
    // HOME PAGE
    // ===================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
            if (items.isNotEmpty()) {
                // horizontalImages = true → poster is wide (16:9)
                home.add(HomePageList(label, items, horizontalImages = true))
            }
        }
        return newHomePageResponse(home, hasNext = true)
    }

    private suspend fun fetchCategoryPage(path: String, page: Int): List<SearchResponse> {
        val url = if (page <= 1) "$mainUrl$path" else "$mainUrl$path/page/$page/"
        return try {
            val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
            doc.select("article.post-item").mapNotNull { it.toSearchCard() }
        } catch (e: Exception) {
            println("TamilBulb: category fetch failed ($path): ${e.message}")
            emptyList()
        }
    }

    // ===================================================================
    // SEARCH — crawls each detail page to replace AVIF thumbnails with
    // the site's real (TMDB) JPEG poster.
    // ===================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document

        // First pass — extract title, href, and card thumbnail (fast fallback)
        val cards = doc.select("article.post-item").mapNotNull { el ->
            val a = el.selectFirst("h3.entry-title a.post-listing-title") ?: return@mapNotNull null
            val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = (a.attr("title").ifBlank { a.text() }).trim().ifBlank { return@mapNotNull null }
            Triple(title, href, extractCardPoster(el))
        }

        // Second pass — concurrently crawl each detail page for the real poster
        return coroutineScope {
            cards.map { (title, href, thumb) ->
                async {
                    val poster = fetchDetailPoster(href) ?: thumb
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = poster
                    }
                }
            }.awaitAll()
        }
    }

    /** Fast path: pick a JPG/WebP from `data-srcset`, avoiding the `.avif` that breaks older devices. */
    private fun extractCardPoster(el: Element): String? {
        val img = el.selectFirst("img.blog-img") ?: return null
        val srcset = img.attr("data-srcset")
        if (srcset.isNotBlank()) {
            val jpg = Regex("""(https?://\S+?\.(?:jpg|jpeg|png|webp))\s+\d+w""", RegexOption.IGNORE_CASE)
                .find(srcset)?.groupValues?.get(1)
            if (jpg != null) return jpg
        }
        val direct = img.attr("data-src").ifBlank { img.attr("src") }
        return direct.ifBlank { null }
    }

    /** Crawl the detail page → grab the poster from the TMDB image the site actually uses. */
    private suspend fun fetchDetailPoster(url: String): String? = try {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        doc.selectFirst("main article > header.entry-header .pp-image img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    // ===================================================================
    // LOAD — uses the exact XPaths you supplied
    // ===================================================================
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val article = doc.selectFirst("main article") ?: doc.selectFirst("article")
            ?: return null

        // --- Title ---
        val title = article.selectFirst("header .entry-title")?.text()?.trim()
            ?: doc.selectFirst("h1.entry-title")?.text()?.trim()
            ?: return null

        // --- Poster ---
        // XPath: /main[1]/article[1]/header[1]/div[1]/div[1]/img[1] → src
        val poster = article.selectFirst("header.entry-header .pp-image img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:image]")?.attr("content")

        // --- Plot ---
        // XPath: /main[1]/article[1]/div[2]  (the first .cast-variant-items-wrapper)
        val plot = article.children()
            .firstOrNull { it.hasClass("cast-variant-items-wrapper") }
            ?.text()?.trim()?.takeIf { it.isNotBlank() }

        // --- Release year ---
        // XPath: .../header[1]/div[1]/div[2]/div[1]/div[1]/span[2]/span[1]
        // = the 2nd .post-footer-item in the 1st .ft-post-meta
        val yearText = article.selectFirst(
            "header.entry-header .pp-content-wrapper > .ft-post-meta .post-footer-item:nth-of-type(2) .item-text"
        )?.text()
        val year = Regex("""(19|20)\d{2}""").find(yearText ?: "")?.value?.toIntOrNull()

        // --- Genres ---
        // XPath: .../header[1]/div[1]/div[2]/div[2]/div[1]/span/span
        // = the 2nd .ft-post-meta block
        val genres = article.select(
            "header.entry-header .pp-content-wrapper > .ft-post-meta:nth-of-type(2) .post-footer-item .item-text"
        ).map { it.text().trim() }.filter { it.isNotBlank() }

        // --- Background image ---
        // XPath: /main[1]/article[1]/header[1] → style="background-image:url(...)"
        val backgroundUrl = article.selectFirst("header.entry-header")?.attr("style")
            ?.let { Regex("""url\(["']?([^)"']+)["']?\)""").find(it)?.groupValues?.get(1) }

        // Fallback genre tags from the breadcrumb category links
        val fallbackTags = doc.select("a.category-item").map { it.text().trim() }
            .filter { it.isNotBlank() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres.ifEmpty { fallbackTags }
            // backgroundPosterUrl is only used if your SDK version supports it
            if (backgroundUrl != null) {
                this.backgroundPosterUrl = backgroundUrl
            }
        }
    }

    // ===================================================================
    // LOAD LINKS — multiple fallbacks + verbose logging
    // ===================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("TamilBulb: loadLinks() called with data=$data")

        // ---- 1. Fetch the detail page ----
        val detailHtml = try {
            app.get(data, headers = mapOf("User-Agent" to UA)).text
        } catch (e: Exception) {
            println("TamilBulb: detail page fetch failed: ${e.message}")
            return false
        }
        println("TamilBulb: detail html size=${detailHtml.length}")

        // ---- 2. Extract stream_id with multiple strategies ----
        val streamId = extractStreamId(detailHtml)
        if (streamId == null) {
            println("TamilBulb: FAILED to extract stream_id")
            return false
        }
        println("TamilBulb: stream_id=$streamId")

        // ---- 3. Build the /embed/<base64(stream_id + unix_ts)> URL ----
        val ts = System.currentTimeMillis() / 1000
        val b64 = Base64.getEncoder().encodeToString("$streamId$ts".toByteArray())
        val embedUrl = "$mainUrl/embed/$b64"
        println("TamilBulb: embedUrl=$embedUrl")

        // ---- 4. Fetch the embed page ----
        val embedHtml = try {
            app.get(embedUrl, headers = mapOf("User-Agent" to UA)).text
        } catch (e: Exception) {
            println("TamilBulb: embed page fetch failed: ${e.message}")
            return false
        }
        println("TamilBulb: embed html size=${embedHtml.length}")

        val embedDoc = Jsoup.parse(embedHtml)

        // ---- 5. Enumerate players #player1..#playerN ----
        var any = false
        for (n in 1..10) {
            val player = embedDoc.selectFirst("#player$n") ?: continue
            val iframeUrl = player.selectFirst(".player-wrapper iframe")
                ?.attr("src")?.takeIf { it.isNotBlank() }
            if (iframeUrl == null) {
                println("TamilBulb: player$n has no iframe src")
                continue
            }
            val playerName = player.selectFirst(".player-name")?.text()?.trim() ?: "Player $n"
            println("TamilBulb: player$n [$playerName] → $iframeUrl")

            try {
                when {
                    iframeUrl.contains("tamilgun.space") ||
                    iframeUrl.contains("vidhide") -> {
                        TamilgunExtractor().extract(
                            iframeUrl, embedUrl, playerName, subtitleCallback, callback
                        )
                        any = true
                    }
                    iframeUrl.contains("byseraguci.com") ||
                    iframeUrl.contains("filemoon")      ||
                    iframeUrl.contains("n1mwq.org") -> {
                        N1mwqExtractor().extract(
                            iframeUrl, embedUrl, playerName, subtitleCallback, callback
                        )
                        any = true
                    }
                    else -> {
                        loadExtractor(iframeUrl, embedUrl, subtitleCallback, callback)
                        any = true
                    }
                }
            } catch (e: Exception) {
                println("TamilBulb: player$n extraction failed: ${e.message}")
            }
        }

        if (!any) {
            println("TamilBulb: no players produced links")
        } else {
            println("TamilBulb: loadLinks() finished successfully")
        }
        return any
    }

    /**
     * Extracts the streambulb/beeteam368 id from the detail HTML.
     * Tries 3 strategies because the site occasionally escapes JSON
     * with HTML entities (`&#34;`, `&quot;`) which break plain regexes.
     */
    private fun extractStreamId(html: String): String? {
        // Normalize the HTML entity escapes the site uses inside `<script>`
        val normalized = html
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&#39;", "'")
            .replace("\\\"", "\"")

        // Strategy 1 — raw `index.php?id=` anywhere
        Regex("""index\.php\?id=([A-Za-z0-9_\-]+)""")
            .find(normalized)?.groupValues?.get(1)?.let {
                println("TamilBulb: stream_id via strategy 1")
                return it
            }

        // Strategy 2 — beeteam368_pro_player config block
        Regex("""beeteam368_pro_player\(\{.*?"video_url":"[^"]*?[?&]id=([A-Za-z0-9_\-]+)""",
              RegexOption.DOT_MATCHES_ALL)
            .find(normalized)?.groupValues?.get(1)?.let {
                println("TamilBulb: stream_id via strategy 2")
                return it
            }

        // Strategy 3 — anything pointing at streambulb.site
        Regex("""streambulb\.site[^"'\s]*?[?&]id=([A-Za-z0-9_\-]+)""")
            .find(normalized)?.groupValues?.get(1)?.let {
                println("TamilBulb: stream_id via strategy 3")
                return it
            }

        // Debug dump — find the beeteam368 block so we can see what's there
        val idx = normalized.indexOf("beeteam368_pro_player")
        if (idx >= 0) {
            val end = minOf(normalized.length, idx + 600)
            println("TamilBulb: DEBUG beeteam368 block: ${normalized.substring(maxOf(0, idx - 40), end)}")
        } else {
            println("TamilBulb: DEBUG no beeteam368_pro_player in HTML")
        }
        return null
    }

    // ===================================================================
    // CARD → SearchResponse
    // ===================================================================
    private fun Element.toSearchCard(): SearchResponse? {
        val a = selectFirst("h3.entry-title a.post-listing-title") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = (a.attr("title").ifBlank { a.text() }).trim().ifBlank { return null }
        val poster = extractCardPoster(this)
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
        }
    }
}
