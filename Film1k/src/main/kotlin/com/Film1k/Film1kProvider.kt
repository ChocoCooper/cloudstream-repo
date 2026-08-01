package com.film1k

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

class Film1kProvider : MainAPI() {
    override var mainUrl = "https://www.film1k.com"
    override var name = "Film1k"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.NSFW)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/tag/usa" to "USA Movies",
        "$mainUrl/tag/1990s" to "1990s Movies"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val homeDoc = app.get("$mainUrl/").document
        val usaDoc = app.get("$mainUrl/tag/usa").document
        val nintiesDoc = app.get("$mainUrl/tag/1990s").document

        val homePageList = mutableListOf<HomePageList>()

        // 1. Latest Movies Section
        val latestTitle = homeDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "Latest Movies"
        val latestItems = parseArticles(homeDoc)
        if (latestItems.isNotEmpty()) {
            homePageList.add(HomePageList(latestTitle, latestItems, isHorizontalImages = true))
        }

        // 2. USA Movies Section
        val usaTitle = usaDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "USA Movies"
        val usaItems = parseArticles(usaDoc)
        if (usaItems.isNotEmpty()) {
            homePageList.add(HomePageList(usaTitle, usaItems, isHorizontalImages = true))
        }

        // 3. 1990s Movies Section
        val nintiesTitle = nintiesDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "1990s Movies"
        val nintiesItems = parseArticles(nintiesDoc)
        if (nintiesItems.isNotEmpty()) {
            homePageList.add(HomePageList(nintiesTitle, nintiesItems, isHorizontalImages = true))
        }

        // 4. 1990s USA Movies Section (Intersection of USA and 1990s)
        val usaUrls = usaItems.map { it.url }.toSet()
        val intersectionItems = nintiesItems.filter { usaUrls.contains(it.url) }
        if (intersectionItems.isNotEmpty()) {
            homePageList.add(HomePageList("1990s USA Movies", intersectionItems, isHorizontalImages = true))
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val doc = app.get(searchUrl).document
        return parseArticles(doc)
    }

    // Crawl pattern (no per-article network request needed):
    // #Ez-Wp > div > div > div > main > section > article > header > a > h2 -> title
    // #Ez-Wp > div > div > div > main > section > article > header > a > figure > img -> poster
    private fun parseArticles(doc: Document): List<SearchResponse> {
        val articles = doc.select("#Ez-Wp > div > div > div > main > section > article")
        return articles.mapNotNull { article ->
            val aHeader = article.selectFirst("header > a") ?: return@mapNotNull null
            val mediaUrl = fixUrl(aHeader.attr("href"))
            if (mediaUrl.isBlank()) return@mapNotNull null

            val mediaName = article.selectFirst("header > a > h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: aHeader.text().trim()

            val imgEl = article.selectFirst("header > a > figure > img")

            // Handles lazy-loaded images too (data-src/data-lazy-src commonly used before src).
            val posterUrl = imgEl?.let { el ->
                el.attr("data-src").takeIf { it.isNotBlank() }
                    ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
                    ?: el.attr("src").takeIf { it.isNotBlank() }
            }?.let { fixUrl(it) }

            newMovieSearchResponse(mediaName, mediaUrl, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document

        // Media Name extraction — same h2.entry-title path used on listing pages,
        // no sentence-trimming needed since this is already the plain title.
        val mediaName = doc.selectFirst("#Ez-Wp > div > div > div > main > section > article > header > a > h2")
            ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title()

        // Poster extraction
        val imgEl = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > img")
        val srcPoster = imgEl?.attr("src")?.takeIf { it.isNotBlank() }
        val ogPoster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val posterUrl = (srcPoster ?: ogPoster)?.let { fixUrl(it) }

        // Description extraction
        // Structure: aside > div > div > p:nth-child(4) contains
        // <strong>Movie Title</strong>, followed by inline text/links that
        // form the actual description, then a trailing Film1k promo sentence
        // we don't want ("Stream this ... on Film1k in high quality!").
        var plot: String? = null

        val descP = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > p:nth-child(4)")
        val descStrong = descP?.selectFirst("strong")

        if (descP != null) {
            var descText = descP.text()

            // Strip the leading movie-title strong text if present.
            val titleText = descStrong?.text()
            if (!titleText.isNullOrBlank() && descText.startsWith(titleText)) {
                descText = descText.removePrefix(titleText)
            }

            // Drop the leading ", " (or ": ") left after removing the title.
            descText = descText.trim().removePrefix(",").removePrefix(":").trim()

            // Drop any trailing sentence(s) that mention the site name (self-promo).
            val sentences = descText.split(Regex("(?<=[.!?])\\s+"))
            descText = sentences.filterNot { it.contains("Film1k", ignoreCase = true) }
                .joinToString(" ")
                .trim()

            if (descText.isNotBlank()) {
                plot = descText
            }
        }

        // Fallback: alternate layout where a plain <h3>Description:</h3> is
        // followed directly by sibling text/links (no wrapping <p>/<strong> title).
        if (plot.isNullOrBlank()) {
            val descH3 = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > h3")
                ?.takeIf { it.text().contains("Description", ignoreCase = true) }

            if (descH3 != null) {
                val builder = StringBuilder()
                var sibling = descH3.nextSibling()
                while (sibling != null) {
                    if (sibling is Element) {
                        val tagName = sibling.tagName().lowercase()
                        if (tagName in listOf("h1", "h2", "h3", "h4", "p", "div", "strong", "b") && tagName != "a") {
                            break
                        }
                        builder.append(sibling.text()).append(" ")
                    } else if (sibling is TextNode) {
                        builder.append(sibling.text()).append(" ")
                    }
                    sibling = sibling.nextSibling()
                }
                var extracted = builder.toString().trim()

                // Drop any trailing self-promo sentence, same as the primary path.
                val sentences = extracted.split(Regex("(?<=[.!?])\\s+"))
                val filtered = sentences.filterNot { it.contains("Film1k", ignoreCase = true) }
                    .joinToString(" ")
                    .trim()
                extracted = filtered.ifBlank { extracted }

                if (extracted.isNotBlank()) {
                    plot = extracted
                }
            }
        }

        // Fallback: older "Plot" labeled layout, in case a page uses it instead.
        if (plot.isNullOrBlank()) {
            val plotStrong = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > strong:nth-child(14)")
                ?.takeIf { it.text().contains("Plot", ignoreCase = true) }
                ?: doc.selectFirst("#Ez-Wp > div > div.Container > div > aside strong:contains(Plot)")
                ?: doc.selectFirst("#Ez-Wp > div > div.Container > div > aside b:contains(Plot)")
                ?: doc.selectFirst("strong:contains(Plot)")

            if (plotStrong != null) {
                val builder = StringBuilder()
                var sibling = plotStrong.nextSibling()
                while (sibling != null) {
                    if (sibling is Element) {
                        val tagName = sibling.tagName().lowercase()
                        if (tagName in listOf("h1", "h2", "h3", "h4", "p", "div", "strong", "b") && tagName != "a") {
                            break
                        }
                        builder.append(sibling.text()).append(" ")
                    } else if (sibling is TextNode) {
                        builder.append(sibling.text()).append(" ")
                    }
                    sibling = sibling.nextSibling()
                }
                val extracted = builder.toString().trim()
                if (extracted.isNotBlank()) {
                    plot = extracted
                }
            }
        }

        // Final fallback: whole aside text block minus its heading.
        if (plot.isNullOrBlank()) {
            val asideDiv = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div")
            val h3Text = asideDiv?.selectFirst("h3")?.text() ?: ""
            var rawPlot = asideDiv?.text() ?: ""
            if (h3Text.isNotBlank()) {
                rawPlot = rawPlot.replace(h3Text, "").trim()
            }
            plot = rawPlot
        }

        plot = plot
            ?.replace("^\\s*:\\s*".toRegex(), "")
            ?.replace("^\\s*\"|\"\\s*$".toRegex(), "")
            ?.replace("\\s+".toRegex(), " ")
            ?.trim()

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

    override suspend fun loadLinks(
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
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                // If it's an embed provider link (e.g. film1k.xyz/e/...)
                loadExtractor(videoUrl, data, subtitleCallback, callback)
            }
        }

        return extractedUrls.isNotEmpty()
    }
}
