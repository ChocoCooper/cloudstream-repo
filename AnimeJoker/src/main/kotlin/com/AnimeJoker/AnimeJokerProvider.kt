package com.AnimeJoker

import android.util.Base64
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
                    targetUrl = if (res.url != targetUrl && !res.url.contains("?trembed=")) {
                        res.url
                    } else {
                        val innerIframe = res.document.selectFirst("iframe")?.let {
                            it.attr("data-src").ifEmpty { it.attr("src") }
                        }
                        if (!innerIframe.isNullOrBlank()) {
                            if (innerIframe.startsWith("//")) "https:$innerIframe" else innerIframe
                        } else {
                            targetUrl
                        }
                    }
                } catch (e: Exception) {
                    continue
                }
            }

            // Drop dead ad/parking networks immediately
            if (targetUrl.contains("cdntamilbulb.online") || targetUrl.contains("parking.godaddy") || targetUrl.contains("wsimg.com")) {
                continue
            }

            // Step 2: Embedseek API Token Hijack
            if (targetUrl.contains("embedseek")) {
                try {
                    val domain = Regex("""(https?://[^/]+)""").find(targetUrl)?.value ?: "https://jkrowl.embedseek.online"
                    val id = targetUrl.substringBefore("?").split("/", "#").last { it.isNotBlank() }
                    val infoUrl = "$domain/api/v1/info?id=$id"

                    var infoResponse = ""
                    try {
                        infoResponse = app.get(infoUrl, referer = targetUrl, headers = mapOf("Accept" to "application/json")).text
                    } catch (e: Exception) {}

                    // If Cloudflare blocked the GET request (returns HTML), use WebView to clear the captcha.
                    // We only wait for the `api/v1/info` request because it happens automatically (no play click required).
                    if (infoResponse.isBlank() || infoResponse.trim().startsWith("<html", ignoreCase = true) || infoResponse.contains("Cloudflare")) {
                        try {
                            withTimeoutOrNull(20000L) {
                                app.get(targetUrl, interceptor = WebViewResolver(Regex("""api/v1/(info|player)""")), referer = data)
                            }
                            // Cloudflare should now be cleared, fetch the API properly
                            infoResponse = app.get(infoUrl, referer = targetUrl, headers = mapOf("Accept" to "application/json")).text
                        } catch (e: Exception) {}
                    }

                    // Clean JSON escaping
                    val cleanInfoResponse = infoResponse.replace("\\/", "/")
                    
                    // Look strictly for JSON keys (file, hls, url) to avoid false-positive Yandex analytics tracking links
                    val mediaRegex = Regex(""""(?:file|hls|url)"\s*:\s*"([^"]+\.(?:m3u8|mp4)[^"]*)"""", RegexOption.IGNORE_CASE)
                    var finalM3u8 = mediaRegex.find(cleanInfoResponse)?.groupValues?.get(1)

                    if (finalM3u8 == null) {
                        // Extract token if direct link isn't provided
                        val tokenMatches = Regex("""([a-fA-F0-9]{40,})""").findAll(cleanInfoResponse)
                        val token = tokenMatches.maxByOrNull { it.value.length }?.value

                        if (token != null) {
                            val playerUrl = "$domain/api/v1/player?t=$token"
                            val playerRes = app.get(playerUrl, referer = "$domain/", headers = mapOf("Accept" to "application/json")).text
                            
                            val cleanPlayerRes = playerRes.replace("\\/", "/")
                            finalM3u8 = mediaRegex.find(cleanPlayerRes)?.groupValues?.get(1)
                            
                            if (finalM3u8 == null) {
                                // If token data is Base64 encoded, decode it and search again
                                val b64Regex = Regex("""([A-Za-z0-9+/=]{40,})""")
                                for (match in b64Regex.findAll(cleanPlayerRes)) {
                                    try {
                                        val decoded = String(Base64.decode(match.value, Base64.DEFAULT))
                                        val cleanDecoded = decoded.replace("\\/", "/")
                                        val decodedM3u8 = mediaRegex.find(cleanDecoded)?.groupValues?.get(1)
                                        if (decodedM3u8 != null) {
                                            finalM3u8 = decodedM3u8
                                            break
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    }

                    // Step 3: Pass ONLY the clean media string to Cloudstream, avoiding Error 3002
                    if (finalM3u8 != null) {
                        callback(
                            ExtractorLink(
                                source = "Embedseek",
                                name = "Embedseek HD",
                                url = finalM3u8,
                                referer = "$domain/",
                                quality = Qualities.Unknown.value,
                                type = if (finalM3u8.contains(".mp4")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                            )
                        )
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                // Step 4: Standard stream hosts
                foundLinks = loadExtractor(targetUrl, data, subtitleCallback, callback) || foundLinks
            }
        }

        return foundLinks
    }
}
