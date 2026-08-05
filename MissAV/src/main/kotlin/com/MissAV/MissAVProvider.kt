package com.MissAv

import android.util.Base64
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

private fun String.decodeHtmlEntities(): String = Parser.unescapeEntities(this, false)

data class LoadData(
    val url: String,
    val poster: String? = null
)

class MissAVProvider : MainAPI() {
    override var mainUrl              = "https://123av.com"
    override var name                 = "MissAV"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)

    private val missAvUrl      = "https://missav.ws"
    private val subtitleCatUrl = "https://www.subtitlecat.com"
    private val javtifulUrl    = "https://javtiful.com"

    override val mainPage = mainPageOf(
        "$mainUrl/en/makers/madonna" to "Madonna",
        "$mainUrl/en/makers/moodys" to "Moody's",
        "$mainUrl/en/makers/sod-create" to "Sod Create",
        "$mainUrl/en/makers/nagae-style" to "Nagae Style",
        "$mainUrl/en/makers/attackers" to "Attackers",
        "$mainUrl/en/makers/prestige" to "Prestige",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url).document

        val items = document.select("div.card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("div.card__body > h3.card__title > a.card__link") ?: return null
        val title = linkEl.text().trim().decodeHtmlEntities()
        val href = fixUrl(linkEl.attr("href"))

        val posterEl = this.selectFirst("div.card__poster > a.card__cover > img.card__img")
        val posterUrl = posterEl?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }?.let { fixUrlNull(it) }

        val loadUrl = LoadData(href, posterUrl).toJson()

        return newMovieSearchResponse(title, loadUrl, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/en/search?keyword=$encoded").document
        return document.select("div.card").mapNotNull { it.toSearchResult() }
    }

    private fun extractCode(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""([a-zA-Z0-9]{2,8}(?:-[a-zA-Z0-9]{2,8})?-\d{2,6})""")
        return regex.find(text)?.value?.uppercase()
    }

    override suspend fun load(url: String): LoadResponse {
        val loadData = runCatching { parseJson<LoadData>(url) }.getOrNull() ?: LoadData(url, null)
        val document = app.get(loadData.url).document

        val title = (document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBeforeLast(" — 123AV")
            ?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown").decodeHtmlEntities()

        val poster = loadData.poster
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }

        val genres = document.select("dl.watch__info > div.watch__info-row > dd.chips > a[href^=\"/en/genres/\"]")
            .map { it.text().trim().decodeHtmlEntities() }

        val tags = document.select("dl.watch__info > div.watch__info-row > dd.chips > a[href^=\"/en/tags/\"]")
            .map { it.text().trim().decodeHtmlEntities() }

        val cast = document.select("dl.watch__info > div.watch__info-row > dd.chips > a[href^=\"/en/actresses/\"]")
            .map { it.text().trim().decodeHtmlEntities() }

        val maker = document.select("dl.watch__info > div.watch__info-row > dd.chips > a[href^=\"/en/makers/\"]")
            .map { it.text().trim().decodeHtmlEntities() }.firstOrNull()

        val rawCode = document.selectFirst("dl.watch__info > div.watch__info-row:nth-child(1) > dd")
            ?.text()?.trim()?.decodeHtmlEntities()
        val cleanCode = extractCode(rawCode) ?: rawCode

        val plot = buildString {
            if (!cleanCode.isNullOrBlank()) appendLine("Code: $cleanCode")
            if (!maker.isNullOrBlank()) appendLine("Maker: $maker")
        }.trim().ifBlank { null }

        return newMovieLoadResponse(title, loadData.url, TvType.NSFW, loadData.url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = (genres + tags).distinct()
            this.actors = cast.map { ActorData(Actor(it)) }
        }
    }

    private suspend fun findMissAvUrl(code: String): String? {
        val encoded = URLEncoder.encode(code, "UTF-8")
        val document = app.get("$missAvUrl/en/search/$encoded", timeout = 15).document

        val aTags = document.select("a[href]")
        for (a in aTags) {
            val href = a.attr("href")
            if (href.contains(code, ignoreCase = true) && !href.contains("search") && !href.contains("tags")) {
                return if (href.startsWith("http")) href else "$missAvUrl$href"
            }
        }
        return null
    }

    // Helper: Find Javtiful Video URL (Uses broad, un-breakable CSS selector)
    private suspend fun findJavtifulUrl(code: String): String? {
        val encoded = URLEncoder.encode(code, "UTF-8")
        val document = app.get("$javtifulUrl/search?q=$encoded", timeout = 15).document

        val articles = document.select("article a[href]")
        for (a in articles) {
            val href = a.attr("href")
            val title = a.attr("title").ifBlank { a.selectFirst("img")?.attr("alt").orEmpty() }
            if (href.contains(code, ignoreCase = true) || title.contains(code, ignoreCase = true)) {
                return if (href.startsWith("http")) href else "$javtifulUrl$href"
            }
        }
        val firstHref = articles.firstOrNull()?.attr("href") ?: return null
        return if (firstHref.startsWith("http")) firstHref else "$javtifulUrl$firstHref"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, timeout = 15).document

        val title = (document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBeforeLast(" — 123AV")?.trim()
            ?: document.title()).decodeHtmlEntities()

        val rawCodeText = document.selectFirst("dl.watch__info > div.watch__info-row:nth-child(1) > dd")
            ?.text()?.trim()?.decodeHtmlEntities()
            ?: title

        val code = extractCode(rawCodeText)

        val foundStream = AtomicBoolean(false)

        coroutineScope {
            // ---- SOURCE 1: 123AV (JavPlayer /stream?id= API) --------------------------
            launch {
                try {
                    val xData = document.selectFirst("div.watch-main > div.watch__main")?.attr("x-data").orEmpty()
                    val embedIdMatch = Regex("""javplayer\.cc[\\/]+e[\\/]+([a-zA-Z0-9]+)""").find(xData)
                    val embedId = embedIdMatch?.groupValues?.get(1)

                    if (!embedId.isNullOrBlank()) {
                        val embedUrl = "https://javplayer.cc/e/$embedId"
                        val apiUrl = "https://javplayer.cc/stream?id=$embedId"
                        
                        val apiRes = app.get(
                            apiUrl, 
                            headers = mapOf(
                                "Referer" to mainUrl, 
                                "X-Requested-With" to "XMLHttpRequest" 
                            )
                        ).text

                        var m3u8Url = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""").find(apiRes.replace("\\/", "/"))?.value

                        if (m3u8Url.isNullOrBlank()) {
                            val embedHtml = app.get(embedUrl, referer = mainUrl, interceptor = CloudflareKiller()).text
                            val unpackedHtml = runCatching { getAndUnpack(embedHtml) }.getOrDefault(embedHtml)
                            
                            val fallbackRegex = Regex("""(?:file|src|url|source)\s*[:=]\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
                            m3u8Url = fallbackRegex.find(unpackedHtml)?.groupValues?.get(1) 
                                ?: fallbackRegex.find(embedHtml)?.groupValues?.get(1)
                                
                            if (m3u8Url.isNullOrBlank()) {
                                m3u8Url = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""").find(unpackedHtml.replace("\\/", "/"))?.value
                                    ?: Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""").find(embedHtml.replace("\\/", "/"))?.value
                            }
                        }

                        if (!m3u8Url.isNullOrBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    "123AV",
                                    "123AV",
                                    m3u8Url,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://javplayer.cc/" 
                                    this.quality = Qualities.Unknown.value 
                                }
                            )
                            foundStream.set(true)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (!code.isNullOrBlank()) {
                // ---- SOURCE 2: MissAV --------------------------------------------------
                launch {
                    runCatching {
                        val missAvVideoUrl = findMissAvUrl(code) ?: return@runCatching
                        val response = app.get(missAvVideoUrl, timeout = 15)

                        val unpackedText = getAndUnpack(response.text)
                        var finalLink = Regex("""source\s*[:=]\s*['"](.*?)['"]""").find(unpackedText)?.groupValues?.get(1)
                        
                        if (finalLink?.startsWith("aHR0c") == true) {
                            finalLink = String(Base64.decode(finalLink, Base64.DEFAULT))
                        }
                        
                        if (finalLink.isNullOrBlank()) {
                            val b64Match = Regex("""['"](aHR0c[a-zA-Z0-9+/=]+)['"]""").find(unpackedText)?.groupValues?.get(1)
                            if (b64Match != null) {
                                val decoded = String(Base64.decode(b64Match, Base64.DEFAULT))
                                if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                                    finalLink = decoded
                                }
                            }
                        }
                        
                        if (finalLink.isNullOrBlank()) {
                            finalLink = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""").find(unpackedText.replace("\\/", "/"))?.value
                        }

                        if (!finalLink.isNullOrBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    "MissAV",
                                    "MissAV",
                                    finalLink,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = missAvUrl
                                    this.quality = Qualities.Unknown.value 
                                }
                            )
                            foundStream.set(true)
                        }
                    }
                }

                // ---- SOURCE 3: Javtiful (REGEX ONLY - Unbreakable) ---------------------
                launch {
                    runCatching {
                        val javtifulVideoUrl = findJavtifulUrl(code) ?: return@runCatching
                        val javDoc = app.get(javtifulVideoUrl, timeout = 15).document

                        // Try to find the iframe source
                        val iframeSrc = javDoc.selectFirst("div.player iframe, #player iframe, iframe")?.attr("src")
                        if (iframeSrc.isNullOrBlank()) return@runCatching

                        val embedUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$javtifulUrl$iframeSrc"
                        val embedHtml = app.get(embedUrl, referer = javtifulVideoUrl, timeout = 15).text

                        var javtifulFound = false
                        val sourcesJsonMatch = Regex("""\"playerSources\"\s*:\s*(\[.*?\])""").find(embedHtml)

                        // 1. Regex the URL straight out of the JSON string to avoid Data Class/Jackson errors
                        if (sourcesJsonMatch != null) {
                            val innerJson = sourcesJsonMatch.groupValues[1]
                            val srcMatches = Regex("""\"src\"\s*:\s*\"(https?://[^\"]+)\"""").findAll(innerJson)
                            
                            srcMatches.forEach { match ->
                                val rawUrl = match.groupValues[1].replace("\\/", "/")
                                val isM3u8 = rawUrl.contains(".m3u8")
                                val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                
                                callback.invoke(
                                    newExtractorLink(
                                        "Javtiful",
                                        "Javtiful",
                                        rawUrl,
                                        linkType
                                    ) {
                                        this.referer = embedUrl
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                javtifulFound = true
                                foundStream.set(true)
                            }
                        }

                        // 2. Ultimate Fallback (Global Regex)
                        if (!javtifulFound) {
                            val fallbackRegex = Regex("""\"src\"\s*:\s*\"(https?://[^\"]+)\"""").findAll(embedHtml)
                            fallbackRegex.forEach { match ->
                                val rawUrl = match.groupValues[1].replace("\\/", "/")
                                if (rawUrl.contains("fast-stream") || rawUrl.contains("video")) {
                                    val isM3u8 = rawUrl.contains(".m3u8")
                                    val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    
                                    callback.invoke(
                                        newExtractorLink(
                                            "Javtiful",
                                            "Javtiful",
                                            rawUrl,
                                            linkType
                                        ) {
                                            this.referer = embedUrl
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                    foundStream.set(true)
                                }
                            }
                        }
                    }
                }

                // ---- Subtitles via SubtitleCat -----------------------------------------
                launch {
                    runCatching {
                        val searchDoc = app.get("$subtitleCatUrl/index.php?search=$code", timeout = 15).document

                        val subtitlePageLinks = searchDoc.select("table.sub-table > tbody > tr > td > a[href^=\"subs/\"]")
                            .filter { it.text().contains(code, ignoreCase = true) }
                            .mapNotNull { el ->
                                el.attr("href").let { href ->
                                    if (href.startsWith("http")) href else "$subtitleCatUrl/$href"
                                }
                            }
                            .distinct()
                            .take(5)

                        subtitlePageLinks.forEach { subPageUrl ->
                            runCatching {
                                val subPageDoc = app.get(subPageUrl, timeout = 10).document
                                val enHref = subPageDoc.selectFirst("div.sub-single > span > a#download_en")?.attr("href")

                                if (!enHref.isNullOrBlank()) {
                                    val fullEnUrl = if (enHref.startsWith("http")) enHref else "$subtitleCatUrl/$enHref"
                                    subtitleCallback(SubtitleFile("English", fullEnUrl))
                                }
                            }
                        }
                    }
                }
            }
        }

        return foundStream.get()
    }
}
