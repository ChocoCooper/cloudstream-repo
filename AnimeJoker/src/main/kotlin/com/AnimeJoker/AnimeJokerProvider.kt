package com.AnimeJoker

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

        // 2. Fetch Movies (Limit 8)
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        var foundLinks = false
        val iframeLinks = mutableSetOf<String>()

        // 1. Direct iframes (If they are loaded natively without AJAX)
        doc.select("iframe").forEach { iframe ->
            iframe.attr("src").takeIf { it.isNotBlank() }?.let { iframeLinks.add(it) }
        }

        // 2. DooPlay AJAX Server Extraction
        // This triggers the WP admin-ajax.php to retrieve the hidden server iframes
        val servers = doc.select("[data-post][data-nume][data-type]")
        for (server in servers) {
            val post = server.attr("data-post")
            val nume = server.attr("data-nume")
            val type = server.attr("data-type")

            if (post.isNotBlank() && nume.isNotBlank() && type.isNotBlank()) {
                try {
                    val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                    val response = app.post(
                        url = ajaxUrl,
                        data = mapOf(
                            "action" to "doo_player",
                            "post" to post,
                            "nume" to nume,
                            "type" to type
                        ),
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data
                        )
                    ).text

                    // The response is JSON containing {"embed_url": "<iframe src='...'>"}
                    val embedUrlRegex = Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)
                    if (embedUrlRegex != null) {
                        // Clean up escaped JSON slashes and quotes
                        val cleanHtml = embedUrlRegex.replace("\\/", "/").replace("\\\"", "\"")
                        val iframeSrc = Regex("""src=["']([^"']+)["']""").find(cleanHtml)?.groupValues?.get(1)
                        
                        if (iframeSrc != null) {
                            iframeLinks.add(iframeSrc)
                        } else if (cleanHtml.startsWith("http")) {
                            iframeLinks.add(cleanHtml)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // 3. Iterate over the found iframes and resolve the final .m3u8 token
        for (iframeUrl in iframeLinks) {
            val fixedUrl = if (iframeUrl.startsWith("//")) "https:$iframeUrl" else iframeUrl

            try {
                val iframeDocText = app.get(fixedUrl, referer = data).text

                // Check if it's an Embedseek/jkrowl player
                val token = Regex("""t=([0-9a-fA-F]{30,})""").find(iframeDocText)?.groupValues?.get(1)
                
                if (token != null) {
                    // Extract domain dynamically in case embedseek changes subdomains (e.g., player2.embedseek.online)
                    val domain = Regex("""(https?://[^/]+)""").find(fixedUrl)?.groupValues?.get(1) ?: "https://jkrowl.embedseek.online"
                    
                    // Since Embedseek's API returns the master M3U8 directly as an octet-stream, 
                    // we can pass the API URL directly to ExoPlayer!
                    val m3u8Url = "$domain/api/v1/player?t=$token"
                    
                    callback(
                        newExtractorLink(
                            source = "Embedseek",
                            name = "Embedseek HD",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$domain/" // Critical: The API requires the domain as the referer
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                    continue
                }

                // Generic Fallback: if there's a standard m3u8 directly in the iframe source
                val m3u8 = Regex("""(https?://[^"']*?\.m3u8[^"']*?)["'\\]""").find(iframeDocText)?.groupValues?.get(1)
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
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return foundLinks
    }
}
