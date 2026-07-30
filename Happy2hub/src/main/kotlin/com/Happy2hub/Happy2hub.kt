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

    // Playable server domain keywords supported by Cloudstream extractors
    private val supportedDomains = listOf(
        "pixeldrain", "luluvid", "lulustream", "playmogo",
        "dood", "myvidplay", "voe", "streamtape", "streamwish"
    )

    private fun isSupportedDomain(url: String): Boolean {
        return supportedDomains.any { domain -> url.contains(domain, ignoreCase = true) }
    }

    /**
     * Unwraps redirection wrappers (ouo.io) to extract the real destination URL
     */
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

    /**
     * Filters a line of <a> tags to pick ONLY the highest quality link available (1080p > 720p > 480p).
     * If no quality labels exist (e.g. "LuluStream", "DoodStream"), returns all tags.
     */
    private fun selectBestQualityLinks(aElements: List<Element>): List<Element> {
        if (aElements.isEmpty()) return emptyList()

        val hasQualityLabel = aElements.any {
            val text = it.text().trim().lowercase()
            text.contains("1080p") || text.contains("720p") || text.contains("480p")
        }

        if (!hasQualityLabel) {
            return aElements
        }

        val link1080p = aElements.firstOrNull { it.text().contains("1080p", ignoreCase = true) }
        val link720p  = aElements.firstOrNull { it.text().contains("720p", ignoreCase = true) }
        val link480p  = aElements.firstOrNull { it.text().contains("480p", ignoreCase = true) }

        val bestLink = link1080p ?: link720p ?: link480p
        return if (bestLink != null) listOf(bestLink) else aElements
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

        val targetHref = document.selectFirst("a[href*='paste.happy2hub.eu']")?.attr("href")
            ?: document.selectFirst("div.entry-content.clearfix p a")?.attr("href")

        if (!targetHref.isNullOrEmpty()) {
            val fullTargetUrl = fixUrl(targetHref)
            val pTag = app.get(fullTargetUrl, headers = requestHeaders, timeout = 30L).document

            val episodeHeaders = pTag.select("div.entry-content.clearfix h4:contains(Episode), div.entry-content.clearfix h5:contains(Episode), div.entry-content.clearfix p:contains(Episode)")

            episodeHeaders.forEach { episodeHeader ->
                val epText = episodeHeader.text()
                // Extracts episode number from "Download Episode 1" or "Download or Watch Online Episode 1"
                val epno = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)
                    ?: epText.substringAfter("Episode ").trim().takeWhile { it.isDigit() }.ifEmpty { "1" }

                val rawLinks = mutableListOf<String>()

                // Check links inside header
                val headerATags = episodeHeader.select("a")
                selectBestQualityLinks(headerATags).forEach { a ->
                    val link = fixUrlNull(a.attr("href"))
                    if (!link.isNullOrEmpty()) rawLinks.add(unwrapUrl(link))
                }

                // Check links inside sibling blocks until next episode
                var nextElement = episodeHeader.nextElementSibling()
                while (nextElement != null) {
                    val isNextHeader = (nextElement.tagName() in listOf("h4", "h5", "p")) &&
                            nextElement.text().contains("Episode", ignoreCase = true)

                    if (isNextHeader) break

                    val siblingATags = nextElement.select("a")
                    selectBestQualityLinks(siblingATags).forEach { a ->
                        val link = fixUrlNull(a.attr("href"))
                        if (!link.isNullOrEmpty()) rawLinks.add(unwrapUrl(link))
                    }

                    nextElement = nextElement.nextElementSibling()
                }

                // Filter out non-streaming file hosters (UpFiles, Sendcm, 4Sync, etc.) to keep playable links
                var playableLinks = rawLinks.filter { isSupportedDomain(it) }.distinct()

                // Fallback to raw links if domain filter returns empty
                if (playableLinks.isEmpty()) {
                    playableLinks = rawLinks.distinct()
                }

                if (playableLinks.isNotEmpty()) {
                    episodes.add(newEpisode(playableLinks.joinToString(",")) {
                        this.name = "Episode $epno"
                    })
                }
            }

            // Fallback for pages without explicit Episode headings
            if (episodes.isEmpty()) {
                val rawFallbackLinks = mutableListOf<String>()
                pTag.select("div.entry-content.clearfix h5, div.entry-content.clearfix p").forEach { container ->
                    val aTags = container.select("a")
                    selectBestQualityLinks(aTags).forEach { a ->
                        val link = fixUrlNull(a.attr("href"))
                        if (!link.isNullOrEmpty()) rawFallbackLinks.add(unwrapUrl(link))
                    }
                }

                var playableFallback = rawFallbackLinks.filter { isSupportedDomain(it) }.distinct()
                if (playableFallback.isEmpty()) playableFallback = rawFallbackLinks.distinct()

                if (playableFallback.isNotEmpty()) {
                    episodes.add(newEpisode(playableFallback.joinToString(",")) {
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
