package com.JavHub

import android.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

private fun String.decodeHtmlEntities(): String = Parser.unescapeEntities(this, false)

data class LoadData(
    val url: String,
    val poster: String? = null
)

// Data class to parse JavHD's secret AJAX JSON response
data class JavHDAjaxResponse(
    @JsonProperty("html") val html: String? = null
)

class JavHubProvider : MainAPI() {
    override var mainUrl              = "https://javhd.today"
    override var name                 = "JavHD"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )

    private val subtitleCatUrl = "https://www.subtitlecat.com"
    private val missAvUrl      = "https://missav.ws"

    override val mainPage = mainPageOf(
        "$mainUrl/channel/madonna/" to "Madonna",
        "$mainUrl/channel/nagae-style-new/" to "Nagae Style",
        "$mainUrl/channel/attackers-new/" to "Attackers",
        "$mainUrl/channel/moodyz-new/" to "Moodyz"
    )

    // High-precision Regex to exclusively grab the JAV Code (e.g. JUR-103)
    private fun extractCode(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""([a-zA-Z0-9]{2,8}(?:-[a-zA-Z0-9]{2,8})?-\d{2,6})""")
        return regex.find(text)?.value?.uppercase()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url, headers = browserHeaders).document
        
        // Strictly target the .video class to avoid sidebar categories
        val items = document.select(".video a:has(img)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { extractCode(it.name) ?: it.url } // Deduplicate by JAV Code
        
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/search/video/?s=$encoded&ajax=1"
        
        val ajaxHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Referer" to "$mainUrl/"
        ) + browserHeaders
        
        // Fetch JSON from the AJAX endpoint to bypass the CAPTCHA
        val jsonResponse = app.get(url, headers = ajaxHeaders, timeout = 15).text
        val ajaxData = runCatching { parseJson<JavHDAjaxResponse>(jsonResponse) }.getOrNull()
        val htmlPayload = ajaxData?.html ?: return emptyList()
        
        // Parse the extracted HTML payload natively
        val document = Jsoup.parse(htmlPayload)
        
        // Strictly target the .video class and deduplicate by Code
        val items = document.select(".video a:has(img)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { extractCode(it.name) ?: it.url }
            
        return items
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        
        // Immediately filter out non-video utility/language links
        if (href.contains("/search/") || href.contains("/channel/") || href.contains("/tag/")) return null
        
        // Safely extract the title
        val rawTitle = this.attr("title").ifBlank { null }
            ?: this.selectFirst("span")?.text()?.trim()
            ?: this.text().trim()
            
        // Cleansed title using native multi-char trim to avoid compiler errors
        val title = rawTitle.replace("(?i)Mosaic|English Sub|Uncensored".toRegex(), "")
            .trim('-', '_', '|', ' ')
            .trim()
            
        if (title.isBlank()) return null

        // Enforce Valid JAV Code presence
        val code = extractCode(title) ?: extractCode(href)
        if (code == null) return null

        // Extract high-quality vertical poster
        val imgEl = this.selectFirst("img") ?: return null
        val posterUrl = imgEl.attr("data-src").ifBlank { imgEl.attr("data-original") }.ifBlank { imgEl.attr("src") }.let { fixUrlNull(it) }

        val loadUrl = LoadData(href, posterUrl).toJson()

        return newMovieSearchResponse(title, loadUrl, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val loadData = runCatching { parseJson<LoadData>(url) }.getOrNull() ?: LoadData(url, null)
        val document = app.get(loadData.url, headers = browserHeaders).document

        val rawTitle = document.selectFirst("h1")?.text()?.trim()?.decodeHtmlEntities() ?: "Unknown"
        val title = rawTitle.replace("(?i)Mosaic|English Sub|Uncensored".toRegex(), "")
            .trim('-', '_', '|', ' ')
            .trim()

        val tags = document.select("a[href*=/tag/]").map { it.text().trim().decodeHtmlEntities() }
        val actors = document.select("a[href*=/star/], a[href*=/model/]").map { ActorData(Actor(it.text().trim().decodeHtmlEntities())) }

        val code = extractCode(title) ?: extractCode(loadData.url)
        val cleanCode = code?.lowercase()

        // Dynamically inject the high-res poster from fourhoi.com on the Details Page
        val poster = if (cleanCode != null) "https://fourhoi.com/$cleanCode/cover-n.jpg" else loadData.poster

        var fetchedDescription: String? = null

        // Background call to MissAV to extract the detailed description
        if (!code.isNullOrBlank()) {
            runCatching {
                val missAvDoc = app.get("$missAvUrl/en/$cleanCode", timeout = 10, headers = browserHeaders).document
                
                // Targets the specific CSS path first, falls back to robust layout classes if layout shifts
                val descEl = missAvDoc.selectFirst("html > body > div:nth-of-type(2) > div:nth-of-type(3) > div > div:nth-of-type(2) > div:nth-of-type(6) > div:nth-of-type(2) > div:nth-of-type(1) > div > div:nth-of-type(1) > div:nth-of-type(1)")
                    ?: missAvDoc.selectFirst("div.mb-1.text-secondary")
                    
                fetchedDescription = descEl?.text()?.trim()?.decodeHtmlEntities()

                if (fetchedDescription.isNullOrBlank()) {
                    fetchedDescription = missAvDoc.selectFirst("meta[property=og:description]")?.attr("content")?.decodeHtmlEntities()
                }
            }
        }

        val plot = buildString {
            if (!code.isNullOrBlank()) appendLine("Code: $code\n")
            if (!fetchedDescription.isNullOrBlank()) appendLine(fetchedDescription)
        }.trim().ifBlank { null }

        return newMovieLoadResponse(title, loadData.url, TvType.NSFW, loadData.url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tags
            this.actors = actors
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val document = app.get(data, timeout = 15, headers = browserHeaders).document
        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: data
        
        val code = extractCode(rawTitle) ?: rawTitle.split("/").lastOrNull { it.isNotBlank() }?.uppercase() ?: return false
        val cleanCode = code.lowercase()
        val foundStream = AtomicBoolean(false)

        coroutineScope {
            
            // ---- MIRROR 1: 123AV (Direct URL + Variants) --------------------------
            val variants123av = listOf(
                cleanCode to "123AV",
                "$cleanCode-uncensored-leaked" to "123AV [Uncensored]"
            )

            variants123av.forEach { (vCode, sourceName) ->
                launch {
                    try {
                        val url123av = "https://123av.com/en/v/$vCode"
                        val doc123 = app.get(url123av, timeout = 15, headers = browserHeaders).document
                        
                        val xData = doc123.selectFirst("div.watch-main > div.watch__main")?.attr("x-data").orEmpty()
                        val embedIdMatch = Regex("""javplayer\.cc[\\/]+e[\\/]+([a-zA-Z0-9]+)""").find(xData)
                        val embedId = embedIdMatch?.groupValues?.get(1)

                        if (!embedId.isNullOrBlank()) {
                            val embedUrl = "https://javplayer.cc/e/$embedId"
                            val apiUrl = "https://javplayer.cc/stream?id=$embedId"
                            
                            val apiRes = app.get(
                                apiUrl, 
                                headers = mapOf(
                                    "Referer" to "https://123av.com", 
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "User-Agent" to browserHeaders["User-Agent"]!!
                                ),
                                interceptor = CloudflareKiller()
                            ).text

                            var m3u8Url = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""").find(apiRes.replace("\\/", "/"))?.value

                            if (m3u8Url.isNullOrBlank()) {
                                val embedHtml = app.get(embedUrl, referer = "https://123av.com", interceptor = CloudflareKiller()).text
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
                                        sourceName,
                                        sourceName,
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
            }

            // ---- MIRROR 2: MissAV (Direct URL + Variants) --------------------------------------
            val variantsMissAv = listOf(
                cleanCode to "MissAV",
                "$cleanCode-uncensored-leak" to "MissAV [Uncensored]",
                "$cleanCode-english-subtitle" to "MissAV [English Subtitle]"
            )

            variantsMissAv.forEach { (vCode, sourceName) ->
                launch {
                    runCatching {
                        val missAvVideoUrl = "https://missav.ws/en/$vCode"
                        val response = app.get(missAvVideoUrl, timeout = 15, headers = browserHeaders)

                        if (response.code == 200) {
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
                                        sourceName,
                                        sourceName,
                                        finalLink,
                                        ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = "https://missav.ws"
                                        this.quality = Qualities.Unknown.value 
                                    }
                                )
                                foundStream.set(true)
                            }
                        }
                    }
                }
            }

            // ---- MIRROR 3: Jable (Direct URL) ---------------------------------------
            launch {
                runCatching {
                    val urlJable = "https://jable.tv/videos/$cleanCode/?lang=en"
                    val responseText = app.get(urlJable, timeout = 15, headers = browserHeaders).text
                    
                    val hlsUrl = Regex("""hlsUrl\s*=\s*['"](https?://[^'"]+\.m3u8)['"]""").find(responseText)?.groupValues?.get(1)
                        ?: Regex("""(https?://[^\s'\"<>]+?\.m3u8[^\s'\"<>]*)""").find(responseText.replace("\\/", "/"))?.groupValues?.get(1)

                    if (!hlsUrl.isNullOrBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                "Jable",
                                "Jable",
                                hlsUrl,
                                ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://jable.tv"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundStream.set(true)
                    }
                }
            }
            
            // ---- Subtitles via SubtitleCat (Fetches multiple .srt matches) -----------
            launch {
                runCatching {
                    val searchDoc = app.get("$subtitleCatUrl/index.php?search=$code", timeout = 15, headers = browserHeaders).document
                    val foundSubs = mutableListOf<String>()

                    val subtitlePageLinks = searchDoc.select("table.sub-table > tbody > tr > td > a[href^=\"subs/\"]")
                        .filter { it.text().contains(code, ignoreCase = true) }
                        .mapNotNull { el ->
                            el.attr("href").let { href ->
                                if (href.startsWith("http")) href else "$subtitleCatUrl/$href"
                            }
                        }
                        .distinct()

                    subtitlePageLinks.forEach { subPageUrl ->
                        runCatching {
                            val subPageDoc = app.get(subPageUrl, timeout = 10, headers = browserHeaders).document
                            val enHref = subPageDoc.selectFirst("div.sub-single > span > a#download_en")?.attr("href")

                            if (!enHref.isNullOrBlank()) {
                                val fullEnUrl = if (enHref.startsWith("http")) enHref else "$subtitleCatUrl/$enHref"
                                if (!foundSubs.contains(fullEnUrl)) {
                                    subtitleCallback(SubtitleFile("English", fullEnUrl))
                                    foundSubs.add(fullEnUrl)
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
