package com.AnimeJoker

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.withTimeoutOrNull
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

        val seriesElements = doc.select("#widget_list_movies_series-2-all ul li").take(8)
        if (seriesElements.isNotEmpty()) {
            home.add(HomePageList("Series", seriesElements.mapNotNull { toSearchResult(it) }))
        }

        val movieElements = doc.select("#widget_list_movies_series-3-all ul li").take(8)
        if (movieElements.isNotEmpty()) {
            home.add(HomePageList("Movies", movieElements.mapNotNull { toSearchResult(it) }))
        }

        return newHomePageResponse(home)
    }

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
        val collectedIframes = mutableSetOf<String>()

        // 1. Gather hardcoded iframes
        doc.select("div.video iframe, iframe").forEach {
            it.attr("data-src").ifEmpty { it.attr("data-lazy-src") }.ifEmpty { it.attr("src") }.let { src ->
                if (src.isNotBlank()) collectedIframes.add(src)
            }
        }

        // 2. Gather hidden AJAX iframes
        doc.select("[data-post][data-nume][data-type]").forEach { server ->
            val post = server.attr("data-post")
            val nume = server.attr("data-nume")
            val type = server.attr("data-type")

            if (post.isNotBlank()) {
                try {
                    val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
                    val res = app.post(
                        url = ajaxUrl,
                        data = mapOf("action" to "doo_player", "post" to post, "nume" to nume, "type" to type),
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).text

                    val cleanRes = res.replace("\\/", "/").replace("\\\"", "\"")
                    Regex("""src=["']([^"']+)["']""").find(cleanRes)?.groupValues?.get(1)?.let {
                        collectedIframes.add(it)
                    }
                } catch (e: Exception) {}
            }
        }

        for (link in collectedIframes) {
            var targetUrl = if (link.startsWith("//")) "https:$link" else link

            // Step 1: Resolve the ?trembed= wrapper page to the actual server iframe
            if (targetUrl.contains("?trembed=")) {
                try {
                    val res = app.get(targetUrl, referer = data)
                    
                    val innerIframe = res.document.selectFirst("iframe")?.let {
                        it.attr("data-src").ifEmpty { it.attr("src") }
                    } ?: Regex("""(https?://[^"'\s]*?embedseek[^"'\s]*)""").find(res.text)?.value

                    if (!innerIframe.isNullOrBlank()) {
                        targetUrl = if (innerIframe.startsWith("//")) "https:$innerIframe" else innerIframe
                    } else if (res.url != targetUrl && !res.url.contains("?trembed=")) {
                        targetUrl = res.url
                    }
                } catch (e: Exception) {
                    // Fallback to WebView if Cloudflare 403 blocks the trembed request
                    try {
                        val wvRes = app.get(targetUrl, interceptor = WebViewResolver(Regex("""embedseek|cdntamilbulb""")), referer = data)
                        targetUrl = wvRes.url
                    } catch (e2: Exception) {}
                }
            }

            // Drop dead ad/parking networks immediately to avoid hanging
            if (targetUrl.contains("cdntamilbulb.online") || targetUrl.contains("parking.godaddy") || targetUrl.contains("wsimg.com")) {
                continue
            }

            // Step 2: Embedseek AutoPlay Hijack
            if (targetUrl.contains("embedseek")) {
                try {
                    val domain = Regex("""(https?://[^/]+)""").find(targetUrl)?.value ?: "https://jkrowl.embedseek.online"

                    // Force Vidstack to load eagerly and autoplay. This bypasses the "Click to Play" requirement
                    // and forces the site's JS to decrypt the AES payload and request the master.m3u8 instantly!
                    val base = targetUrl.substringBefore("#")
                    val hash = if (targetUrl.contains("#")) "#" + targetUrl.substringAfter("#") else ""
                    val separator = if (base.contains("?")) "&" else "?"
                    val autoPlayUrl = "${base}${separator}autoplay=1&autoPlay=true&muted=true&preload=auto&load=eager$hash"

                    // Strict Regex: Requires .m3u8 or .mp4 to be the ACTUAL file extension, NOT a query parameter.
                    // This perfectly ignores Yandex/Google tracking beacons.
                    val mediaRegex = Regex("""^[^?]+\.(m3u8|mp4)(?:\?|$)""", RegexOption.IGNORE_CASE)

                    val mediaResponse = withTimeoutOrNull(25000L) {
                        app.get(
                            autoPlayUrl,
                            interceptor = WebViewResolver(mediaRegex),
                            referer = data
                        )
                    }

                    val finalUrl = mediaResponse?.url

                    if (finalUrl != null && mediaRegex.containsMatchIn(finalUrl)) {
                        callback(
                            ExtractorLink(
                                source = "Embedseek",
                                name = "Embedseek HD",
                                url = finalUrl,
                                referer = "$domain/",
                                quality = Qualities.Unknown.value,
                                type = if (finalUrl.contains(".mp4", ignoreCase = true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                            )
                        )
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                // Step 3: Standard external extractors
                foundLinks = loadExtractor(targetUrl, data, subtitleCallback, callback) || foundLinks
            }
        }

        return foundLinks
    }
}
