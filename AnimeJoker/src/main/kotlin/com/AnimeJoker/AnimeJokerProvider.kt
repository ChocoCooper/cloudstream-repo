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

        // 1. Gather Hardcoded Iframes
        doc.select("div.video iframe, iframe").forEach {
            it.attr("data-src").ifEmpty { it.attr("data-lazy-src") }.ifEmpty { it.attr("src") }.let { src ->
                if (src.isNotBlank()) collectedIframes.add(src)
            }
        }

        // 2. Gather Hidden AJAX Iframes
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

            // Step 1: Push internal redirects to WebView instantly to bypass Cloudflare 403 drops
            if (targetUrl.contains("?trembed=")) {
                try {
                    val wvUrl = withTimeoutOrNull(15000L) {
                        app.get(
                            targetUrl,
                            interceptor = WebViewResolver(Regex("""api/v1/info\?id=|cdntamilbulb\.online|parking\.godaddy|wsimg\.com""")),
                            referer = data
                        ).url
                    }
                    
                    // If WebView caught the info API or a dead domain, use it. Otherwise, standard fallback.
                    if (wvUrl != null && (wvUrl.contains("api/v1/info") || wvUrl.contains("cdntamilbulb") || wvUrl.contains("godaddy") || wvUrl.contains("wsimg"))) {
                        targetUrl = wvUrl
                    } else {
                        val res = app.get(targetUrl, referer = data)
                        if (res.url != targetUrl && !res.url.contains("?trembed=")) {
                            targetUrl = res.url
                        } else {
                            val innerIframe = res.document.selectFirst("iframe")?.let {
                                it.attr("data-src").ifEmpty { it.attr("src") }
                            }
                            if (!innerIframe.isNullOrBlank()) {
                                targetUrl = if (innerIframe.startsWith("//")) "https:$innerIframe" else innerIframe
                            }
                        }
                    }
                } catch (e: Exception) {
                    continue 
                }
            }

            // Step 2: Drop dead ad networks immediately
            if (targetUrl.contains("cdntamilbulb.online") || targetUrl.contains("parking.godaddy") || targetUrl.contains("wsimg.com")) {
                continue
            }

            // Step 3: Embedseek API Hijack (Eliminates Error 3002)
            if (targetUrl.contains("embedseek")) {
                try {
                    val domain = Regex("""(https?://[^/]+)""").find(targetUrl)?.value ?: "https://jkrowl.embedseek.online"
                    
                    // If targetUrl is already the intercepted API link, use it!
                    val infoUrl = if (targetUrl.contains("api/v1/info")) {
                        targetUrl
                    } else {
                        val id = targetUrl.substringBefore("?").split("/", "#").last { it.isNotBlank() }
                        "$domain/api/v1/info?id=$id"
                    }
                    
                    // Fetch API (Cloudflare is already cleared by WebViewResolver at this point)
                    val infoResponse = app.get(infoUrl, referer = "$domain/", headers = mapOf("Accept" to "application/json")).text
                    val cleanInfoResponse = infoResponse.replace("\\/", "/")
                    
                    var finalM3u8 = Regex("""(https?://[^"'\s]+?\.m3u8[^"'\s]*)""").find(cleanInfoResponse)?.groupValues?.get(1)

                    if (finalM3u8 == null) {
                        val tokenMatches = Regex("""([a-fA-F0-9]{40,})""").findAll(cleanInfoResponse)
                        val token = tokenMatches.maxByOrNull { it.value.length }?.value

                        if (token != null) {
                            val playerUrl = "$domain/api/v1/player?t=$token"
                            val playerRes = app.get(playerUrl, referer = "$domain/", headers = mapOf("Accept" to "application/json")).text
                            
                            val cleanPlayerRes = playerRes.replace("\\/", "/")
                            finalM3u8 = Regex("""(https?://[^"'\s]+?\.m3u8[^"'\s]*)""").find(cleanPlayerRes)?.groupValues?.get(1)
                            
                            if (finalM3u8 == null) {
                                val b64Regex = Regex("""([A-Za-z0-9+/=]{40,})""")
                                for (match in b64Regex.findAll(cleanPlayerRes)) {
                                    try {
                                        val decoded = String(Base64.decode(match.value, Base64.DEFAULT))
                                        val cleanDecoded = decoded.replace("\\/", "/")
                                        val decodedM3u8 = Regex("""(https?://[^"'\s]+?\.m3u8[^"'\s]*)""").find(cleanDecoded)?.groupValues?.get(1)
                                        if (decodedM3u8 != null) {
                                            finalM3u8 = decodedM3u8
                                            break
                                        }
                                    } catch (e: Exception) {}
                                }
                            }
                        }
                    }
                    
                    if (finalM3u8 != null) {
                        callback(
                            ExtractorLink(
                                source = "Embedseek",
                                name = "Embedseek HD",
                                url = finalM3u8.replace("\\", ""),
                                referer = "$domain/",
                                quality = Qualities.Unknown.value,
                                type = ExtractorLinkType.M3U8
                            )
                        )
                        foundLinks = true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                foundLinks = loadExtractor(targetUrl, data, subtitleCallback, callback) || foundLinks
            }
        }
        
        return foundLinks
    }
}
