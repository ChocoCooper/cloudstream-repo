package com.AnimeJoker

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeJokerProvider : MainAPI() {
    override var mainUrl = "https://animejoker.com"
    override var name = "AnimeJoker"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()

        // 1. Fetch Series (Limit 8)
        val seriesElements = doc.select("#widget_list_movies_series-2-all ul li").take(8)
        if (seriesElements.isNotEmpty()) {
            home.add(HomePageList("Series", seriesElements.mapNotNull { toSearchResult(it) }))
        }

        // 2. Fetch Movies (Using the corrected ID: -3-all, Limit 8)
        val movieElements = doc.select("#widget_list_movies_series-3-all ul li").take(8)
        if (movieElements.isNotEmpty()) {
            home.add(HomePageList("Movies", movieElements.mapNotNull { toSearchResult(it) }))
        }

        return newHomePageResponse(home)
    }

    // Helper function to keep parsing clean
    private fun toSearchResult(item: Element): SearchResponse? {
        val a = item.selectFirst("a") ?: return null
        val href = a.attr("href")
        val img = item.selectFirst(".post-thumbnail img")

        // Prioritize explicit title, fallback to 'title' attr, fallback to 'alt' attr
        val title = item.selectFirst(".entry-title")?.text()
            ?: a.attr("title").ifEmpty { img?.attr("alt") }
            ?: "No Title"
        val poster = img?.attr("src")

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        var page = 1
        var hasNext = true

        // Pagination loop: Caps at 5 pages to prevent accidental infinite looping
        while (hasNext && page <= 5) {
            val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
            val doc = app.get(url).document

            val items = doc.select("#movies-a ul.post-lst li")
            if (items.isEmpty()) break

            items.forEach { item ->
                toSearchResult(item)?.let { searchResponse.add(it) }
            }

            // Check if there is a 'NEXT' button in the pagination nav
            val nextLink = doc.select(".nav-links a").find { it.text().contains("NEXT", ignoreCase = true) }
            if (nextLink != null) {
                page++
            } else {
                hasNext = false
            }
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // Grab title from standard entry-title class
        val title = doc.selectFirst("h1.entry-title, .title")?.text() ?: "No Title"
        val poster = doc.selectFirst(".post-thumbnail img")?.attr("src")

        // Distinguish between Movies and Series via URL path
        val isMovie = url.contains("/movies/")
        val episodes = mutableListOf<Episode>()

        if (isMovie) {
            // Movies only have 1 item directly on the page
            episodes.add(
                newEpisode(url) {
                    this.name = title
                }
            )
        } else {
            // Series loops through the episode list
            doc.select("#episode_by_temp li").forEachIndexed { index, item ->
                val a = item.selectFirst("a.lnk-blk") ?: return@forEachIndexed
                val href = a.attr("href")
                val img = item.selectFirst(".post-thumbnail img")?.attr("src")
                episodes.add(
                    newEpisode(href) {
                        this.name = "Episode ${index + 1}"
                        this.posterUrl = img
                        this.episode = index + 1
                    }
                )
            }
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    // Safely decode Base64 strings if the site tries to hide URLs in them
    private fun String.b64Decode(): String {
        return try {
            String(Base64.decode(this, Base64.DEFAULT))
        } catch (e: Exception) {
            this
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val html = doc.html()
        var foundLinks = false

        // We will build a queue of links/iframes to check so we can dig through redirects.
        val toCheck = mutableListOf<String>()

        // 1. Find all standard iframes
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) toCheck.add(src)
        }

        // 2. Find hidden links in data attributes (often Base64 encoded in custom themes)
        doc.select("[data-video], [data-src], [data-embed], [data-tplayerv]").forEach {
            val src = it.attr("data-video").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("data-embed") }.ifEmpty { it.attr("data-tplayerv") }
            if (src.isNotBlank()) {
                if (src.startsWith("http") || src.startsWith("//")) {
                    toCheck.add(src)
                } else {
                    val decoded = src.b64Decode()
                    if (decoded.startsWith("http") || decoded.startsWith("//")) toCheck.add(decoded)
                }
            }
        }

        // 3. Find any embedseek URLs hiding as raw text/javascript variables
        Regex("""(https?://(?:[^"'\s\\]+)?embedseek[^"'\s\\]*)""").findAll(html).forEach {
            toCheck.add(it.value.replace("\\", ""))
        }

        // 4. Dooplay standard AJAX fetching
        val servers = doc.select("[data-post][data-nume][data-type]")
        if (servers.isNotEmpty()) {
            val ajaxUrl = Regex("""['"]([^'"]+admin-ajax\.php)['"]""").find(html)?.groupValues?.get(1) ?: "$mainUrl/wp-admin/admin-ajax.php"
            for (server in servers) {
                val post = server.attr("data-post")
                val nume = server.attr("data-nume")
                val type = server.attr("data-type")

                if (post.isNotBlank()) {
                    try {
                        val response = app.post(
                            url = ajaxUrl,
                            data = mapOf("action" to "doo_player", "post" to post, "nume" to nume, "type" to type),
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest", "Referer" to data)
                        ).text

                        val embedUrlRegex = Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)
                        if (embedUrlRegex != null) {
                            val cleanHtml = embedUrlRegex.replace("\\/", "/").replace("\\\"", "\"")
                            val iframeSrc = Regex("""src=["']([^"']+)["']""").find(cleanHtml)?.groupValues?.get(1)
                            if (iframeSrc != null) toCheck.add(iframeSrc)
                            else if (cleanHtml.startsWith("http")) toCheck.add(cleanHtml)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // Now process the queue. We will check each URL to see if it's the player token.
        // We limit it to 15 iterations to prevent infinite looping on broken pages.
        val checked = mutableSetOf<String>()
        var iterations = 0

        while (toCheck.isNotEmpty() && iterations < 15) {
            iterations++
            val currentUrl = toCheck.removeFirst()
            if (currentUrl.isBlank() || checked.contains(currentUrl)) continue
            checked.add(currentUrl)

            val fixedUrl = if (currentUrl.startsWith("//")) "https:$currentUrl" else currentUrl
            if (!fixedUrl.startsWith("http")) continue

            try {
                // If it's already a direct M3U8 link, return it immediately
                if (fixedUrl.contains(".m3u8")) {
                    callback(
                        newExtractorLink(
                            source = "AnimeJoker",
                            name = "Direct Server",
                            url = fixedUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                    continue
                }

                // Fetch the inner contents of the iframe/link
                val responseText = app.get(fixedUrl, referer = data).text

                // Check A: Embedseek Token Extraction
                val token = Regex("""t=([0-9a-fA-F]{30,})""").find(responseText)?.groupValues?.get(1)
                if (token != null) {
                    val domain = Regex("""(https?://[^/]+)""").find(fixedUrl)?.groupValues?.get(1) ?: "https://jkrowl.embedseek.online"
                    val m3u8Url = "$domain/api/v1/player?t=$token"

                    callback(
                        newExtractorLink(
                            source = "Embedseek",
                            name = "Embedseek HD",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$domain/" // Critical for Cloudstream ExoPlayer authorization
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                    continue
                }

                // Check B: Generic fallback if a server just exposed the .m3u8 link directly inside the iframe
                val m3u8 = Regex("""(https?://[^"']*?\.m3u8[^"']*?)["'\\]""").find(responseText)?.groupValues?.get(1)
                if (m3u8 != null) {
                    callback(
                        newExtractorLink(
                            source = "Server",
                            name = "Server HD",
                            url = m3u8.replace("\\", ""),
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = fixedUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                    continue
                }

                // Check C: Nested Iframes (If the iframe just loaded another iframe, add it to our search queue)
                Regex("""(?:<iframe[^>]*src=["']|atob\(\s*["']|base64,\s*["'])([^"']+)["']""").findAll(responseText).forEach { match ->
                    val innerUrl = match.groupValues[1]
                    val decodedUrl = if (innerUrl.startsWith("http") || innerUrl.startsWith("//")) innerUrl else innerUrl.b64Decode()
                    if (!checked.contains(decodedUrl)) toCheck.add(decodedUrl)
                }

            } catch (e: Exception) {
                // Ignore network errors on bad ad iframes so the queue can continue
            }
        }

        return foundLinks
    }
}
