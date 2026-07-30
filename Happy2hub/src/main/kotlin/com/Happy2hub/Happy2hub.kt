package com.Happy2hub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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

    private val supportedDomains = listOf(
        "pixeldrain", "luluvid", "lulustream", "luluvdo", "lulu",
        "playmogo", "dood", "myvidplay", "voe", "streamtape", "streamwish"
    )

    private fun isSupportedDomain(url: String): Boolean {
        return supportedDomains.any { domain -> url.contains(domain, ignoreCase = true) }
    }

    private fun unwrapUrl(url: String): String {
        var currentUrl = fixUrl(url)
        while (currentUrl.contains("?s=")) {
            currentUrl = try {
                val encodedUrl = currentUrl.substringAfter("?s=").substringBefore("&")
                URLDecoder.decode(encodedUrl, "UTF-8")
            } catch (e: Exception) {
                break
            }
        }
        return currentUrl
    }

    private fun getQualityFromName(qualityStr: String): Int {
        val lower = qualityStr.lowercase()
        return when {
            lower.contains("1080") -> Qualities.P1080.value
            lower.contains("720")  -> Qualities.P720.value
            lower.contains("480")  -> Qualities.P480.value
            lower.contains("360")  -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun getQualityString(qualityInt: Int): String {
        return when (qualityInt) {
            Qualities.P1080.value -> "1080p"
            Qualities.P720.value  -> "720p"
            Qualities.P480.value  -> "480p"
            Qualities.P360.value  -> "360p"
            else -> ""
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trimEnd('/')
        val url = "$mainUrl/$path/page/$page"

        val home = try {
            val document = app.get(url, headers = requestHeaders, timeout = 30L).document
            document.select("div.content-wrap > div > div > div").mapNotNull { it.toSearchResult() }
        } catch (e: Exception) {
            emptyList()
        }

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
        for (i in 1..3) {
            val results = try {
                val document = app.get("$mainUrl/page/$i?s=$query", headers = requestHeaders, timeout = 30L).document
                document.select("div.content-wrap > div > div > div").mapNotNull { it.toSearchResult() }
            } catch (e: Exception) {
                emptyList()
            }

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
                val epno = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(epText)?.groupValues?.get(1)
                    ?: epText.substringAfter("Episode ").trim().takeWhile { it.isDigit() }.ifEmpty { "1" }

                val rawLinks = mutableListOf<String>()

                episodeHeader.select("a").forEach { a ->
                    val rawHref = fixUrlNull(a.attr("href"))
                    if (!rawHref.isNullOrEmpty()) {
                        val unwrapped = unwrapUrl(rawHref)
                        val text = a.text().trim()
                        val qTag = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(text)?.value ?: ""
                        rawLinks.add("$unwrapped|$qTag")
                    }
                }

                var nextElement = episodeHeader.nextElementSibling()
                while (nextElement != null) {
                    val isNextHeader = (nextElement.tagName() in listOf("h4", "h5", "p")) &&
                            nextElement.text().contains("Episode", ignoreCase = true)

                    if (isNextHeader) break

                    nextElement.select("a").forEach { a ->
                        val rawHref = fixUrlNull(a.attr("href"))
                        if (!rawHref.isNullOrEmpty()) {
                            val unwrapped = unwrapUrl(rawHref)
                            val text = a.text().trim()
                            val qTag = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(text)?.value ?: ""
                            rawLinks.add("$unwrapped|$qTag")
                        }
                    }

                    nextElement = nextElement.nextElementSibling()
                }

                var playableLinks = rawLinks.filter { isSupportedDomain(it.substringBefore("|")) }.distinct()
                if (playableLinks.isEmpty()) {
                    playableLinks = rawLinks.distinct()
                }

                if (playableLinks.isNotEmpty()) {
                    episodes.add(newEpisode(playableLinks.joinToString(",")) {
                        this.name = "Episode $epno"
                    })
                }
            }

            if (episodes.isEmpty()) {
                val rawFallbackLinks = mutableListOf<String>()
                pTag.select("div.entry-content.clearfix h5, div.entry-content.clearfix p").forEach { container ->
                    container.select("a").forEach { a ->
                        val rawHref = fixUrlNull(a.attr("href"))
                        if (!rawHref.isNullOrEmpty()) {
                            val unwrapped = unwrapUrl(rawHref)
                            val text = a.text().trim()
                            val qTag = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(text)?.value ?: ""
                            rawFallbackLinks.add("$unwrapped|$qTag")
                        }
                    }
                }

                var playableFallback = rawFallbackLinks.filter { isSupportedDomain(it.substringBefore("|")) }.distinct()
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

    private suspend fun loadExtractorWithCustomName(
        url: String,
        qualityTag: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        loadExtractor(
            url = url,
            subtitleCallback = subtitleCallback,
            callback = { link ->
                val qVal = getQualityFromName(qualityTag)
                val finalQuality = if (qVal != Qualities.Unknown.value) qVal else link.quality
                val qStr = if (qualityTag.isNotEmpty()) qualityTag else getQualityString(link.quality)

                val displayName = if (qStr.isNotEmpty() && !link.name.contains(qStr, ignoreCase = true)) {
                    "${link.name} $qStr"
                } else {
                    link.name
                }

                val modifiedLink = link.copy(
                    name = displayName,
                    quality = finalQuality
                )
                callback.invoke(modifiedLink)
            }
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val linksList = data.split(",").map { it.trim() }
        linksList.forEach { rawItem ->
            if (rawItem.isNotEmpty()) {
                val parts = rawItem.split("|")
                val rawLink = parts[0].trim()
                val qualityTag = if (parts.size > 1) parts[1].trim() else ""

                when {
                    rawLink.contains("pixeldrain", ignoreCase = true) -> {
                        val fileId = Regex("""pixeldrain\.(?:com|dev)/(?:u|api/file)/([a-zA-Z0-9]+)""")
                            .find(rawLink)?.groupValues?.get(1)

                        if (fileId != null) {
                            val serverName = "PixelDrain"
                            val displayName = if (qualityTag.isNotEmpty()) "$serverName $qualityTag" else serverName

                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = displayName,
                                    url = "https://pixeldrain.dev/api/file/$fileId",
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "https://pixeldrain.dev/"
                                    this.quality = getQualityFromName(qualityTag)
                                }
                            )
                        } else {
                            loadExtractorWithCustomName(rawLink, qualityTag, subtitleCallback, callback)
                        }
                    }

                    rawLink.contains("luluvid", ignoreCase = true) ||
                    rawLink.contains("lulustream", ignoreCase = true) ||
                    rawLink.contains("luluvdo", ignoreCase = true) -> {
                        val fileId = Regex("""/(?:d|e)/([a-zA-Z0-9_-]+)""")
                            .find(rawLink)?.groupValues?.get(1)

                        if (fileId != null) {
                            coroutineScope {
                                listOf("lulustream.com", "luluvdo.com").map { domain ->
                                    async {
                                        loadExtractorWithCustomName("https://$domain/e/$fileId", qualityTag, subtitleCallback, callback)
                                    }
                                }.awaitAll()
                            }
                        } else {
                            loadExtractorWithCustomName(rawLink, qualityTag, subtitleCallback, callback)
                        }
                    }

                    rawLink.contains("playmogo", ignoreCase = true) ||
                    rawLink.contains("dood", ignoreCase = true) ||
                    rawLink.contains("myvidplay", ignoreCase = true) -> {
                        val fileId = Regex("""/(?:d|e)/([a-zA-Z0-9_-]+)""")
                            .find(rawLink)?.groupValues?.get(1)

                        if (fileId != null) {
                            coroutineScope {
                                listOf("dood.la", "doodstream.com").map { domain ->
                                    async {
                                        loadExtractorWithCustomName("https://$domain/e/$fileId", qualityTag, subtitleCallback, callback)
                                    }
                                }.awaitAll()
                            }
                        } else {
                            loadExtractorWithCustomName(rawLink, qualityTag, subtitleCallback, callback)
                        }
                    }

                    else -> {
                        loadExtractorWithCustomName(rawLink, qualityTag, subtitleCallback, callback)
                    }
                }
            }
        }
        return true
    }
}
