package com.Happy2hub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder

class Happy2hub : MainAPI() {
    override var mainUrl              = "https://happy2hub.eu"
    override var name                 = "Happy2hub"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val requestHeaders = mapOf("User-Agent" to USER_AGENT)

    override val mainPage = mainPageOf(
        "ullu-b/" to "Ullu",
        "atrangii-b/" to "Atrangii",
        "altt/" to "Altt",
        "primeplay-f/" to "PrimePlay",
        "hotshots/" to "Hotshots",
        "voovi-b/" to "Voovi",
        "primeshots/" to "PrimeShots",
        "hitprime/" to "HitPrime",
    )

    private fun unwrapUrl(url: String): String {
        val fixed = fixUrl(url)
        return if (fixed.contains("?s=")) {
            try {
                val encodedUrl = fixed.substringAfter("?s=").substringBefore("&")
                URLDecoder.decode(encodedUrl, "UTF-8")
            } catch (e: Exception) {
                fixed
            }
        } else {
            fixed
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trimEnd('/')
        val url = "$mainUrl/$path/page/$page"

        val document = app.get(url, headers = requestHeaders, timeout = 30L).document
        val home = document.select("div.content-wrap > div > div > div").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h4 a") ?: return null
        val title     = titleElement.text().trim()
        val href      = fixUrl(titleElement.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()
        for (i in 1..5) {
            val document = app.get("$mainUrl/page/$i?s=$query", headers = requestHeaders, timeout = 30L).document
            val results = document.select("div.content-wrap > div > div > div").mapNotNull { it.toSearchResult() }

            if (results.isEmpty()) break

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }
        }
        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = requestHeaders, timeout = 30L).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val episodes = mutableListOf<Episode>()

        // Targets paste.happy2hub.eu or falls back to standard content link
        val targetHref = document.selectFirst("a[href*='paste.happy2hub.eu']")?.attr("href")
            ?: document.selectFirst("div.entry-content.clearfix p a")?.attr("href")
        
        if (!targetHref.isNullOrEmpty()) {
            val fullTargetUrl = fixUrl(targetHref)
            val pTag = app.get(fullTargetUrl, headers = requestHeaders, timeout = 30L).document

            // Select headers containing "Episode" regardless of tag (h4, h5, p, etc.)
            val episodeHeaders = pTag.select("div.entry-content.clearfix h4:contains(Episode), div.entry-content.clearfix h5:contains(Episode), div.entry-content.clearfix p:contains(Episode)")

            episodeHeaders.forEach { episodeHeader ->
                val epText = episodeHeader.text()
                
                // Extracts digits following "Episode" e.g. "Download or Watch Online Episode 1" -> "1"
                val epno = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)
                    ?: epText.substringAfter("Episode ").trim().takeWhile { it.isDigit() }.ifEmpty { "1" }

                val episodeLinks = mutableListOf<String>()

                // 1. Collect links inside the header tag itself (if any)
                episodeHeader.select("a").forEach { linkElement ->
                    val link = fixUrlNull(linkElement.attr("href"))
                    if (!link.isNullOrEmpty()) {
                        episodeLinks.add(unwrapUrl(link))
                    }
                }

                // 2. Iterate through sibling elements until encountering the next Episode heading
                var nextElement = episodeHeader.nextElementSibling()
                while (nextElement != null) {
                    val isNextEpisodeHeader = (nextElement.tagName() in listOf("h4", "h5", "p")) && 
                            nextElement.text().contains("Episode", ignoreCase = true)
                    
                    if (isNextEpisodeHeader) break

                    nextElement.select("a").forEach { linkElement ->
                        val link = fixUrlNull(linkElement.attr("href"))
                        if (!link.isNullOrEmpty()) {
                            episodeLinks.add(unwrapUrl(link))
                        }
                    }

                    nextElement = nextElement.nextElementSibling()
                }

                if (episodeLinks.isNotEmpty()) {
                    episodes.add(newEpisode(episodeLinks.distinct().joinToString(",")) {
                        this.name = "Episode $epno"
                        // Episode posters omitted
                    })
                }
            }

            // Fallback for pages without explicit "Episode" headings
            if (episodes.isEmpty()) {
                val fallbackLinks = mutableListOf<String>()
                pTag.select("div.entry-content.clearfix a").forEach { linkElement ->
                    val link = fixUrlNull(linkElement.attr("href"))
                    if (!link.isNullOrEmpty()) {
                        fallbackLinks.add(unwrapUrl(link))
                    }
                }
                if (fallbackLinks.isNotEmpty()) {
                    episodes.add(newEpisode(fallbackLinks.distinct().joinToString(",")) {
                        this.name = "Full Content"
                    })
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linksList = data.split(",").map { it.trim() }
        linksList.forEach { link ->
            if (link.isNotEmpty()) {
                loadExtractor(link, subtitleCallback, callback)
            }
        }
        return true
    }
}
