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
            episodes.add(newEpisode(url, title))
        } else {
            // Series loops through the episode list
            doc.select("#episode_by_temp li").forEachIndexed { index, item ->
                val a = item.selectFirst("a.lnk-blk") ?: return@forEachIndexed
                val href = a.attr("href")
                val img = item.selectFirst(".post-thumbnail img")?.attr("src")
                episodes.add(
                    newEpisode(
                        data = href,
                        name = "Episode ${index + 1}",
                        posterUrl = img,
                        episode = index + 1
                    )
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
        val html = doc.html()

        // Locate the Embedseek iframe (either by element or regex search in raw HTML)
        val iframeSrc = doc.select("iframe").map { it.attr("src") }.find { it.contains("embedseek") }
            ?: Regex("""(https?://[^"']*?embedseek[^"']*?)["']""").find(html)?.groupValues?.get(1)

        if (iframeSrc != null) {
            val iframeDoc = app.get(iframeSrc, referer = "$mainUrl/").text

            // Attempt A: See if the m3u8 is exposed directly inside the iframe's text
            val directM3u8 = Regex("""(https?://[^"']*?\.m3u8[^"']*?)["'\\]""").find(iframeDoc)?.groupValues?.get(1)
            if (directM3u8 != null) {
                callback(
                    newExtractorLink(
                        source = "Embedseek",
                        name = "Embedseek",
                        url = directM3u8.replace("\\", ""),
                        referer = iframeSrc,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                return true
            }

            // Attempt B: Extract the 't' token you found in DevTools and hit the API
            val token = Regex("""t=([0-9a-fA-F]{30,})""").find(iframeDoc)?.groupValues?.get(1)
            if (token != null) {
                val apiUrl = "https://jkrowl.embedseek.online/api/v1/player?t=$token"
                val apiResponse = app.get(
                    apiUrl, 
                    headers = mapOf(
                        "Referer" to iframeSrc,
                        "Accept" to "*/*"
                    )
                ).text

                // Since it returns application/octet-stream, it could be Base64, JSON, or raw text.
                // Regex tries to parse the .m3u8 link regardless of how it's wrapped.
                val extractedM3u8 = Regex("""(https?://[^"']*?\.m3u8[^"']*?)["'\\]""").find(apiResponse)?.groupValues?.get(1)
                
                if (extractedM3u8 != null) {
                    callback(
                        newExtractorLink(
                            source = "Embedseek API",
                            name = "Embedseek (API)",
                            url = extractedM3u8.replace("\\", ""),
                            referer = iframeSrc,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                    return true
                }
            }
        }

        // Final Fallback: If the .m3u8 was embedded directly in AnimeJoker's main HTML
        val fallbackM3u8 = Regex("""(https?://[^"']*?\.m3u8[^"']*?)["'\\]""").find(html)?.groupValues?.get(1)
        if (fallbackM3u8 != null) {
            callback(
                newExtractorLink(
                    source = "AnimeJoker",
                    name = "AnimeJoker",
                    url = fallbackM3u8.replace("\\", ""),
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true
        }

        return false
    }
}
