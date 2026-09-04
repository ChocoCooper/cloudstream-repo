package com.KRX18

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Krx18Provider : MainAPI() {
    override var mainUrl = "https://krx18.com"
    override var name = "KRX 18"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val vpnStatus = VPNStatus.MightBeNeeded
    override val supportedTypes = setOf(TvType.NSFW)

    // Common selector for articles on both main page and search results
    private val articleSelector = "#archive-content article, div.items.normal article, div#content article, article.post"

    override val mainPage = mainPageOf(
        "movies" to "Recently added",
        "genre/eng-sub" to "English SUB",
        "genre/korea" to "Korea",
        "genre/china" to "China",
        "genre/japan" to "Japan",
        "genre/thailand" to "Thailand",
        "genre/philippines" to "Philippines"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = "$mainUrl/${request.data}/?page/$page"
        println("MainPage URL: $url")
        val document = app.get(url).document
        val articles = document.select(articleSelector)
        println("Found ${articles.size} articles on main page")
        val home = articles.mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            val linkEl = this.selectFirst("h3 a") ?: this.selectFirst("a")
            val href = linkEl?.attr("abs:href") ?: return null
            val title = linkEl.text().ifEmpty { this.selectFirst("img")?.attr("alt") ?: "Unknown" }
            val posterUrl = this.selectFirst("img")?.attr("abs:src")
            return newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } catch (e: Exception) {
            println("Error parsing article: ${e.message}")
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${query.replace(" ", "+")}"
        println("Search URL: $url")
        val document = app.get(url).document
        val articles = document.select(articleSelector)
        println("Found ${articles.size} search results")
        return articles.mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        println("Loading URL: $url")
        val document = app.get(url).document

        val title = document.selectFirst("div.data h1")?.text()
            ?: document.selectFirst("h1.entry-title")?.text()
            ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
            ?: document.selectFirst("div.post-thumbnail img")?.attr("abs:src")
            ?: ""
        val tags = document.select("div:nth-child(2) > div.video-details__item_links a, .tags-links a")
            .map { it.text() }
        val description = document.selectFirst("div.wp-content p, div.entry-content p")?.text()?.trim()
            ?: "No description"
        val recommendations = document.select(articleSelector).mapNotNull { it.toSearchResult() }

        // ----- Extract server embed URLs via AJAX (primary) -----
        val embedLinks = mutableSetOf<String>()
        val serverItems = document.select("ul#playeroptionsul li, ul.servers-list li, div.servers-list li, div.server-item")
        println("Found ${serverItems.size} server items")

        serverItems.forEach { li ->
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

                    val embedUrl = response.embed_url?.let { fixUrl(it) }
                    if (!embedUrl.isNullOrEmpty()) {
                        embedLinks.add(embedUrl)
                        println("AJAX embed: $embedUrl")
                    }
                } catch (e: Exception) {
                    println("AJAX request failed for $post/$nume: ${e.message}")
                }
            }
        }

        // ----- Fallback: direct iframes or data attributes -----
        if (embedLinks.isEmpty()) {
            println("No AJAX links found, trying fallback")
            // Direct iframe from player container
            document.select("div.player-iframe iframe, div#player iframe, div[class*='player'] iframe, div.embed-responsive iframe")
                .forEach { iframe ->
                    val src = iframe.attr("abs:src")
                    if (src.isNotEmpty() && !src.contains("about:blank")) {
                        embedLinks.add(src)
                        println("Fallback iframe: $src")
                    }
                }

            // Server spans with data-link / onclick
            document.select("span.server, div.server, [class*='server']").forEach { elem ->
                for (attr in listOf("data-link", "data-src", "data-url", "data-href", "data-embed")) {
                    val value = elem.attr(attr)
                    if (value.isNotEmpty()) {
                        val fixed = fixUrl(value)
                        if (fixed.isNotEmpty()) {
                            embedLinks.add(fixed)
                            println("Fallback data-attr: $fixed")
                            break
                        }
                    }
                }
                val onclick = elem.attr("onclick")
                if (onclick.isNotEmpty()) {
                    Regex("""['"](https?://[^'"]+)['"]""").find(onclick)?.groupValues?.get(1)?.let {
                        embedLinks.add(fixUrl(it))
                        println("Fallback onclick: $it")
                    }
                }
            }

            // Also check for video source elements (sometimes directly embedded)
            document.select("video source, source[src]").forEach { srcEl ->
                val src = srcEl.attr("abs:src")
                if (src.isNotEmpty()) {
                    embedLinks.add(src)
                    println("Fallback video source: $src")
                }
            }
        }

        // Store distinct embed URLs in the `data` field (separated by |||)
        val responseData = embedLinks.joinToString("|||")
        println("Total embed links extracted: ${embedLinks.size}")

        return newMovieLoadResponse(title, url, TvType.NSFW, responseData) {
            this.posterUrl = poster
            this.plot = description
            this.recommendations = recommendations
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrls = data.split("|||").filter { it.isNotEmpty() }
        println("loadLinks: processing ${embedUrls.size} embed URLs")
        if (embedUrls.isEmpty()) {
            println("No embed URLs found in data")
            return false
        }

        var success = false
        embedUrls.forEach { embedUrl ->
            try {
                // Attempt to load extractor for this URL
                val result = loadExtractor(embedUrl, subtitleCallback, callback)
                if (result) success = true
                println("loadExtractor for $embedUrl returned $result")
            } catch (e: Exception) {
                println("Error loading extractor for $embedUrl: ${e.message}")
            }
        }
        return success
    }

    // ----- JSON response from AJAX -----
    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String?,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null
    )

    // Helper to fix relative URLs
    private fun fixUrl(url: String): String {
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("http")) return url
        if (url.startsWith("/")) return "$mainUrl$url"
        return url
    }
}
