package com.KRX18

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

/**
 * Krx18 Provider – fetches NSFW movies and uses AJAX to get server embed links.
 * Servers are handled by separate ExtractorApi implementations (PlayKrx18Extractor,
 * Mov18plusExtractor, LoadvidExtractor) which are auto‑discovered.
 */
class KRX18Provider : MainAPI() {
    override var mainUrl = "https://krx18.com"
    override var name = "KRX18"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "genre/korea" to "Korea",
        "genre/japan" to "Japan",
        "genre/philippines" to "Philippines",
        "genre/eng-sub" to "English SUB"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/?page/$page").document
        val home = document.select("#archive-content article, div.items.normal article")
            .map { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("h3").text()
        val href = fixUrl(this.select("h3 a").attr("href"))
        val posterUrl = fixUrlNull(this.select("img").attr("src"))
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search/videos?search_query=$query").document
        return document.select("div.card.border-0").map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.data h1")?.text() ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: ""
        val tags = document.select("div:nth-child(2) > div.video-details__item_links a").map { it.text() }
        val description = document.selectFirst("div.wp-content p")?.text()?.trim() ?: "No description"
        val recommendations = document.select("div.card.border-0").map { it.toSearchResult() }

        // ----- Extract server embed URLs via AJAX -----
        val embedLinks = mutableListOf<String>()
        document.select("ul#playeroptionsul li").forEach { li ->
            val post = li.attr("data-post")
            val nume = li.attr("data-nume")
            val type = li.attr("data-type")
            if (post.isNotEmpty() && nume.isNotEmpty() && type.isNotEmpty()) {
                try {
                    val response = app.post(
                        url = "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "doo_player_ajax",
                            "post" to post,
                            "nume" to nume,
                            "type" to type
                        ),
                        referer = mainUrl,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).parsed<ResponseHash>()

                    val embedUrl = response.embed_url
                    if (!embedUrl.isNullOrEmpty() && !embedLinks.contains(embedUrl)) {
                        embedLinks.add(embedUrl)
                    }
                } catch (_: Exception) {
                    // Skip failed server
                }
            }
        }

        // ----- Fallback: direct iframes or server spans (if AJAX fails) -----
        if (embedLinks.isEmpty()) {
            // Direct iframe (default player)
            document.select("div.player-iframe iframe, div#player iframe, div[class*='player'] iframe")
                .forEach { iframe ->
                    val src = iframe.attr("abs:src")
                    if (src.isNotEmpty() && !src.contains("about:blank")) {
                        embedLinks.add(src)
                    }
                }

            // Server spans with data‑link / onclick
            document.select("span.server, div.server, [class*='server']").forEach { elem ->
                // Check data‑* attributes
                for (attr in listOf("data-link", "data-src", "data-url", "data-href")) {
                    val value = elem.attr(attr)
                    if (value.isNotEmpty()) {
                        embedLinks.add(value)
                        break
                    }
                }
                // Check onclick for embedded URL
                val onclick = elem.attr("onclick")
                if (onclick.isNotEmpty()) {
                    Regex("""['"](https?://[^'"]+)['"]""").find(onclick)?.groupValues?.get(1)?.let {
                        embedLinks.add(it)
                    }
                }
            }
        }

        // Store all embed URLs in episode data (separated by |||)
        val episodeData = embedLinks.distinct().joinToString("|||")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
            this.tags = tags
            this.episodes = listOf(
                newEpisode() {
                    this.name = "Movie"
                    this.posterUrl = poster
                    this.data = episodeData
                }
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrls = data.split("|||").filter { it.isNotEmpty() }
        embedUrls.forEach { embedUrl ->
            // The loadExtractor function will automatically pick the right ExtractorApi
            // based on the URL domain (PlayKrx18Extractor, Mov18plusExtractor, LoadvidExtractor, etc.)
            loadExtractor(embedUrl, subtitleCallback, callback)
        }
        return true
    }

    // ----- JSON response from AJAX -----
    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String?,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}
