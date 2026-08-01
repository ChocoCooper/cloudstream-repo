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
        val homeDoc = app.get("$mainUrl/", verify = false).document
        val usaDoc = app.get("$mainUrl/tag/usa", verify = false).document
        val nintiesDoc = app.get("$mainUrl/tag/1990s", verify = false).document

        val homePageList = mutableListOf<HomePageList>()

        val latestTitle = homeDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "Latest Movies"
        val latestItems = parseArticles(homeDoc)
        if (latestItems.isNotEmpty()) {
            homePageList.add(HomePageList(latestTitle, latestItems, isHorizontalImages = true))
        }

        val usaTitle = usaDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "USA Movies"
        val usaItems = parseArticles(usaDoc)
        if (usaItems.isNotEmpty()) {
            homePageList.add(HomePageList(usaTitle, usaItems, isHorizontalImages = true))
        }

        val nintiesTitle = nintiesDoc.selectFirst("#Ez-Wp > div > div > div > main > section > div.page-top > h3")?.text() ?: "1990s Movies"
        val nintiesItems = parseArticles(nintiesDoc)
        if (nintiesItems.isNotEmpty()) {
            homePageList.add(HomePageList(nintiesTitle, nintiesItems, isHorizontalImages = true))
        }

        val usaUrls = usaItems.map { it.url }.toSet()
        val intersectionItems = nintiesItems.filter { usaUrls.contains(it.url) }
        if (intersectionItems.isNotEmpty()) {
            homePageList.add(HomePageList("1990s USA Movies", intersectionItems, isHorizontalImages = true))
        }

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val doc = app.get(searchUrl, verify = false).document
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
        val doc = app.get(url, verify = false).document

        val imgEl = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > img")
        val realPoster = imgEl?.let { el ->
            el.attr("data-src").takeIf { it.isNotBlank() }
                ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() }
                ?: el.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
        }
        val ogPoster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val posterUrl = (realPoster ?: ogPoster)?.let { fixUrl(it) }

        val rawName = imgEl?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1.entry-title, h2.entry-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title()
            
        val mediaName = rawName.replace("movie poster watch online", "", ignoreCase = true).trim()

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
        val doc = app.get(data, verify = false).document
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
            val isM3u8 = videoUrl.contains(".m3u8")
            val isMp4 = videoUrl.contains(".mp4")
            
            // Check if it's a direct media link and NOT masquerading as an embed path
            val isDirectMedia = (isM3u8 || isMp4) && 
                !videoUrl.contains("/e/") && 
                !videoUrl.contains("/embed/") && 
                !videoUrl.contains("/v/")

            if (isDirectMedia) {
                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = videoUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                // FORCE the link through our custom extractor to ensure verify = false is used.
                // Do NOT use Cloudstream's generic loadExtractor() here, as it will crash on blocked ISPs.
                Film1kExtractor().getUrl(videoUrl, mainUrl, subtitleCallback, callback)
            }
        }

        return extractedUrls.isNotEmpty()
    }
}

// ---------------------------------------------------------------------
// EXTRACTOR TO BYPASS THE FAKE PLAYER & EXTRACT THE M3U8
// (Manually bypasses SSL to prevent crashes caused by ISP blocking)
// ---------------------------------------------------------------------

class Film1kExtractor : ExtractorApi() {
    override var mainUrl = "https://film1k.xyz"
    override var name = "Film1k Embed"
    override val requiresReferer = false

    private suspend fun invokeCallback(
        streamUrl: String, 
        referer: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = streamUrl.replace("\\/", "/")
        val isM3u8 = cleanUrl.contains(".m3u8")
        callback.invoke(
            newExtractorLink(
                name = this.name,
                source = this.name,
                url = cleanUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
            }
        )
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // 1. Fetch the main embed page (ALWAYS USE verify = false)
            val response = app.get(url, referer = referer, verify = false).text
            
            // 2. Safely find the iframe (like q8y5z.com)
            val document = org.jsoup.Jsoup.parse(response)
            var iframeSrc = document.selectFirst("iframe")?.attr("src")
            if (iframeSrc.isNullOrBlank()) {
                val iframeRegex = Regex("<iframe[^>]+src=[\"']([^\"']+)[\"']")
                iframeSrc = iframeRegex.find(response)?.groupValues?.get(1)
            }

            val targetUrl = if (!iframeSrc.isNullOrBlank()) {
                if (iframeSrc.startsWith("//")) {
                    "https:$iframeSrc"
                } else if (iframeSrc.startsWith("/")) {
                    val host = url.split("/").take(3).joinToString("/")
                    "$host$iframeSrc"
                } else {
                    iframeSrc
                }
            } else url

            // 3. Fetch the iframe target manually (CRITICAL: verify = false prevents the SSL crash)
            val targetHtml = if (targetUrl != url) {
                app.get(targetUrl, referer = url, verify = false).text
            } else response

            val mediaRegex = Regex("(?<=[\"'])(https?://[^\"']+(?:\\.m3u8|\\.mp4)[^\"']*)(?=[\"'])")

            // Method A: Standard Regex search in HTML
            var match = mediaRegex.find(targetHtml)
            if (match != null) {
                invokeCallback(match.value, targetUrl, callback)
                return
            }

            // Method B: Unpack Obfuscated JS (Dean Edwards eval packer)
            val packedRegex = Regex("eval\\(function\\(p,a,c,k,e,d\\).*?split\\('\\|'\\).*?\\)\\)")
            val packedMatch = packedRegex.find(targetHtml)
            if (packedMatch != null) {
                val unpacked = getAndUnpack(packedMatch.value)
                val unpackedMatch = mediaRegex.find(unpacked)
                if (unpackedMatch != null) {
                    invokeCallback(unpackedMatch.value, targetUrl, callback)
                    return
                }
            }

            // Method C: API Bypass (Intercepting XHR requests)
            val videoId = targetUrl.substringAfterLast("/").substringBefore("?")
            if (videoId.isNotBlank()) {
                val apiHost = targetUrl.substringBefore("/e/").substringBefore("/v/").substringBefore("/embed/")
                
                val paths = listOf("/api/playback/$videoId", "/playback/$videoId", "/api/source/$videoId", "/source/$videoId")
                for (path in paths) {
                    try {
                        val headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        
                        // Test GET request
                        val apiResponseGet = app.get("$apiHost$path", referer = targetUrl, headers = headers, verify = false).text
                        val apiMatchGet = mediaRegex.find(apiResponseGet)
                        if (apiMatchGet != null) {
                            invokeCallback(apiMatchGet.value, targetUrl, callback)
                            return
                        }

                        // Test POST request
                        val apiResponsePost = app.post("$apiHost$path", referer = targetUrl, headers = headers, verify = false).text
                        val apiMatchPost = mediaRegex.find(apiResponsePost)
                        if (apiMatchPost != null) {
                            invokeCallback(apiMatchPost.value, targetUrl, callback)
                            return
                        }
                    } catch (e: Exception) {}
                }
            }
        } catch (e: Exception) {
            // Silently swallow errors so the app doesn't panic
        }
    }
}
