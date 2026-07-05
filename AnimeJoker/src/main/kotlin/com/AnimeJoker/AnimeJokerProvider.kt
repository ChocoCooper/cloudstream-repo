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

        val title = item.selectFirst(".entry-title")?.text()
            ?: a.attr("title").ifEmpty { img?.attr("alt") }
            ?: "No Title"

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

        while (hasNext && page <= 5) {
            val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
            val doc = app.get(url).document

            val items = doc.select("#movies-a ul.post-lst li")
            if (items.isEmpty()) break

            items.forEach { item ->
                toSearchResult(item)?.let { searchResponse.add(it) }
            }

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

        val title = doc.selectFirst("h1.entry-title, .title, .post-title")?.text() ?: "No Title"
        
        val posterImg = doc.selectFirst(".post-thumbnail img")
        val poster = posterImg?.attr("data-src")?.ifEmpty {
            posterImg.attr("data-lazy-src")
        }?.ifEmpty {
            posterImg.attr("src")
        }

        val episodeElements = doc.select("#episode_by_temp li")
        
        // Format as Movie if 0 or 1 episodes exist
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

        // Extract raw iframes (prioritizing lazy loaded data-src)
        val initialIframes = doc.select("div.video iframe, iframe").mapNotNull {
            it.attr("data-src").ifEmpty {
                it.attr("data-lazy-src").ifEmpty {
                    it.attr("src")
                }
            }
        }.filter { it.isNotBlank() }.toSet()

        for (link in initialIframes) {
            var targetUrl = if (link.startsWith("//")) "https:$link" else link

            // Step 1: Resolve internal ?trembed links
            if (targetUrl.contains("?trembed=")) {
                try {
                    val res = app.get(targetUrl, referer = data)
                    // Did it do a 301/302 Redirect directly to the server?
                    if (res.url != targetUrl && !res.url.contains("?trembed=")) {
                        targetUrl = res.url
                    } else {
                        // Or did it load an HTML page with an iframe inside?
                        val innerIframe = res.document.selectFirst("iframe")?.let {
                            it.attr("data-src").ifEmpty { it.attr("src") }
                        }
                        if (!innerIframe.isNullOrBlank()) {
                            targetUrl = if (innerIframe.startsWith("//")) "https:$innerIframe" else innerIframe
                        }
                    }
                } catch (e: Exception) {
                    continue 
                }
            }

            // Step 2: Instantly drop dead/parked domain ads
            if (targetUrl.contains("cdntamilbulb.online") || targetUrl.contains("parking.godaddy") || targetUrl.contains("wsimg.com")) {
                continue
            }

            // Step 3: Embedseek Direct API Hijack (Bypasses Ads & Timeouts)
            if (targetUrl.contains("embedseek")) {
                try {
                    val domain = Regex("""(https?://[^/]+)""").find(targetUrl)?.value ?: "https://jkrowl.embedseek.online"
                    
                    // Extracts 'hw16q' from '.../#hw16q' or '.../e/hw16q'
                    val id = targetUrl.split("/", "#").last { it.isNotBlank() }
                    val infoUrl = "$domain/api/v1/info?id=$id"
                    
                    // Fetch the API JSON. 
                    // (If Cloudflare blocks the raw GET, we fallback to WebViewResolver just to read the JSON text)
                    val infoResponse = try {
                         app.get(infoUrl, referer = targetUrl).text
                    } catch (e: Exception) {
                         app.get(infoUrl, interceptor = WebViewResolver(Regex("""api/v1/info""")), referer = targetUrl).document.text()
                    }
                    
                    // Extract the massive encrypted token payload
                    val tokenMatches = Regex("""([a-fA-F0-9]{40,})""").findAll(infoResponse)
                    val token = tokenMatches.maxByOrNull { it.value.length }?.value
                    val m3u8 = Regex("""(https?://[^"'\s]*?\.m3u8[^"'\s]*)""").find(infoResponse)?.groupValues?.get(1)
                    
                    if (m3u8 != null) {
                        callback(
                            newExtractorLink(
                                source = "Embedseek",
                                name = "Embedseek HD",
                                url = m3u8.replace("\\", ""),
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$domain/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    } else if (token != null) {
                        val playerUrl = "$domain/api/v1/player?t=$token"
                        callback(
                            newExtractorLink(
                                source = "Embedseek",
                                name = "Embedseek HD",
                                url = playerUrl,
                                type = ExtractorLinkType.M3U8 
                            ) {
                                this.referer = "$domain/" // Critical authorization header
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                // Step 4: Any standard streaming servers get routed to built-in Cloudstream extractors
                foundLinks = loadExtractor(targetUrl, data, subtitleCallback, callback) || foundLinks
            }
        }
        
        return foundLinks
    }
}
