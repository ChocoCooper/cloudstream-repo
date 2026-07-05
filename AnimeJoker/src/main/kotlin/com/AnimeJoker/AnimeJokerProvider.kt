package com.AnimeJoker

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
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
        val img = item.selectFirst(".post-thumbnail img, img")

        // Prioritize explicit title, fallback to 'title' attr, fallback to 'alt' attr
        val title = item.selectFirst(".entry-title")?.text()
            ?: a.attr("title").ifEmpty { img?.attr("alt") }
            ?: "No Title"

        // Torofilm frequently uses data-src for lazy loading images
        val poster = img?.attr("data-src")?.ifEmpty {
            img.attr("data-lazy-src")
        }?.ifEmpty {
            img.attr("src")
        }

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
        val title = doc.selectFirst("h1.entry-title, .title, .post-title")?.text() ?: "No Title"
        
        val posterImg = doc.selectFirst(".post-thumbnail img")
        val poster = posterImg?.attr("data-src")?.ifEmpty {
            posterImg.attr("data-lazy-src")
        }?.ifEmpty {
            posterImg.attr("src")
        }

        val episodeElements = doc.select("#episode_by_temp li")
        
        // Movie vs Series Logic:
        // If there are 0 or 1 episodes in the list, format it as a Movie.
        if (episodeElements.isEmpty() || episodeElements.size == 1) {
            val dataUrl = if (episodeElements.size == 1) {
                episodeElements.first()?.selectFirst("a.lnk-blk")?.attr("href") ?: url
            } else {
                url
            }
            
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, dataUrl) {
                this.posterUrl = poster
            }
        } else {
            // It's a Series
            val episodes = mutableListOf<Episode>()
            episodeElements.forEachIndexed { index, item ->
                val a = item.selectFirst("a.lnk-blk") ?: return@forEachIndexed
                val href = a.attr("href")
                
                val img = item.selectFirst(".post-thumbnail img")
                val epPoster = img?.attr("data-src")?.ifEmpty {
                    img.attr("data-lazy-src")
                }?.ifEmpty {
                    img.attr("src")
                }
                
                episodes.add(
                    newEpisode(href) {
                        this.name = "Episode ${index + 1}"
                        this.posterUrl = epPoster
                        this.episode = index + 1
                    }
                )
            }

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                addEpisodes(DubStatus.Subbed, episodes)
            }
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

        // 1. Extract all iframes. Torofilm heavily uses data-src for lazy loading in tabs (#options-0)
        val initialIframes = doc.select("div.video iframe, iframe").mapNotNull {
            it.attr("data-src").ifEmpty {
                it.attr("data-lazy-src").ifEmpty {
                    it.attr("src")
                }
            }
        }.filter { it.isNotBlank() }.toSet()

        for (link in initialIframes) {
            var targetUrl = if (link.startsWith("//")) "https:$link" else link

            // Step 1: Trace internal ?trembed links to find the REAL server iframe
            if (targetUrl.contains("?trembed=")) {
                try {
                    val embedDoc = app.get(targetUrl, referer = data).document
                    val innerIframe = embedDoc.selectFirst("iframe")?.let {
                        it.attr("data-src").ifEmpty { it.attr("src") }
                    }
                    if (!innerIframe.isNullOrBlank()) {
                        targetUrl = if (innerIframe.startsWith("//")) "https:$innerIframe" else innerIframe
                    }
                } catch (e: Exception) {
                    continue // Skip if network fails on this specific tab
                }
            }

            // At this point, targetUrl should be the actual server (e.g., https://jkrowl.embedseek.online/...)

            // Step 2: Push the real server URL through the WebViewResolver
            try {
                // The resolver will wait in the background until the player executes its CF check
                // and requests the master m3u8 or the API token.
                val resolvedUrl = app.get(
                    targetUrl,
                    interceptor = WebViewResolver(Regex("""(api/v1/player\?t=|master\.m3u8|\.m3u8|\.mp4)""")),
                    referer = data
                ).url

                if (resolvedUrl.contains("player?t=") || resolvedUrl.contains(".m3u8") || resolvedUrl.contains(".mp4")) {
                    callback(
                        newExtractorLink(
                            source = if (targetUrl.contains("embedseek")) "Embedseek" else "Server",
                            name = if (targetUrl.contains("embedseek")) "Embedseek HD" else "Server HD",
                            url = resolvedUrl,
                            type = if (resolvedUrl.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                        ) {
                            this.referer = targetUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    foundLinks = true
                }
            } catch (e: Exception) {
                // WebViewResolver throws an exception if it times out (e.g., if a click is strictly required and not automated).
                e.printStackTrace()
            }
        }
        
        return foundLinks
    }
}
