package com.Happy2hub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Happy2hub : MainAPI() {
    override var mainUrl              = "https://happy2hub.eu"
    override var name                 = "Happy2hub"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    // Real mobile browser header to prevent instant Cloudflare WAF flags
    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val requestHeaders = mapOf("User-Agent" to USER_AGENT)

    override val mainPage = mainPageOf(
        "ullu-a/" to "Ullu",
        "tag/primeplay-watch-online" to "Primeplay",
        "tag/altt-watch-online" to "Altt",
        "tag/bigshots-ott-watch-online" to "Bigshots",
        "tag/naari-magazine-watch-online" to "Naari",
        "tag/desiflix-originals-watch-online" to "Desiflix",
        "tag/idiot-boxx-watch-online" to "Idiot",
        "tag/hotshots-watch-online" to "Hotshots",
        "tag/mx-player-watch-online" to "MX Player",
        "tag/namastey-flix-originals" to "Namastey Flix",
        "tag/18" to "All Videos",
        "tag/brazzersexxtra" to "Brazzer",
        "tag/mojflix-watch-online" to "Mojflix",
        "tag/mangoflix-watch-online" to "Mangoflix",
        "tag/hothit-watch-online" to "Hothit",
        "tag/porn" to "All Porn",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}/page/$page"
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

        // Safely extract target link and handle relative paths
        val targetHref = document.selectFirst("div.entry-content.clearfix p a")?.attr("href")
        
        if (!targetHref.isNullOrEmpty()) {
            val fullTargetUrl = fixUrl(targetHref)
            val pTag = app.get(fullTargetUrl, headers = requestHeaders, timeout = 30L).document

            pTag.select("div.entry-content.clearfix h4:contains(Episode), div.entry-content.clearfix h5:contains(Episode)").forEach { episode ->
                val epno = episode.text().substringAfter("Episode ").trim()
                val episodeLinks = mutableListOf<String>()

                var nextElement = episode.nextElementSibling()
                while (nextElement != null && nextElement.tagName() != "p") {
                    nextElement.select("a").forEach { linkElement ->
                        val link = fixUrlNull(linkElement.attr("href"))
                        if (!link.isNullOrEmpty()) {
                            episodeLinks.add(link)
                        }
                    }
                    nextElement = nextElement.nextElementSibling()
                }

                if (episodeLinks.isNotEmpty()) {
                    episodes.add(newEpisode(episodeLinks.joinToString(",")) {
                        this.name = "Episode $epno"
                        this.posterUrl = poster
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
