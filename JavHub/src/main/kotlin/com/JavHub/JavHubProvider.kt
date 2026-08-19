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

// Upgrades a single-resolution HLS URL to the multi-resolution master playlist
// WHEN we can clearly tell it's a per-resolution variant (a "/1920x1080/" or
// "/1080p/" style segment in the path). If the URL doesn't match that shape,
// it is left completely untouched — different mirrors use different master
// filenames (or already return the master directly), and forcing every URL
// to literally end in "playlist.m3u8" breaks mirrors that don't use that name.
private fun String.toPlaylistM3u8(): String {
    val perResolutionSegment = Regex("""/(?:\d{3,5}x\d{3,5}|\d{3,4}p)/[^/]+\.m3u8""")
    return when {
        perResolutionSegment.containsMatchIn(this) ->
            perResolutionSegment.replace(this, "/playlist.m3u8")
        this.endsWith("/video.m3u8") ->
            this.replace(Regex("""/video\.m3u8$"""), "/playlist.m3u8")
        else -> this
    }
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

    // ---------------------------------------------------------------------
    // Shared metadata helpers — used identically by getMainPage(), search(),
    // toSearchResult(), and load(), so homepage and search results always
    // converge on the exact same title/code/backgroundPosterUrl.
    // ---------------------------------------------------------------------

    // Word-boundary safe so we never eat into legitimate substrings of a real code.
    private val JUNK_WORDS = Regex(
        """\b(mosaic|english\s*sub(?:title)?s?|uncensored|engsub)\b""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Strips known junk keywords (Mosaic / English Subtitle / Uncensored / EngSub,
     * in any position/order) and normalizes whitespace/punctuation.
     */
    private fun cleanTitleText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .replace(JUNK_WORDS, "")
            .trim('-', '_', '|', ' ', '[', ']')
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .ifBlank { null }
    }

    /**
     * Extracts a JAV code like "JUR-816" from arbitrary text (title, url slug, etc).
     * Always cleans junk keywords first, so "Mosaic JUR-816", "JUR-816 Uncensored",
     * "JUR-816 [English Subtitle]", etc. all resolve to the same code: "JUR-816".
     */
    private fun extractCode(text: String?): String? {
        val cleanText = cleanTitleText(text) ?: return null
        val regex = Regex("""\b([a-zA-Z0-9]{2,8}(?:-[a-zA-Z0-9]{2,8})?-\d{2,6})\b""")
        return regex.find(cleanText)?.value?.uppercase()
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page <= 1) request.data else "${request.data}?page=$page"
        val document = app.get(url, headers = browserHeaders).document

        // Strictly target the main list (ul.videos) to preserve perfect sequential order
        val items = document.select("ul.videos div.video a:has(img)")
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

        // Strictly target the main list (ul.videos) to preserve perfect sequential order
        val items = document.select("ul.videos div.video a:has(img)")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { extractCode(it.name) ?: it.url }

        return items
    }

    /**
     * Builds the SearchResponse identically for homepage and search markup.
     * The anchor `title` attribute is not guaranteed on every listing template
     * (homepage vs AJAX search fragment can differ), so we fall back through
     * several candidate sources for the raw title text before giving up. This
     * is what makes homepage items resolve a code just as reliably as search
     * items do, instead of silently producing a null/garbled code.
     */
    private fun Element.toSearchResult(): SearchResponse? {
        val rawHref = this.attr("href")
        if (rawHref.isBlank()) return null

        val href = if (rawHref.startsWith("/")) "$mainUrl$rawHref" else rawHref
        if (href.contains("/search/") || href.contains("/channel/") || href.contains("/tag/")) return null

        val imgEl = this.selectFirst("img")

        // Fallback chain: anchor title attr -> img alt -> nested title/text elements -> link text.
        val titleCandidate = listOfNotNull(
            this.attr("title").ifBlank { null },
            imgEl?.attr("alt")?.ifBlank { null },
            this.selectFirst(".title, h3, .video-title")?.text()?.ifBlank { null },
            this.text().ifBlank { null }
        ).firstNotNullOfOrNull { cleanTitleText(it) }

        val title = titleCandidate ?: return null

        val code = extractCode(title)
        if (code == null) return null

        val posterUrl = fixUrlNull(
            imgEl?.attr("src")?.ifBlank { null } ?: imgEl?.attr("data-src")
        )

        val loadUrl = LoadData(href, posterUrl, code).toJson()

        return newMovieSearchResponse(title, loadUrl, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf("Referer" to "https://javhd.today/")
        }
    }

    /**
     * Single source of truth for the final LoadResponse. Regardless of whether
     * the user arrived via homepage or search, this always re-derives
     * title/code from the actual video page itself, so both entry paths
     * converge on identical output — same title, same backgroundPosterUrl,
     * same description. The vertical poster is preserved from LoadData
     * (cheap and reliable from the listing thumbnail).
     */
    override suspend fun load(url: String): LoadResponse {
        val loadData = runCatching { parseJson<LoadData>(url) }.getOrNull()
        var videoUrl = loadData?.url ?: url

        var document = app.get(videoUrl, headers = browserHeaders).document

        // Homepage links sometimes point at a "channel"-scoped copy of the video
        // page (e.g. /channel/madonna/video/xxx) which can render a lighter
        // template than the canonical /video/xxx page search results link to
        // directly. Resolve to the canonical page so BOTH entry paths always
        // end up parsing the exact same document — this is what keeps
        // homepage and search converging on identical title/code/description.
        val canonicalHref = document.selectFirst("link[rel=canonical]")?.attr("href")
            ?: document.selectFirst("meta[property=og:url]")?.attr("content")

        if (!canonicalHref.isNullOrBlank() && canonicalHref != videoUrl) {
            runCatching {
                val canonicalDoc = app.get(canonicalHref, headers = browserHeaders).document
                // Only swap over if the canonical page actually has real content.
                if (canonicalDoc.selectFirst("h1") != null) {
                    document = canonicalDoc
                    videoUrl = canonicalHref
                }
            }
        }

        val freshTitle = cleanTitleText(document.selectFirst("h1")?.text()?.decodeHtmlEntities())
            ?: cleanTitleText(document.selectFirst("meta[property=og:title]")?.attr("content"))

        val title = freshTitle ?: loadData?.code ?: "Unknown"

        // Prefer the code extracted from the freshly-loaded (canonical) page;
        // fall back to the listing-supplied code, then the URL slug, in that order.
        val code = extractCode(freshTitle)
            ?: loadData?.code
            ?: extractCode(videoUrl)

        val cleanCode = code?.lowercase()

        val verticalPoster = loadData?.poster
        val horizontalPoster = cleanCode?.let { "https://fourhoi.com/$it/cover-n.jpg" }

        var fetchedDescription: String? = null
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

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = verticalPoster
            // IMPORTANT: CloudStream only exposes ONE header map on LoadResponse
            // (posterHeaders) and applies it to every image tied to this response,
            // including backgroundPosterUrl. posterUrl lives on javhd.today but
            // backgroundPosterUrl lives on fourhoi.com — a Referer scoped to one
            // domain can get the image request on the OTHER domain rejected by
            // hotlink protection. Keep only a User-Agent here (safe for both
            // hosts) instead of a domain-specific Referer.
            this.posterHeaders = mapOf("User-Agent" to browserHeaders["User-Agent"]!!)
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
                                        // Use the site's own domain as referer for playback,
                                        // not the embed/player domain used to fetch the URL.
                                        this.referer = "https://123av.com/"
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

            // ---- MIRROR 4: Javmost (page -> #show_player iframe -> dooplayer embed -> mostplayer stream) --
            val variantsJavmost = listOf(
                code to "Javmost",
                "$code-UNCENSORED-edit" to "Javmost [Uncensored]"
            )

            variantsJavmost.forEach { (vCode, sourceName) ->
                launch {
                    runCatching {
                        val javmostUrl = "https://www.javmost.ws/$vCode/"
                        val pageDoc = app.get(javmostUrl, timeout = 15, headers = browserHeaders).document

                        val iframeSrc = pageDoc.selectFirst("#show_player iframe")?.attr("src")
                            ?.ifBlank { null }
                            ?: pageDoc.selectFirst("#show_player iframe")?.attr("data-src")?.ifBlank { null }

                        if (!iframeSrc.isNullOrBlank()) {
                            val embedUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc

                            val embedHtml = app.get(
                                embedUrl,
                                timeout = 15,
                                headers = browserHeaders,
                                referer = javmostUrl
                            ).text

                            val unpackedHtml = runCatching { getAndUnpack(embedHtml) }.getOrDefault(embedHtml)

                            // The embed page loads its actual manifest from mostplayer's CDN
                            // via a tokenized "stream?t=" request — capture that directly when
                            // present, since it's the confirmed real stream endpoint.
                            var streamUrl = Regex("""https?://cdn\.mostplayer\.com/stream\?t=[^"'\s<>]+""")
                                .find(unpackedHtml.replace("\\/", "/"))?.value
                                ?: Regex("""https?://cdn\.mostplayer\.com/stream\?t=[^"'\s<>]+""")
                                    .find(embedHtml.replace("\\/", "/"))?.value

                            if (streamUrl.isNullOrBlank()) {
                                val fallbackRegex = Regex("""(?:file|src|url|source)\s*[:=]\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""")
                                streamUrl = fallbackRegex.find(unpackedHtml)?.groupValues?.get(1)
                                    ?: fallbackRegex.find(embedHtml)?.groupValues?.get(1)
                            }

                            if (streamUrl.isNullOrBlank()) {
                                streamUrl = Regex("""https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*""")
                                    .find(unpackedHtml.replace("\\/", "/"))?.value
                                    ?: Regex("""https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*""")
                                        .find(embedHtml.replace("\\/", "/"))?.value
                            }

                            if (!streamUrl.isNullOrBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        sourceName,
                                        sourceName,
                                        streamUrl.toPlaylistM3u8(),
                                        ExtractorLinkType.M3U8
                                    ) {
                                        // Site's own domain as referer, per playback requirements.
                                        this.referer = "https://www.javmost.ws/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                foundStream.set(true)
                            }
                        }
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
