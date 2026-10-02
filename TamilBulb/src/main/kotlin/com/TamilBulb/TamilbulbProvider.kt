package com.tamilbulb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.util.Base64

class TamilbulbProvider : MainAPI() {
    override var mainUrl = "https://tamilbulb.cc"
    override var name = "TamilBulb"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Movie)

    private val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                     "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    // -------------------- HOME --------------------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val home = mutableListOf<HomePageList>()
        listOf(
            "New Movies" to "/video-category/new1movies/",
            "HD Movies"  to "/video-category/movies/",
            "Dubbed"     to "/video-category/dmovie/",
            "Trending"   to "/video-category/trending/",
            "CAM"        to "/video-category/cam/",
        ).forEach { (label, path) ->
            val items = fetchCategory(path, page)
            if (items.isNotEmpty()) home.add(HomePageList(label, items))
        }
        return newHomePageResponse(home, hasNext = true)
    }

    private suspend fun fetchCategory(path: String, page: Int): List<SearchResponse> {
        val url = if (page <= 1) "$mainUrl$path" else "$mainUrl$path/page/$page/"
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        return doc.select("article.post-item").mapNotNull { it.toSearch() }
    }

    // -------------------- SEARCH --------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=${query.replace(" ", "+")}",
            headers = mapOf("User-Agent" to UA)).document
        return doc.select("article.post-item").mapNotNull { it.toSearch() }
    }

    private fun Element.toSearch(): SearchResponse? {
        val a = selectFirst("h3.entry-title a.post-listing-title") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        val title = (a.attr("title").ifBlank { a.text() }).trim().ifBlank { return null }
        val poster = selectFirst("img.blog-img")?.let {
            it.attr("data-src").ifBlank { it.attr("src") }
        }
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = poster }
    }

    // -------------------- LOAD --------------------
    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url, headers = mapOf("User-Agent" to UA)).document
        val title = doc.selectFirst("h1.entry-title, h1.single-post-title")
            ?.text()?.trim() ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[name=description]")?.attr("content")
        val year = Regex("""(19|20)\d{2}""").find(
            doc.selectFirst("span.post-footer-item")?.text() ?: ""
        )?.value?.toIntOrNull()
        val tags = doc.select("a.category-item").map { it.text().trim() }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    // -------------------- LOAD LINKS --------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = mapOf("User-Agent" to UA)).document

        // 1. Extract stream_id from the beeteam368_pro_player script
        val streamId = doc.select("script").mapNotNull { s ->
            val txt = s.data().takeIf { it.isNotBlank() }
                ?: s.html().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!txt.contains("beeteam368_pro_player")) return@mapNotNull null
            Regex("""index\.php\?id=([A-Za-z0-9_\-]+)""").find(txt)?.groupValues?.get(1)
        }.firstOrNull() ?: return false

        // 2. Build the /embed/<base64(stream_id + timestamp)> URL
        val ts = System.currentTimeMillis() / 1000
        val payload = Base64.getEncoder().encodeToString("$streamId$ts".toByteArray())
        val embedUrl = "$mainUrl/embed/$payload"

        // 3. Fetch the embed page and enumerate players
        val embedDoc = app.get(embedUrl, headers = mapOf(
            "User-Agent" to UA,
            "Referer"    to data
        )).document

        var any = false
        for (n in 1..10) {
            val item = embedDoc.selectFirst("#player$n") ?: continue
            val iframe = item.selectFirst(".player-wrapper iframe")?.attr("src")
                ?.takeIf { it.isNotBlank() } ?: continue

            val name = item.selectFirst(".player-name")?.text()?.trim() ?: "Player $n"
            any = true

            when {
                iframe.contains("tamilgun.space") || iframe.contains("vidhide") ->
                    TamilgunExtractor().extract(iframe, embedUrl, name, subtitleCallback, callback)

                iframe.contains("byseraguci.com") ||
                iframe.contains("filemoon")      ||
                iframe.contains("n1mwq.org")     ->
                    N1mwqExtractor().extract(iframe, embedUrl, name, subtitleCallback, callback)

                else ->
                    loadExtractor(iframe, embedUrl, subtitleCallback, callback)
            }
        }
        return any
    }
}
