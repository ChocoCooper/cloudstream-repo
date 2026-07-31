package com.film1k

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class Film1kPlugin : MainAPI() {
    override var mainUrl = "https://www.film1k.com"
    override var name = "Film1k"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/tag/usa" to "USA Movies",
        "$mainUrl/tag/1990s" to "1990s Movies"
    )

    override async fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val homeDoc = app.get("$mainUrl/").document
        val usaDoc = app.get("$mainUrl/tag/usa").document
        val nintiesDoc = app.get("$mainUrl/tag/1990s").document

        val homePageList = mutableListOf<HomePageList>()

        // 1. Top 10 Popular Section
        val top10Title = homeDoc.selectFirst("#custom_html-4 > div > div > h3")?.text() ?: "Top 10 Popular"
        val top10Elements = homeDoc.select("#custom_html-4 > div > div > ul > li > a")
        
        val top10Items = coroutineScope {
            top10Elements.map { aTag ->
                async {
                    val mediaUrl = fixUrl(aTag.attr("href"))
                    // Fetch media page to get poster and clean title
                    try {
                        val mediaDoc = app.get(mediaUrl).document
                        val imgEl = mediaDoc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > img")
                        val rawName = imgEl?.attr("alt") ?: aTag.text()
                        val mediaName = rawName.replace("movie poster watch online", "", ignoreCase = true).trim()
                        val posterUrl = imgEl?.attr("src")?.let { fixUrl(it) }

                        newMovieSearchResponse(mediaName, mediaUrl, TvType.Movie) {
                            this.posterUrl = posterUrl
                        }
                    } catch (e: Exception) {
                        null
                    }
                }
            }.mapNotNull { it.await() }
        }
        if (top10Items.isNotEmpty()) {
            homePageList.add(HomePageList(top10Title, top10Items))
        }

        // 2. Latest Movies Section
        val latestTitle = homeDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "Latest Movies"
        val latestItems = parseArticles(homeDoc)
        if (latestItems.isNotEmpty()) {
            homePageList.add(HomePageList(latestTitle, latestItems))
        }

        // 3. USA Movies Section
        val usaTitle = usaDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "USA Movies"
        val usaItems = parseArticles(usaDoc)
        if (usaItems.isNotEmpty()) {
            homePageList.add(HomePageList(usaTitle, usaItems))
        }

        // 4. 1990s Movies Section
        val nintiesTitle = nintiesDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "1990s Movies"
        val nintiesItems = parseArticles(nintiesDoc)
        if (nintiesItems.isNotEmpty()) {
            homePageList.add(HomePageList(nintiesTitle, nintiesItems))
        }

        // 5. 1990s USA Movies Section (Intersection of USA and 1990s)
        val usaUrls = usaItems.map { it.url }.toSet()
        val intersectionItems = nintiesItems.filter { usaUrls.contains(it.url) }
        if (intersectionItems.isNotEmpty()) {
            homePageList.add(HomePageList("1990s USA Movies", intersectionItems))
        }

        return newHomePageResponse(homePageList)
    }

    private fun parseArticles(doc: Document): List<SearchResponse> {
        val articles = doc.select("#Ez-Wp > div > div > div > main > section > article")
        return articles.mapNotNull { article ->
            val aHeader = article.selectFirst("header > a") ?: return@mapNotNull null
            val mediaUrl = fixUrl(aHeader.attr("href"))
            val mediaName = article.selectFirst("header > a > h2")?.text() ?: aHeader.text()

            newMovieSearchResponse(mediaName, mediaUrl, TvType.Movie) {
                // Poster will be loaded when clicked, or extracted on detail load
            }
        }
    }

    override async fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // Image & Media Name extraction
        val imgEl = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > img")
        val rawName = imgEl?.attr("alt") ?: doc.title()
        val mediaName = rawName.replace("movie poster watch online", "", ignoreCase = true).trim()
        val posterUrl = imgEl?.attr("src")?.let { fixUrl(it) }

        // Description extraction
        val asideDiv = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div")
        val h3Text = asideDiv?.selectFirst("h3")?.text() ?: ""
        var rawPlot = asideDiv?.text() ?: ""
        
        if (h3Text.isNotBlank()) {
            rawPlot = rawPlot.replace(h3Text, "").trim()
        }
        
        // Trim quotes and whitespace
        val plot = rawPlot.trim('"', ' ', '\n', '\r')

        // Meta Tags extraction
        val tags1 = doc.select("#Ez-Wp > div > div.Container > div > aside > div > p:nth-child(4) > a").map { it.text() }
        val tags2 = doc.select("#Ez-Wp > div > div.Container > div > aside > div > p:nth-child(6) > a").map { it.text() }
        val allTags = (tags1 + tags2).filter { it.isNotBlank() }.distinct()

        return newMovieLoadResponse(mediaName, url, TvType.Movie, url) {
            this.posterUrl = posterUrl
            this.plot = plot
            this.tags = allTags
        }
    }

    override async fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val extractedUrls = mutableSetOf<String>()

        // Path 1: #my-video > source
        doc.select("#my-video > source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank()) extractedUrls.add(fixUrl(src))
        }

        // Path 2: #video-op-a > div > iframe
        doc.select("#video-op-a > div > iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) extractedUrls.add(fixUrl(src))
        }

        // Path 3: #Eroz > div > ul > li:nth-child(1) > a
        doc.select("#Eroz > div > ul > li > a").forEach { aTag ->
            val href = aTag.attr("href").ifBlank { aTag.attr("data-link") }
            if (href.isNotBlank()) extractedUrls.add(fixUrl(href))
        }

        // Process all extracted URLs
        for (videoUrl in extractedUrls) {
            if (videoUrl.endsWith(".mp4") || videoUrl.contains(".m3u8")) {
                callback.invoke(
                    ExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = videoUrl,
                        referer = mainUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = videoUrl.contains(".m3u8")
                    )
                )
            } else {
                // If it's an embed provider link (e.g. film1k.xyz/e/...)
                loadExtractor(videoUrl, data, subtitleCallback, callback)
            }
        }

        return extractedUrls.isNotEmpty()
    }
}
