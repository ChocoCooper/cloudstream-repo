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
import java.net.URLDecoder
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

    /**
     * Resolves any image reference (relative path, protocol-relative url,
     * or a Next.js `/_next/image?url=...` resize-proxy path) down to a
     * real, directly-loadable image URL.
     *
     * Verified against live responses:
     *  - the mirror's own /_next/image proxy DOES work, but only when it
     *    has a full host attached to it. Passing a bare "/_next/image?..."
     *    path (no host) is what produced the Coil "ENOENT" errors, since
     *    the image loader tried to open it as a local file.
     *  - decoding straight to the upstream CDN url (e.g. cdn.hotmaal.cc/...)
     *    is more robust than depending on the mirror's proxy, because it
     *    keeps working even when the poster HTML came from a *different*
     *    mirror than `mainUrl`.
     */
    private fun fixImageUrl(raw: String?, pageUrl: String): String? {
        if (raw.isNullOrBlank()) return null
        val url = raw.trim()

        // Inline base64 placeholders used by lazy-loaders are never real images.
        if (url.startsWith("data:")) return null

        val protocolEnd = pageUrl.indexOf("//") + 2
        val domainRoot = pageUrl.substring(0, protocolEnd) + pageUrl.substring(protocolEnd).substringBefore("/")

        if (url.contains("/_next/image")) {
            val full = if (url.startsWith("http")) url else domainRoot + url
            val encoded = Regex("[?&]url=([^&]+)").find(full)?.groupValues?.get(1)
            if (encoded != null) {
                return try {
                    URLDecoder.decode(encoded, "UTF-8")
                } catch (e: Exception) {
                    full
                }
            }
            return full
        }

        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> domainRoot + url
            else -> url
        }
    }

    /** Pulls the best available image url off an <img> tag, preferring lazy-load attrs. */
    private fun Element.bestRawImg(): String? {
        val img = this.selectFirst("img") ?: return this.attr("style")
            .substringAfter("url('").substringAfter("url(\"")
            .substringBefore("')").substringBefore("\")")
            .ifBlank { null }
        return img.attr("data-src").ifBlank { img.attr("src") }.ifBlank { null }
            ?: this.attr("style").substringAfter("url('").substringAfter("url(\"")
                .substringBefore("')").substringBefore("\")").ifBlank { null }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("a.group.block").mapNotNull {
            it.toSearchResult(request.data)
        }
        return newHomePageResponse(
            HomePageList(request.name, home, isHorizontalImages = true)
        )
    }

    private fun Element.toSearchResult(baseUrl: String): SearchResponse? {
        val title = this.selectFirst("h4, h2.vtitle")?.text()?.trim() ?: this.attr("title")
        val href = this.attr("href")
        val url = if (href.startsWith("/")) baseUrl + href else href

        val poster = fixImageUrl(this.bestRawImg(), baseUrl)

        if (title.isBlank() || url.isBlank()) return null

        return newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
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
                    val protocolEnd = url.indexOf("//") + 2
                    val base = url.substring(0, protocolEnd) + url.substring(protocolEnd).substringBefore("/")
                    base + href
                } else href
                break
            }
        }

        if (seriesUrl == null) return null

        // 2. Fetch the actual series page
        val seriesDoc = app.get(seriesUrl).document
        val title = seriesDoc.selectFirst("h1, h2")?.text()?.trim() ?: "Unknown Series"

        // NOTE: this line was previously missing the /_next fix entirely, which is
        // what caused the Coil "ENOENT" errors from the logcat.
        val rawPoster = seriesDoc.selectFirst("img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        val poster = fixImageUrl(rawPoster, seriesUrl)

        // 3. Extract episodes
        val episodesList = mutableListOf<Episode>()
        val seenEpTitles = mutableSetOf<String>()

        seriesDoc.select("a.video, a.group.block, article a.link").forEach { element ->
            val epTitle = element.selectFirst("h4, h2.vtitle")?.text()?.trim() ?: element.attr("title")
            val epHref = element.attr("href")
            val epUrl = if (epHref.startsWith("/")) {
                val protocolEnd = seriesUrl.indexOf("//") + 2
                val base = seriesUrl.substring(0, protocolEnd) + seriesUrl.substring(protocolEnd).substringBefore("/")
                base + epHref
            } else epHref

            val epPoster = fixImageUrl(element.bestRawImg(), seriesUrl)

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

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
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
                                    this.referer = mirrorUrl // confirmed harmless/unneeded but kept for future-proofing
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
