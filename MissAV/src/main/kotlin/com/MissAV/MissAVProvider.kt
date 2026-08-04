package com.MissAv

import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

// Some titles arrive double HTML-encoded (e.g. "Moody&#039;s" instead of "Moody's").
// Jsoup's .text()/.attr() only decode entities once during parsing, so a literal
// "&#039;" left in the text still needs one more manual unescape pass.
private fun String.decodeHtmlEntities(): String = Parser.unescapeEntities(this, false)

// Carries the poster scraped from the search/homepage card through to load(), since
// the 123AV video page's own og:image often falls back to the generic site logo
// (it's only filled in client-side by Alpine.js, which our static fetch never runs).
data class LoadData(
    val url: String,
    val poster: String? = null
)

class MissAVProvider : MainAPI() {
    // NOTE: mainUrl stays on 123av.com because ALL browsing (home page + search + video
    // metadata) is scraped from 123AV. missAvUrl below is only ever used internally, at
    // playback time, to look up the matching MissAV page and pull the real stream from it.
    override var mainUrl              = "https://123av.com"
    override var name                 = "MissAV"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)

    private val missAvUrl = "https://missav.ws"
    private val subtitleCatUrl = "https://www.subtitlecat.com"

    // ------------------------------------------------------------------
    // Home page — custom 123AV maker sections
    // ------------------------------------------------------------------
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

    // ------------------------------------------------------------------
    // 123AV card parser (home page + search)
    // ------------------------------------------------------------------
    private fun Element.toSearchResult(): SearchResponse? {
        val linkEl = this.selectFirst("div.card__body > h3.card__title > a.card__link") ?: return null
        val title = linkEl.text().trim().decodeHtmlEntities()
        val href = fixUrl(linkEl.attr("href"))

        val posterEl = this.selectFirst("div.card__poster > a.card__cover > img.card__img")
        val posterUrl = posterEl?.let {
            it.attr("src").ifBlank { it.attr("data-src") }
        }?.let { fixUrlNull(it) }

        // Bundle the href + poster together so load() can reuse the exact same poster
        // shown here, instead of re-scraping (and possibly getting the wrong) image
        // from the video page itself.
        val loadUrl = LoadData(href, posterUrl).toJson()

        return newMovieSearchResponse(title, loadUrl, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    // ------------------------------------------------------------------
    // Search — 123AV results
    // ------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/en/search?keyword=$encoded").document
        return document.select("div.card").mapNotNull { it.toSearchResult() }
    }

    // ------------------------------------------------------------------
    // Video / detail page — 123AV metadata
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        // `url` is normally a LoadData JSON blob (href + poster) produced in toSearchResult().
        // Fall back gracefully to treating it as a plain page URL (e.g. deep links) if parsing fails.
        val loadData = runCatching { parseJson<LoadData>(url) }.getOrNull() ?: LoadData(url, null)

        val document = app.get(loadData.url).document

        val title = (document.selectFirst("meta[property=og:title]")?.attr("content")
            ?.substringBeforeLast(" — 123AV")
            ?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "Unknown").decodeHtmlEntities()

        // Reuse the poster carried over from the search/homepage card. Only fall back to
        // scraping og:image (which is often just the generic site logo) if we somehow
        // don't have one already.
        val poster = loadData.poster
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.let { fixUrlNull(it) }

        val genres = document.select(
            "dl.watch__info > div.watch__info-row > dd.chips > a[href^=\"/en/genres/\"]"
        ).map { it.text().trim().decodeHtmlEntities() }

        val tags = document.select(
            "dl.watch__info > div.watch__info-row > dd.chips > a[href^=\"/en/tags/\"]"
        ).map { it.text().trim().decodeHtmlEntities() }

        val cast = document.select(
            "dl.watch__info > div.watch__info-row > dd.chips > a[href^=\"/en/actresses/\"]"
        ).map { it.text().trim().decodeHtmlEntities() }

        val maker = document.select(
            "dl.watch__info > div.watch__info-row > dd.chips > a[href^=\"/en/makers/\"]"
        ).map { it.text().trim().decodeHtmlEntities() }.firstOrNull()

        val code = document.selectFirst(
            "dl.watch__info > div.watch__info-row:nth-child(1) > dd"
        )?.text()?.trim()?.decodeHtmlEntities()

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
    // Helper: pull the JAV code (e.g. "SDJS-373", "START-609") out of a title
    // ------------------------------------------------------------------
    private fun extractCode(title: String): String? =
        Regex("""[a-zA-Z]+-\d+""").find(title)?.value

    // ------------------------------------------------------------------
    // Helper: search missav.ws for a given code and return the first
    // matching video page URL (absolute).
    // ------------------------------------------------------------------
    private suspend fun findMissAvUrl(code: String): String? {
        val encoded = URLEncoder.encode(code, "UTF-8")
        val document = app.get("$missAvUrl/en/search/$encoded", timeout = 15).document

        val card = document.selectFirst(".thumbnail, .max-w-sm, .w-full.truncate") ?: return null
        val aTag = card.selectFirst(".text-secondary") ?: card.selectFirst("a") ?: return null

        val href = aTag.attr("href")
        if (href.isBlank()) return null
        return if (href.startsWith("http")) href else "$missAvUrl$href"
    }

    // ------------------------------------------------------------------
    // Links + subtitles
    // ------------------------------------------------------------------
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
            // ---- 123AV's own stream (Alpine.js x-data on the watch page) ---------------
            launch {
                try {
                    val xData = document.selectFirst("div.watch-main > div.watch__main")?.attr("x-data").orEmpty()
                    
                    // 1. Safely extract just the ID, ignoring the messy \/\/\/ escaping
                    val embedIdMatch = Regex("""javplayer\.cc[\\/]+e[\\/]+([a-zA-Z0-9]+)""").find(xData)
                    val embedId = embedIdMatch?.groupValues?.get(1)
                    
                    println("123AV_DEBUG: Extracted Embed ID -> $embedId")

                    if (!embedId.isNullOrBlank()) {
                        // Rebuild the clean URL manually
                        val embedUrl = "https://javplayer.cc/e/$embedId"
                        println("123AV_DEBUG: Fetching URL -> $embedUrl")
                        
                        // 2. Fetch the JavPlayer embed page
                        val embedHtml = app.get(embedUrl, referer = mainUrl).text
                        println("123AV_DEBUG: Downloaded HTML length -> ${embedHtml.length}")
                        println("123AV_DEBUG: HTML CONTENT -> \n$embedHtml\n------------------")
                        
                        // 3. Unpack the obfuscated JavaScript. 
                        val unpackedHtml = runCatching { getAndUnpack(embedHtml) }.getOrDefault(embedHtml)
                        
                        // 4. Extract the hidden .m3u8 link from the decrypted JS
                        val m3u8Regex = Regex("""(?:file|src|url|source)\s*[:=]\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
                        val match = m3u8Regex.find(unpackedHtml) ?: m3u8Regex.find(embedHtml)
                        
                        val m3u8Url = match?.groupValues?.get(1)
                        println("123AV_DEBUG: Found M3U8 URL -> $m3u8Url")

                        if (!m3u8Url.isNullOrBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    "123AV",
                                    "123AV", // Renamed to just "123AV"
                                    m3u8Url,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "https://javplayer.cc/" 
                                    this.quality = Qualities.Unknown.value // Removes the "1080p" tag
                                }
                            )
                            foundStream.set(true)
                            println("123AV_DEBUG: Success! Stream sent to ExoPlayer.")
                        } else {
                            println("123AV_DEBUG: Failed to find .m3u8 string in the HTML.")
                        }
                    } else {
                        println("123AV_DEBUG: Regex failed to extract embed ID from x-data.")
                    }
                } catch (e: Exception) {
                    println("123AV_DEBUG: CRASHED -> ${e.message}")
                }
            }


            if (!code.isNullOrBlank()) {
                // ---- Cross-reference to MissAV for the actual stream ---------------------
                launch {
                    runCatching {
                        val missAvVideoUrl = findMissAvUrl(code) ?: return@runCatching
                        val response = app.get(missAvVideoUrl, timeout = 15)

                        val unpackedText = getAndUnpack(response.text)
                        val finalLink = Regex("""source=['"](.*?)['"]""").find(unpackedText)?.groupValues?.get(1)

                        if (!finalLink.isNullOrBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    "MissAV",
                                    "MissAV", // Renamed to just "MissAV"
                                    finalLink,
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.referer = missAvUrl
                                    this.quality = Qualities.Unknown.value // Removes the "1080p" tag
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
