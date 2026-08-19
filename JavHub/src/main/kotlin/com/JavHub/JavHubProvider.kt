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

// Extension function to force Master Playlist containing all resolutions
private fun String.toPlaylistM3u8(): String {
    return this.replace(Regex("""/[0-9]+x[0-9]+/[^/]+\.m3u8"""), "/playlist.m3u8")
               .replace("video.m3u8", "playlist.m3u8")
}

// Added 'code' to pass the exact JAV code directly from the UI grid to the extractors
data class LoadData(
    val url: String,
    val poster: String? = null,
    val code: String? = null
)

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

    private fun extractCode(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""([a-zA-Z0-9]{2,8}(?:-[a-zA-Z0-9]{2,8})?-\d{2,6})""")
        return regex.find(text)?.value?.uppercase()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url, headers = browserHeaders).document
        
        val items = document.select(".video a:has(img)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { extractCode(it.name) ?: it.url }
        
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
        
        val jsonResponse = app.get(url, headers = ajaxHeaders, timeout = 15).text
        val ajaxData = runCatching { parseJson<JavHDAjaxResponse>(jsonResponse) }.getOrNull()
        val htmlPayload = ajaxData?.html ?: return emptyList()
        
        val document = Jsoup.parse(htmlPayload)
        
        val items = document.select(".video a:has(img)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { extractCode(it.name) ?: it.url }
            
        return items
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawHref = this.attr("href")
        if (rawHref.isBlank()) return null
        
        // Safely resolves relative URLs regardless of JSoup Base URI state
        val href = if (rawHref.startsWith("/")) "$mainUrl$rawHref" else rawHref
        
        if (href.contains("/search/") || href.contains("/channel/") || href.contains("/tag/")) return null
        
        val rawTitle = this.attr("title")
        val title = rawTitle.replace("(?i)Mosaic|English Sub|Uncensored".toRegex(), "")
            .trim('-', '_', '|', ' ')
            .trim()
            
        if (title.isBlank()) return null

        // Strictly extracts code from the title only
        val code = extractCode(title)
        if (code == null) return null

        val imgEl = this.selectFirst("img") ?: return null
        val posterUrl = fixUrlNull(imgEl.attr("src"))

        // We explicitly package the verified Code into the JSON payload
        val loadUrl = LoadData(href, posterUrl, code).toJson()

        return newMovieSearchResponse(title, loadUrl, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "https://javhd.today/")
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val loadData = runCatching { parseJson<LoadData>(url) }.getOrNull()
        val videoUrl = loadData?.url ?: url
        
        val document = app.get(videoUrl, headers = browserHeaders).document

        val rawTitle = document.selectFirst("h1")?.text()?.trim()?.decodeHtmlEntities() ?: "Unknown"
        val title = rawTitle.replace("(?i)Mosaic|English Sub|Uncensored".toRegex(), "")
            .trim('-', '_', '|', ' ')
            .trim()

        // Bypasses the HTML entirely if we already packaged the code in LoadData, otherwise extracts from title only
        val code = loadData?.code ?: extractCode(title)
        val cleanCode = code?.lowercase()

        val verticalPoster = loadData?.poster
        var fetchedDescription: String? = null
        val horizontalPoster = if (cleanCode != null) "https://fourhoi.com/$cleanCode/cover-n.jpg" else null

        if (!cleanCode.isNullOrBlank()) {
            runCatching {
                val missAvDoc = app.get("$missAvUrl/en/$cleanCode", timeout = 10, headers = browserHeaders).document
                
                val descEl = missAvDoc.selectFirst("div.mb-1.text-secondary") 
                    ?: missAvDoc.selectFirst("meta[property=og:description]")

                fetchedDescription = if (descEl?.tagName() == "meta") {
                    descEl.attr("content").decodeHtmlEntities()
                } else {
                    descEl?.text()?.trim()?.decodeHtmlEntities()
                }
            }
        }

        // We pass the exact 'url' JSON string to loadLinks to preserve the Code state
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = verticalPoster
            this.posterHeaders = mapOf("Referer" to "https://javhd.today/")
            this.backgroundPosterUrl = horizontalPoster
            this.plot = fetchedDescription
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        val loadData = runCatching { parseJson<LoadData>(data) }.getOrNull()
        val videoUrl = loadData?.url ?: data
        
        // Grab code directly from JSON. If somehow missing, safely fallback to scraping the page title only.
        var code = loadData?.code
        
        if (code == null) {
            val document = app.get(videoUrl, timeout = 15, headers = browserHeaders).document
            val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: ""
            code = extractCode(rawTitle)
        }
        
        if (code == null) return false
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
                                        m3u8Url.toPlaylistM3u8(),
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
                                        finalLink.toPlaylistM3u8(),
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
                                hlsUrl.toPlaylistM3u8(),
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
