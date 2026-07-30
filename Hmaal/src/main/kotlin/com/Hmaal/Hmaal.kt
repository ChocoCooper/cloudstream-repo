package com.Hmaal

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element

class Hmaal : MainAPI() {
    override var mainUrl              = "https://hmaal.tv"
    override var name                 = "Hmaal"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val requestHeaders = mapOf("User-Agent" to USER_AGENT)

    override val mainPage = mainPageOf(
        "ott/ullu/" to "Ullu",
        "ott/atrangii/" to "Atrangii",
        "ott/primeplay/" to "Primeplay",
        "ott/voovi/" to "Voovi",
        "ott/jugnu/" to "Jugnu",
    )

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.attr("title").ifEmpty { this.text() }.trim()
        if (title.isEmpty()) return null

        var posterUrl: String? = null
        val styleAttr = this.attr("style")
        if (styleAttr.contains("url(")) {
            val match = Regex("""url\(['"]?(.*?)['"]?\)`""").find(styleAttr)
            if (match != null) {
                posterUrl = fixUrlNull(match.groupValues[1])
            }
        }
        if (posterUrl == null) {
            posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.trimEnd('/')
        val url = if (page <= 1) "$mainUrl/$path/" else "$mainUrl/$path/page/$page/"

        val home = try {
            val document = app.get(url, headers = requestHeaders, timeout = 30L).document
            document.select("#primary > div > a.video, #primary > div > a").mapNotNull { it.toSearchResult() }
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

    override suspend fun search(query: String): List<SearchResponse> {
        val searchDomains = listOf(
            "https://hmaal.tv",
            "https://hdmaal.io",
            "https://hotmaal.xxx"
        )

        val allResults = mutableListOf<SearchResponse>()
        val seenTitles = mutableSetOf<String>()

        coroutineScope {
            searchDomains.map { domain ->
                async {
                    try {
                        val searchUrl = "$domain/?s=$query"
                        val document = app.get(searchUrl, headers = requestHeaders, timeout = 30L).document
                        document.select("#primary > div > a.video, #primary > div > a").mapNotNull { it.toSearchResult() }
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().forEach { list ->
                list.forEach { item ->
                    val normTitle = item.name.lowercase().trim()
                    if (seenTitles.add(normTitle)) {
                        allResults.add(item)
                    }
                }
            }
        }

        return allResults
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = requestHeaders, timeout = 30L).document
        val pageTitle = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.title().trim().ifEmpty { "Unknown" }

        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("#primary > div > a.video")?.let { el ->
                val style = el.attr("style")
                Regex("""url\(['"]?(.*?)['"]?\)`""").find(style)?.groupValues?.get(1)
            })

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val episodes = mutableListOf<Episode>()

        val seriesLinkElement = document.selectFirst("#primary > div.taxonomy-meta > div.series-list > a")
        val seriesUrl = fixUrlNull(seriesLinkElement?.attr("href"))
        val seriesName = seriesLinkElement?.text()?.trim() ?: pageTitle.substringBefore("Episode").trim()

        if (!seriesUrl.isNullOrEmpty()) {
            try {
                val seriesDoc = app.get(seriesUrl, headers = requestHeaders, timeout = 30L).document
                val episodeElements = seriesDoc.select("#primary > div > a.video, #primary > div > a")

                episodeElements.forEach { epElem ->
                    val epHref = fixUrlNull(epElem.attr("href")) ?: return@forEach
                    val epTitleAttr = epElem.attr("title").ifEmpty { epElem.text() }.trim()

                    val epNum = Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE).find(epTitleAttr)?.groupValues?.get(1)
                        ?: epTitleAttr.takeLastWhile { it.isDigit() }.ifEmpty { null }

                    val formattedName = if (epNum != null) {
                        "$seriesName Episode $epNum"
                    } else {
                        epTitleAttr.ifEmpty { "Episode 1" }
                    }

                    var epPoster: String? = null
                    val styleAttr = epElem.attr("style")
                    if (styleAttr.contains("url(")) {
                        val match = Regex("""url\(['"]?(.*?)['"]?\)`""").find(styleAttr)
                        if (match != null) {
                            epPoster = fixUrlNull(match.groupValues[1])
                        }
                    }

                    episodes.add(newEpisode(epHref) {
                        this.name = formattedName
                        this.posterUrl = epPoster
                    })
                }
            } catch (e: Exception) {
                // Ignore series fetch failure and fall back
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(newEpisode(url) {
                this.name = pageTitle
                this.posterUrl = poster
            })
        }

        return newTvSeriesLoadResponse(if (seriesName.isNotEmpty()) seriesName else pageTitle, url, TvType.TvSeries, episodes) {
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
        val uri = try {
            java.net.URI(data)
        } catch (e: Exception) {
            null
        }

        val path = uri?.path ?: data.substringAfter("://").substringAfter("/")
        val cleanPath = if (path.startsWith("/")) path else "/$path"

        val targetDomains = listOf(
            "https://hmaal.tv",
            "https://hdmaal.io",
            "https://hotmaal.xxx"
        )

        coroutineScope {
            targetDomains.map { domain ->
                async {
                    val fullUrl = "$domain$cleanPath"
                    try {
                        val response = app.get(fullUrl, headers = requestHeaders, timeout = 15L)
                        if (response.code == 200) {
                            val document = response.document
                            val domainHost = domain.substringAfter("://")

                            val videoSources = document.select("#my-video source[src], video source[src], source[src]")
                            videoSources.forEach { sourceElem ->
                                val videoUrl = fixUrlNull(sourceElem.attr("src"))
                                if (!videoUrl.isNullOrEmpty()) {
                                    val qualityLabel = when {
                                        videoUrl.contains("1080") -> "1080p"
                                        videoUrl.contains("720") -> "720p"
                                        videoUrl.contains("480") -> "480p"
                                        else -> "MP4"
                                    }

                                    callback.invoke(
                                        newExtractorLink(
                                            source = "Hmaal ($domainHost)",
                                            name = "Hmaal $qualityLabel ($domainHost)",
                                            url = videoUrl,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = "$domain/"
                                            this.quality = when {
                                                qualityLabel.contains("1080") -> Qualities.P1080.value
                                                qualityLabel.contains("720") -> Qualities.P720.value
                                                qualityLabel.contains("480") -> Qualities.P480.value
                                                else -> Qualities.Unknown.value
                                            }
                                        }
                                    )
                                }
                            }

                            document.select("iframe[src]").forEach { iframe ->
                                val iframeUrl = fixUrlNull(iframe.attr("src"))
                                if (!iframeUrl.isNullOrEmpty()) {
                                    loadExtractor(iframeUrl, subtitleCallback, callback)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Silently handle 404 / connection errors per mirror domain
                    }
                }
            }.awaitAll()
        }

        return true
    }
}
