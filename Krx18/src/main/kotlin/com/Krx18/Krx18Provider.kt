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
        val document = app.get("$mainUrl/${request.data}/?page/$page").document
        val home = document.select("article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = true
        )
    }

    // Exact parsing logic matching the tested DOM structure
    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst("a") ?: return null
        val imgTag = aTag.selectFirst("img") ?: this.selectFirst("img")
        
        val title = imgTag?.attr("alt")?.takeIf { it.isNotBlank() } 
            ?: aTag.attr("title").takeIf { it.isNotBlank() } 
            ?: this.selectFirst("h3")?.text() 
            ?: "Unknown Title"
            
        val href = fixUrl(aTag.attr("href"))
        val posterUrl = fixUrlNull(imgTag?.attr("src"))
        
        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Native WordPress search endpoint
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.data h1")?.text() ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")?.trim() ?: ""
        val tags = document.select("div:nth-child(2) > div.video-details__item_links a").map { it.text() }
        val description = document.selectFirst("div.wp-content p")?.text()?.trim() ?: "No description"
        val recommendations = document.select("article").mapNotNull { it.toSearchResult() }

        val embedLinks = mutableListOf<String>()
        
        // 1. Hunt via AJAX (For hidden dynamic servers)
        document.select("ul#playeroptionsul li").forEach { li ->
            val post = li.attr("data-post")
            val nume = li.attr("data-nume")
            val type = li.attr("data-type")
            if (post.isNotEmpty() && nume.isNotEmpty() && type.isNotEmpty()) {
                try {
                    val response = app.post(
                        url = "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf("action" to "doo_player_ajax", "post" to post, "nume" to nume, "type" to type),
                        referer = mainUrl,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).parsed<ResponseHash>()

                    val embedUrl = response.embed_url
                    if (!embedUrl.isNullOrEmpty() && !embedLinks.contains(embedUrl)) {
                        embedLinks.add(embedUrl)
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Fallback: Hunt direct iframes (For pre-embedded movies)
        if (embedLinks.isEmpty()) {
            document.select("div.player-iframe iframe, div#player iframe, div[class*='player'] iframe").forEach { iframe ->
                val src = iframe.attr("abs:src")
                if (src.isNotEmpty() && !src.contains("about:blank")) embedLinks.add(src)
            }
            document.select("span.server, div.server").forEach { elem ->
                listOf("data-link", "data-src", "data-url", "data-href").forEach { attr ->
                    val value = elem.attr(attr)
                    if (value.isNotEmpty()) { embedLinks.add(value); return@forEach }
                }
            }
        }

        val responseData = embedLinks.distinct().joinToString("|||")

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
        embedUrls.forEach { embedUrl ->
            loadExtractor(embedUrl, subtitleCallback, callback)
        }
        return true
    }

    data class ResponseHash(
        @JsonProperty("embed_url") val embed_url: String?,
        @JsonProperty("key") val key: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}
