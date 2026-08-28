package com.Xmaza

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.util.Comparator
import java.util.regex.Pattern

class XmazaProvider : MainAPI() {
    override var mainUrl = "https://xmaza2.net"
    override var name = "Xmaza"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries)

    private val mirrors = listOf(
        "https://ottdude.com",
        "https://maalvdo.net",
        "https://xmaza.gg",
        "https://zmaal.net",
        "https://xmaza2.net"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/ott/ullu" to "ULLU",
        "$mainUrl/ott/atrangii" to "Atrangii",
        "$mainUrl/ott/primeplay" to "PrimePlay",
        "$mainUrl/ott/voovi" to "Voovi"
    )

    /** Helper to strip spaces/special characters for aggressive deduplication */
    private fun normalizeTitle(title: String): String {
        return title.lowercase().replace(Regex("[^a-z0-9]"), "")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("a.group.block").mapNotNull {
            it.toSearchResult(request.data)
        }
        return HomePageResponse(
            listOf(HomePageList(request.name, home, isHorizontalImages = true))
        )
    }

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val title = this.selectFirst("h4, h2.vtitle")?.text()?.trim() ?: this.attr("title")
        val href = this.attr("href")
        val url = if (href.startsWith("/")) baseUrl + href else href

        var poster = this.selectFirst("img")?.attr("src")
            ?: this.attr("style").substringAfter("url('").substringAfter("url(\"").substringBefore("')").substringBefore("\")")

        // Fix Next.js image routing
        if (poster.startsWith("/_next")) {
            poster = "$mainUrl$poster"
        }

        if (title.isBlank() || url.isBlank()) return null

        // Qualified with "this." to force resolution to the MainAPI extension
        // overload instead of the ambiguous top-level one.
        return this@XmazaProvider.newTvSeriesSearchResponse(
            name = title,
            url = url,
            type = TvType.TvSeries
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableMapOf<String, SearchResponse>()
        val seenTitles = mutableSetOf<String>()

        // Concurrently search all 5 sites
        coroutineScope {
            mirrors.map { site ->
                async {
                    try {
                        val searchUrl = if (site.contains("xmaza2")) "$site/search/$query" else "$site/?s=$query"
                        val document = app.get(searchUrl).document

                        document.select("a.video, a.group.block, article a.link").forEach { element ->
                            val parsed = element.toSearchResult(site)
                            if (parsed != null) {
                                val normTitle = normalizeTitle(parsed.name)
                                val slug = parsed.url.trimEnd('/').substringAfterLast("/")

                                // Deduplicate across mirrors by normalized title
                                if (normTitle.isNotBlank() && !seenTitles.contains(normTitle)) {
                                    seenTitles.add(normTitle)
                                    results[slug] = parsed
                                }
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }
        return results.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // 1. Fetch clicked episode page to find the main Series link
        val epDoc = app.get(url).document

        var seriesUrl: String? = null
        for (a in epDoc.select("a")) {
            val href = a.attr("href")
            val pathParts = href.substringAfter("://").substringAfter("/").split("/").filter { it.isNotBlank() }

            // Check if it's a series link AND it's not just the generic root directory "/series/"
            if ((pathParts.contains("series") || pathParts.contains("web-series")) && pathParts.size > 1) {
                seriesUrl = if (href.startsWith("/")) {
                    val base = url.substringBefore("/", url.indexOf("//") + 2)
                    base + href
                } else href
                break
            }
        }

        if (seriesUrl == null) return null

        // 2. Fetch the actual series page
        val seriesDoc = app.get(seriesUrl).document
        val title = seriesDoc.selectFirst("h1, h2")?.text()?.trim() ?: "Unknown Series"
        val poster = seriesDoc.selectFirst("img")?.attr("src")

        // 3. Extract episodes
        val episodesList = mutableListOf<Episode>()
        val seenEpTitles = mutableSetOf<String>()

        seriesDoc.select("a.video, a.group.block, article a.link").forEach { element ->
            val epTitle = element.selectFirst("h4, h2.vtitle")?.text()?.trim() ?: element.attr("title")
            val epHref = element.attr("href")
            val epUrl = if (epHref.startsWith("/")) {
                val base = seriesUrl.substringBefore("/", seriesUrl.indexOf("//") + 2)
                base + epHref
            } else epHref

            var epPoster = element.selectFirst("img")?.attr("src")
                ?: element.attr("style").substringAfter("url('").substringAfter("url(\"").substringBefore("')").substringBefore("\")")
            if (epPoster.startsWith("/_next")) {
                epPoster = "$mainUrl$epPoster"
            }

            if (epTitle.isNotBlank() && epUrl.isNotBlank()) {
                val normEpTitle = normalizeTitle(epTitle)
                val epSlug = epUrl.trimEnd('/').substringAfterLast("/")

                if (!seenEpTitles.contains(normEpTitle)) {
                    seenEpTitles.add(normEpTitle)
                    episodesList.add(
                        newEpisode(epSlug) { // data = slug, passed to loadLinks
                            this.name = epTitle
                            this.posterUrl = epPoster
                        }
                    )
                }
            }
        }

        // Sort episodes naturally (Episode 1, Episode 2, ... Episode 10)
        val sortedEpisodes = episodesList.sortedWith(AlphanumComparator())

        return this@XmazaProvider.newTvSeriesLoadResponse(
            name = title,
            url = url,
            type = TvType.TvSeries,
            episodes = sortedEpisodes
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val slug = data

        // Generate Mirror Links using the extracted base slug
        val mirrorUrls = mapOf(
            "OTT Dude (Primary)" to "https://ottdude.com/$slug/",
            "MaalVDO" to "https://maalvdo.net/$slug/",
            "XMaza" to "https://xmaza.gg/$slug/",
            "ZMaal" to "https://zmaal.net/$slug/",
            "XMaza2 (Alt CDN)" to "https://xmaza2.net/watch/$slug"
        )

        // Concurrently try to resolve video from each mirror
        coroutineScope {
            mirrorUrls.map { (sourceName, mirrorUrl) ->
                async {
                    try {
                        val html = app.get(mirrorUrl).text
                        // Regex to extract direct mp4/m3u8 URLs natively embedded in the HTML
                        val pattern = "(?i)(?:src|file|source)\\s*[:=]\\s*[\"'](https?://[^\"']+\\.(?:mp4|m3u8)[^\"']*)[\"']".toRegex()

                        pattern.findAll(html).forEach { matchResult ->
                            val videoUrl = matchResult.groupValues[1]
                            val isM3u8 = videoUrl.contains(".m3u8")

                            callback.invoke(
                                newExtractorLink(
                                    source = sourceName,
                                    name = sourceName,
                                    url = videoUrl,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = mirrorUrl // Included for future-proofing against hotlink protection
                                    this.quality = if (isM3u8) Qualities.Unknown.value else Qualities.P1080.value
                                }
                            )
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }.awaitAll()
        }

        return true
    }

    /**
     * Natural Sort Comparator for logical ordering of episodes
     * Ensures "Episode 2" comes before "Episode 10"
     */
    class AlphanumComparator : Comparator<Episode> {
        override fun compare(s1: Episode, s2: Episode): Int {
            val name1 = s1.name ?: ""
            val name2 = s2.name ?: ""
            val p = Pattern.compile("(\\d+)|(\\D+)")
            val m1 = p.matcher(name1)
            val m2 = p.matcher(name2)
            while (m1.find() && m2.find()) {
                val tok1 = m1.group()
                val tok2 = m2.group()
                val cmp = if (tok1.matches("\\d+".toRegex()) && tok2.matches("\\d+".toRegex())) {
                    tok1.toLong().compareTo(tok2.toLong())
                } else {
                    tok1.compareTo(tok2, ignoreCase = true)
                }
                if (cmp != 0) return cmp
            }
            return name1.length.compareTo(name2.length)
        }
    }
}
