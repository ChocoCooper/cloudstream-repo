package com.MissAv

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

    private val missAvUrl = "https://missav.ws"
    private val subtitleCatUrl = "https://www.subtitlecat.com"

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

        val code = document.selectFirst("dl.watch__info > div.watch__info-row:nth-child(1) > dd")
            ?.text()?.trim()?.decodeHtmlEntities()

        val plot = buildString {
            if (!code.isNullOrBlank()) appendLine("Code: $code")
            if (!maker.isNullOrBlank()) appendLine("Maker: $maker")
        }.trim().ifBlank { null }

        return newMovieLoadResponse(title, loadData.url, TvType.NSFW, loadData.url) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = (genres + tags).distinct()
            this.actors = cast.map { ActorData(Actor(it)) }
        }
    }

    // ------------------------------------------------------------------
    // Helper: Highly robust JAV code extractor (Ignores glued strings)
    // ------------------------------------------------------------------
    private fun extractCode(title: String): String? {
        // 1. Tries to find neatly spaced codes first
        Regex("""\b([a-zA-Z]{2,6}-\d{2,5})\b""").find(title)?.let { return it.value.uppercase() }
        // 2. Looks strictly for uppercase letters before hyphen (ignores glued lowercase words)
        Regex("""[A-Z]{2,6}-\d{2,5}""").find(title)?.let { return it.value }
        // 3. Fallback: rigidly capped letter count so it doesn't swallow sentences
        return Regex("""([a-zA-Z]{2,6}-\d{2,5})""").find(title)?.value?.uppercase()
    }

    private suspend fun findMissAvUrl(code: String): String? {
        val encoded = URLEncoder.encode(code, "UTF-8")
        val document = app.get("$missAvUrl/en/search/$encoded", timeout = 15).document

        val card = document.selectFirst(".thumbnail, .max-w-sm, .w-full.truncate") ?: return null
        val aTag = card.selectFirst(".text-secondary") ?: card.selectFirst("a") ?: return null

        val href = aTag.attr("href")
        if (href.isBlank()) return null
        return if (href.startsWith("http")) href else "$missAvUrl$href"
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

        val code = document.selectFirst("dl.watch__info > div.watch__info-row:nth-child(1) > dd")
            ?.text()?.trim()?.decodeHtmlEntities()
            ?: extractCode(title)

        val foundStream = AtomicBoolean(false)

        coroutineScope {
            // ---- 123AV's own stream (JavPlayer Embed + API Brute Force) ---------------
            launch {
                try {
                    val xData = document.selectFirst("div.watch-main > div.watch__main")?.attr("x-data").orEmpty()
                    
                    val embedIdMatch = Regex("""javplayer\.cc[\\/]+e[\\/]+([a-zA-Z0-9]+)""").find(xData)
                    val embedId = embedIdMatch?.groupValues?.get(1)

                    if (!embedId.isNullOrBlank()) {
                        val embedUrl = "https://javplayer.cc/e/$embedId"
                        
                        // Fetch embed page with Cloudflare bypass
                        val embedHtml = app.get(
                            embedUrl, 
                            referer = mainUrl, 
                            interceptor = CloudflareKiller()
                        ).text
                        
                        val unpackedHtml = runCatching { getAndUnpack(embedHtml) }.getOrDefault(embedHtml)
                        var m3u8Url: String? = null
                        
                        // 1. Check for the modern JavPlayer hidden API endpoint
                        val apiPath = Regex("""["'](/api/source/[a-zA-Z0-9_]+)["']""").find(unpackedHtml)?.groupValues?.get(1)
                            ?: Regex("""["'](/api/source/[a-zA-Z0-9_]+)["']""").find(embedHtml)?.groupValues?.get(1)
                            
                        if (apiPath != null) {
                            val apiUrl = "https://javplayer.cc$apiPath"
                            // The API requires a POST request to deliver the JSON containing the stream URL
                            val apiRes = app.post(apiUrl, referer = embedUrl).text
                            
                            // Extract stream URL and fix escaped slashes (\/ -> /)
                            m3u8Url = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""").find(apiRes.replace("\\/", "/"))?.value
                        }

                        // 2. Fallback: If no API, check if .m3u8 is directly in the unpacked HTML
                        if (m3u8Url.isNullOrBlank()) {
                            val fallbackRegex = Regex("""(?:file|src|url|source)\s*[:=]\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
                            m3u8Url = fallbackRegex.find(unpackedHtml)?.groupValues?.get(1) 
                                ?: fallbackRegex.find(embedHtml)?.groupValues?.get(1)
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
                // ---- Cross-reference to MissAV for the actual stream ---------------------
                launch {
                    runCatching {
                        // Using the new cleanly extracted code ensures this API call won't break
                        val missAvVideoUrl = findMissAvUrl(code) ?: return@runCatching
                        val response = app.get(missAvVideoUrl, timeout = 15)

                        val unpackedText = getAndUnpack(response.text)
                        val finalLink = Regex("""source=['"](.*?)['"]""").find(unpackedText)?.groupValues?.get(1)

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

                // ---- Subtitles via SubtitleCat (English only) -----------------------------
                launch {
                    runCatching {
                        val searchDoc = app.get("$subtitleCatUrl/index.php?search=$code", timeout = 15).document

                        val subtitlePageLinks = searchDoc.select(
                            "table.sub-table > tbody > tr > td > a[href^=\"subs/\"]"
                        )
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
                                val enHref = subPageDoc.selectFirst("div.sub-single > span > a#download_en")
                                    ?.attr("href")

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
