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
        val html = doc.html()
        var foundLinks = false

        // 1. Extract all iframes. Torofilm heavily uses data-src for lazy loading in tabs (#options-0)
        val iframes = doc.select("iframe").mapNotNull {
            it.attr("data-src").ifEmpty {
                it.attr("data-lazy-src").ifEmpty {
                    it.attr("src")
                }
            }
        }.filter { it.isNotBlank() }.toSet()

        // 2. Fallback: Sometimes Torofilm dumps the raw iframe URL into global javascript
        val regexLinks = Regex("""(https?://[^"'\s]*?embedseek[^"'\s]*)""").findAll(html).map { it.groupValues[1] }.toSet()

        val allLinks = (iframes + regexLinks).filter { it.contains("embedseek") || it.contains(".m3u8") }.toSet()

        for (link in allLinks) {
            val url = if (link.startsWith("//")) "https:$link" else link

            if (url.contains("embedseek")) {
                try {
                    // Because Embedseek has human verification (Cloudflare), standard requests will fail.
                    // We use WebViewResolver to open a hidden browser, solve the verification, and intercept the API request.
                    val resolvedUrl = app.get(
                        url,
                        interceptor = WebViewResolver(Regex("""api/v1/player\?t=|master\.m3u8|\.m3u8"""))
                    ).url

                    if (resolvedUrl.contains("api/v1/player") || resolvedUrl.contains(".m3u8")) {
                        callback(
                            newExtractorLink(
                                source = "Embedseek",
                                name = "Embedseek HD",
                                url = resolvedUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = url
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    // Fallback to standard HTTP GET if WebViewResolver times out (less likely to work due to verification)
                    try {
                        val res = app.get(url, referer = "$mainUrl/").text
                        val token = Regex("""t=([0-9a-fA-F]{30,})""").find(res)?.groupValues?.get(1)
                        if (token != null) {
                            val domain = Regex("""(https?://[^/]+)""").find(url)?.groupValues?.get(1) ?: "https://jkrowl.embedseek.online"
                            val apiUrl = "$domain/api/v1/player?t=$token"
                            callback(
                                newExtractorLink(
                                    source = "Embedseek API",
                                    name = "Embedseek API",
                                    url = apiUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$domain/"
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            foundLinks = true
                        }
                    } catch (e2: Exception) {
                        e2.printStackTrace()
                    }
                }
            } else if (url.contains(".m3u8")) {
                // Generic server catch-all
                callback(
                    newExtractorLink(
                        source = "Server",
                        name = "Server HD",
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                foundLinks = true
            }
        }
        return foundLinks
    }
}
