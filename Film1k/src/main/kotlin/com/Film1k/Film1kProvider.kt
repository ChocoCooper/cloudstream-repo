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

    private fun parseArticles(doc: Document): List<SearchResponse> {
        val articles = doc.select("#Ez-Wp > div > div > div > main > section > article")
        return articles.mapNotNull { article ->
            val aHeader = article.selectFirst("header > a") ?: return@mapNotNull null
            val mediaUrl = fixUrl(aHeader.attr("href"))
            if (mediaUrl.isBlank()) return@mapNotNull null

            val rawName = article.selectFirst("header > a > h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
                ?: aHeader.text().trim()
            val mediaName = rawName.replace("movie poster watch online", "", ignoreCase = true).trim()

            val imgEl = article.selectFirst("header > a > figure > img")

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

        // Poster extraction
        val imgEl = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > img")
        val realPoster = imgEl?.let { el ->
            el.attr("data-src").takeIf { it.isNotBlank() }
                ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
                ?: el.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }
        val ogPoster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val posterUrl = (realPoster ?: ogPoster)?.let { fixUrl(it) }

        // Media Name extraction
        val rawName = imgEl?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1.entry-title, h2.entry-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title()
            
        val mediaName = rawName.replace("movie poster watch online", "", ignoreCase = true).trim()

        // Description / Plot extraction
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
                    if (tagName in listOf("h1", "h2", "h3", "h4", "strong", "b")) break
                    builder.append(sibling.text()).append(" ")
                } else if (sibling is TextNode) {
                    builder.append(sibling.text()).append(" ")
                }
                sibling = sibling.nextSibling()
            }

            var extracted = builder.toString().trim().removePrefix(",").removePrefix(":").trim()
            if (extracted.isBlank()) return null

            val sentences = extracted.split(Regex("(?<=[.!?])\\s+"))
            val filtered = sentences.filterNot { it.contains("Film1k", ignoreCase = true) }
                .joinToString(" ").trim()
            extracted = filtered.ifBlank { extracted }

            return extracted.ifBlank { null }
        }

        fun extractTitleLeadParagraph(): String? {
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

        fun realSrc(el: Element): String? {
            return el.attr("data-src").takeIf { it.isNotBlank() }
                ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
                ?: el.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }

        doc.select("#my-video > source").forEach { source ->
            val src = realSrc(source)
            if (!src.isNullOrBlank()) extractedUrls.add(fixUrl(src))
        }

        doc.select("#video-op-a > div > iframe").forEach { iframe ->
            val src = realSrc(iframe)
            if (!src.isNullOrBlank()) extractedUrls.add(fixUrl(src))
        }

        doc.select("#Eroz > div > ul > li > a").forEach { aTag ->
            val href = aTag.attr("href").ifBlank { aTag.attr("data-link") }
            if (href.isNotBlank()) extractedUrls.add(fixUrl(href))
        }

        for (videoUrl in extractedUrls) {
            val isEmbedPage = videoUrl.contains("/e/") || videoUrl.contains("/embed/")

            if (!isEmbedPage && videoUrl.contains(".m3u8")) {
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
            } else if (!isEmbedPage && videoUrl.contains(".mp4")) {
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
                // Passes embed links to the Extractor logic
                loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
            }
        }

        return extractedUrls.isNotEmpty()
    }
}

// ---------------------------------------------------------------------
// EXTRACTOR TO BYPASS THE FAKE PLAYER & EXTRACT THE M3U8
// ---------------------------------------------------------------------

class Film1kExtractor : ExtractorApi() {
    override var mainUrl = "https://film1k.xyz"
    override var name = "Film1k Embed"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Fetch the raw HTML of the embed page
        val response = app.get(url, referer = referer)
        val html = response.text

        // Attempt 1: Search the raw HTML/Scripts for standard m3u8 patterns
        val m3u8Regex = Regex("(?<=[\"'])(https?://[^\"']+\\.m3u8[^\"']*)(?=[\"'])")
        val match = m3u8Regex.find(html)
        
        if (match != null) {
            val streamUrl = match.value.replace("\\/", "/")
            callback.invoke(
                ExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = streamUrl,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return
        }

        // Attempt 2: If the m3u8 is not directly in the HTML, check for iframes
        val document = response.document
        val iframeSrc = document.selectFirst("iframe")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            val fixedIframe = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            loadExtractor(fixedIframe, url, subtitleCallback, callback)
            return
        }

        // Attempt 3: If it's heavily obfuscated via API (React Single Page App), 
        // we can attempt a lightweight API interception (using the video ID).
        // For example, if url is "https://film1k.xyz/e/f69hykwic7jp"
        val videoId = url.substringAfterLast("/")
        if (videoId.isNotBlank()) {
            try {
                // Often, these React video hosts make a POST/GET request to a backend endpoint like /api/source/
                val apiResponse = app.post(
                    "https://film1k.xyz/api/source/$videoId",
                    referer = url,
                    headers = mapOf("Accept" to "application/json", "X-Requested-With" to "XMLHttpRequest")
                ).text

                val apiMatch = m3u8Regex.find(apiResponse)
                if (apiMatch != null) {
                    val streamUrl = apiMatch.value.replace("\\/", "/")
                    callback.invoke(
                        ExtractorLink(
                            name = this.name,
                            source = this.name,
                            url = streamUrl,
                            referer = url,
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                }
            } catch (e: Exception) {
                // API endpoint might be different; fail silently to prevent crashes
            }
        }
    }
}
