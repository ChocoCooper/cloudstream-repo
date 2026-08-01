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

        // Media Name extraction — use the entry-title class directly (WordPress
        // standard), since the single media page doesn't always wrap the h2 in
        // the same "article > header > a" chain the listing pages use. This is
        // what keeps the title identical to what's shown on the homepage/search.
        val mediaName = doc.selectFirst("h1.entry-title, h2.entry-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("#Ez-Wp > div > div > div > main > section > article > header > a > h2")
                ?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title()

        // Poster extraction — the aside poster <img> is often lazy-loaded, so the
        // real image lives in data-src/data-lazy-src while src is just a blank
        // placeholder (e.g. src="data:image/svg+xml;base64,..."). Check those first.
        val imgEl = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > img")
        val realPoster = imgEl?.let { el ->
            el.attr("data-src").takeIf { it.isNotBlank() }
                ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
                ?: el.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }
        val ogPoster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val posterUrl = (realPoster ?: ogPoster)?.let { fixUrl(it) }

        // Description extraction — instead of relying on a fragile nth-child
        // position (which shifts between pages), find the <strong>/<h3> label
        // whose own text is "Plot" or "Description" (with or without a colon),
        // then collect the text that follows it up to the next label/heading.
        // Inline <a> links are kept as plain text (href dropped, words kept).
        var plot: String? = null

        val descContainer = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div")

        fun extractAfterLabel(label: String): String? {
            val labelEl = descContainer?.select("strong, h3, b")?.firstOrNull {
                it.text().trim().removeSuffix(":").trim().equals(label, ignoreCase = true)
            } ?: return null

            val builder = StringBuilder()
            var sibling = labelEl.nextSibling()
            while (sibling != null) {
                if (sibling is Element) {
                    val tagName = sibling.tagName().lowercase()
                    // Stop once we hit another label/heading — that belongs to the next field.
                    if (tagName in listOf("h1", "h2", "h3", "h4", "strong", "b")) break
                    builder.append(sibling.text()).append(" ")
                } else if (sibling is TextNode) {
                    builder.append(sibling.text()).append(" ")
                }
                sibling = sibling.nextSibling()
            }

            var extracted = builder.toString().trim()
                .removePrefix(",").removePrefix(":").trim()
            if (extracted.isBlank()) return null

            // Drop any trailing self-promo sentence (e.g. "Stream this ... on Film1k ...").
            val sentences = extracted.split(Regex("(?<=[.!?])\\s+"))
            val filtered = sentences.filterNot { it.contains("Film1k", ignoreCase = true) }
                .joinToString(" ").trim()
            extracted = filtered.ifBlank { extracted }

            return extracted.ifBlank { null }
        }

        fun extractTitleLeadParagraph(): String? {
            // Some pages have no literal "Plot"/"Description" label text at all —
            // the first <p> just starts with the movie title in <strong>, e.g.
            // "<strong>Dinosaur Island</strong>, an adventure comedy movie...".
            val firstP = descContainer?.selectFirst("p") ?: return null
            val leadStrong = firstP.selectFirst("strong") ?: return null
            var text = firstP.text()
            val titleText = leadStrong.text()
            if (text.startsWith(titleText)) {
                text = text.removePrefix(titleText)
            }
            text = text.trim().removePrefix(",").removePrefix(":").trim()
            if (text.isBlank()) return null

            val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            val filtered = sentences.filterNot { it.contains("Film1k", ignoreCase = true) }
                .joinToString(" ").trim()
            return filtered.ifBlank { text }.ifBlank { null }
        }

        plot = extractAfterLabel("Description") ?: extractAfterLabel("Plot") ?: extractTitleLeadParagraph()

        // Final fallback: whole aside text block minus its heading.
        if (plot.isNullOrBlank()) {
            val h3Text = descContainer?.selectFirst("h3")?.text() ?: ""
            var rawPlot = descContainer?.text() ?: ""
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

        // Helper: prefer real (possibly lazy-loaded) src over a blank/placeholder one.
        fun realSrc(el: Element): String? {
            return el.attr("data-src").takeIf { it.isNotBlank() }
                ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
                ?: el.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }

        // Path 1: #my-video > source
        doc.select("#my-video > source").forEach { source ->
            val src = realSrc(source)
            if (!src.isNullOrBlank()) extractedUrls.add(fixUrl(src))
        }

        // Path 2: #video-op-a > div > iframe
        doc.select("#video-op-a > div > iframe").forEach { iframe ->
            val src = realSrc(iframe)
            if (!src.isNullOrBlank()) extractedUrls.add(fixUrl(src))
        }

        // Path 3: #Eroz > div > ul > li:nth-child(1) > a
        doc.select("#Eroz > div > ul > li > a").forEach { aTag ->
            val href = aTag.attr("href").ifBlank { aTag.attr("data-link") }
            if (href.isNotBlank()) extractedUrls.add(fixUrl(href))
        }

        // Process all extracted URLs
        for (videoUrl in extractedUrls) {
            if (videoUrl.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else if (videoUrl.contains(".mp4")) {
                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = videoUrl,
                        type = ExtractorLinkType.VIDEO
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
