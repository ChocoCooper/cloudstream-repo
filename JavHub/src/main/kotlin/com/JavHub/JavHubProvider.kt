package com.JavHub

import android.util.Base64
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

private fun String.decodeHtmlEntities(): String = Parser.unescapeEntities(this, false)

// Converts direct video streams to master playlists if needed
private fun String.toPlaylistM3u8(): String {
    val perResolutionSegment = Regex("""/(?:\d{3,5}x\d{3,5}|\d{3,4}p)/[^/]+\.m3u8""")
    return when {
        perResolutionSegment.containsMatchIn(this) ->
            perResolutionSegment.replace(this, "/playlist.m3u8")
        this.contains("/video.m3u8") ->
            this.replace(Regex("""/video\.m3u8(\?.*)?$"""), "/playlist.m3u8$1")
        else -> this
    }
}

data class LoadData(
    val url: String,
    val poster: String? = null,
    val code: String? = null
)

data class JavtifulSource(
    @JsonProperty("src") val src: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("size") val size: Int? = null
)

data class JavtifulWatchConfig(
    @JsonProperty("playerSources") val playerSources: List<JavtifulSource>? = null
)

class JavHubProvider : MainAPI() {
    override var mainUrl              = "https://javtrailers.com"
    override var name                 = "JavHub"
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
        "Madonna" to "Madonna"
    )

    // ==================== Helpers ====================

    private fun extractCode(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""\b([a-zA-Z0-9]{2,8}(?:-[a-zA-Z0-9]{2,8})?-\d{2,6})\b""")
        return regex.find(text)?.value?.uppercase()
    }

    // Vertical poster (used on search cards only).
    // JavTrailers is inconsistent about lazy loading:
    //   - Search cards (img.card-img-top.video-image): real URL is in `data-src`, `src` is base64.
    //   - Some cards put the real URL in `src`, `data-src` empty.
    // Strategy: pick whichever attribute holds a real (non-base64) URL, preferring `src`.
    private fun verticalImageUrl(img: Element?): String? {
        if (img == null) return null
        val src     = img.attr("src").trim()
        val dataSrc = img.attr("data-src").trim()

        val chosen = when {
            src.isNotBlank() && !src.startsWith("data:") -> src
            dataSrc.isNotBlank() && !dataSrc.startsWith("data:") -> dataSrc
            else -> null
        }
        return fixUrlNull(chosen)
    }

    // Horizontal poster (used on details page).
    // Source: <meta property="og:image" content="...">
    private fun horizontalImageUrl(doc: Document): String? {
        val url = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        if (url.isNullOrBlank() || url.startsWith("data:")) return null
        return fixUrlNull(url)
    }

    // ==================== Label-based info-row extraction ====================
    //
    // The video page has #info-row containing <p> elements, each like:
    //   <p class="mb-1">
    //     <span class="font-weight-bold mr-3">Release Date:</span>
    //     20 Feb 2026
    //   </p>
    //
    // We locate the <p> whose leading <span> starts with the given label.
    // If no such row exists, we return null and the field is simply skipped.
    //
    private fun findInfoRow(doc: Document, vararg labels: String): String? {
        val normalizedLabels = labels.map { it.trim().lowercase().removeSuffix(":") }
        for (p in doc.select("#info-row p")) {
            val spanText = p.selectFirst("span")?.text()?.trim()?.lowercase()?.removeSuffix(":")
                ?: continue
            if (spanText in normalizedLabels) {
                // Return full text with the label span removed
                val labelSpan = p.selectFirst("span")
                val fullText = p.text().trim()
                val labelText = labelSpan?.text()?.trim().orEmpty()
                val value = if (labelText.isNotEmpty() && fullText.startsWith(labelText)) {
                    fullText.removePrefix(labelText).trim()
                } else {
                    fullText
                }
                return value.ifBlank { null }
            }
        }
        return null
    }

    private fun extractReleaseYear(doc: Document): Int? {
        val raw = findInfoRow(doc, "Release Date") ?: return null
        return Regex("""\b(19|20)\d{2}\b""").find(raw)?.value?.toIntOrNull()
    }

    private fun extractDurationMinutes(doc: Document): Int? {
        val raw = findInfoRow(doc, "Duration") ?: return null
        return Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE)
            .find(raw)?.groupValues?.get(1)?.toIntOrNull()
    }

    // Categories row contains multiple <a> badges. We take the first 3.
    private fun extractGenres(doc: Document, max: Int = 3): List<String> {
        // Find the <p> whose label span starts with "Categories"
        for (p in doc.select("#info-row p")) {
            val spanText = p.selectFirst("span")?.text()?.trim()?.lowercase()?.removeSuffix(":")
                ?: continue
            if (spanText == "categories") {
                return p.select("a")
                    .map { it.text().trim().decodeHtmlEntities() }
                    .filter { it.isNotBlank() }
                    .take(max)
            }
        }
        return emptyList()
    }

    // ==================== MissAV description / cast extraction ====================

    private fun extractMissAvDescription(doc: Document): String? {
        val descEl = doc.selectFirst("div.mb-1.text-secondary.break-all")
            ?: doc.selectFirst("div.text-secondary.break-all")
            ?: doc.selectFirst("meta[property=og:description]")

        val text = if (descEl?.tagName() == "meta") {
            descEl.attr("content")
        } else {
            descEl?.text()
        }

        return text?.trim()?.decodeHtmlEntities()?.trim()?.ifBlank { null }
    }

    private fun extractMissAvActors(doc: Document): List<ActorData> {
        val labels = setOf(
            "Actress:", "Actress", "Actresses:", "Actresses",
            "Actor:",   "Actor",   "Actors:",   "Actors"
        )

        val seen   = linkedSetOf<String>()
        val actors = mutableListOf<ActorData>()

        for (div in doc.select("div.text-secondary")) {
            val label = div.selectFirst("span")?.text()?.trim() ?: continue
            if (label !in labels) continue

            div.select("a").forEach { a ->
                val name = a.text().trim().decodeHtmlEntities()
                if (name.isNotBlank() && seen.add(name)) {
                    actors.add(ActorData(actor = Actor(name = name)))
                }
            }
        }

        return actors
    }

    // ==================== Search page parsing ====================

    private fun parseSearchPage(document: Document): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        val cards = document.select("#search div.card-container a.video-link")

        for (card in cards) {
            val href = card.attr("href")
            if (href.isBlank()) continue

            val fullUrl = if (href.startsWith("http")) href else "$mainUrl$href"

            val titleEl = card.selectFirst("p.card-text.title.mb-0.vid-title")
                ?: card.selectFirst("p.vid-title")
                ?: card.selectFirst("p.card-text.title")

            val rawTitle = titleEl?.text()?.trim()?.decodeHtmlEntities()
                ?: card.attr("title").ifBlank { null }
                ?: continue

            val imgEl = card.selectFirst("img.card-img-top.video-image")
                ?: card.selectFirst("img")

            val posterUrl = verticalImageUrl(imgEl)
            val code      = extractCode(rawTitle)

            val data = LoadData(
                url = fullUrl,
                poster = posterUrl,
                code = code
            ).toJson()

            results.add(
                newMovieSearchResponse(rawTitle, data, TvType.NSFW) {
                    this.posterUrl = posterUrl
                }
            )
        }

        return results
    }

    // ==================== Home page ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val encoded = URLEncoder.encode(request.data, "UTF-8")
        val url     = if (page <= 1) {
            "$mainUrl/search/$encoded"
        } else {
            "$mainUrl/search/$encoded?page=$page"
        }

        val document = app.get(url, headers = browserHeaders).document
        val items    = parseSearchPage(document)

        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    // ==================== Search ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")

        return coroutineScope {
            (1..2).map { page ->
                async {
                    runCatching {
                        val url = if (page == 1) {
                            "$mainUrl/search/$encoded"
                        } else {
                            "$mainUrl/search/$encoded?page=$page"
                        }
                        val document = app.get(url, headers = browserHeaders, timeout = 15).document
                        parseSearchPage(document)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.url }
        }
    }

    // ==================== Load ====================

    override suspend fun load(url: String): LoadResponse {
        val loadData = runCatching { parseJson<LoadData>(url) }.getOrNull()
        val videoUrl = loadData?.url ?: url

        val document = app.get(videoUrl, headers = browserHeaders).document

        val rawTitle = document.selectFirst("h1")?.text()?.trim()?.decodeHtmlEntities()
            ?: loadData?.code
            ?: "Unknown"

        val dvdId = loadData?.code ?: extractCode(rawTitle)
        val displayTitle = if (!dvdId.isNullOrBlank() &&
                                !rawTitle.startsWith(dvdId, ignoreCase = true)) {
            "$dvdId $rawTitle"
        } else rawTitle

        val cleanCode = dvdId?.lowercase()

        // ---- Horizontal poster (used on details page) ----
        // <meta property="og:image"> → content
        val horizontalPoster: String? = horizontalImageUrl(document)

        // ---- Label-based info fields (skipped if label not found) ----
        val releaseYear: Int?    = extractReleaseYear(document)
        val durationMinutes: Int? = extractDurationMinutes(document)
        val genres: List<String>  = extractGenres(document, max = 3)

        // ---- MissAV enrichment: description + cast ----
        var fetchedDescription: String?    = null
        var fetchedActors: List<ActorData> = emptyList()

        if (!cleanCode.isNullOrBlank()) {
            val missAvSlugCandidates = listOf(
                cleanCode,
                "$cleanCode-uncensored-leak",
                "$cleanCode-english-subtitle"
            )

            for (slug in missAvSlugCandidates) {
                val missAvDoc = runCatching {
                    app.get("$missAvUrl/en/$slug", timeout = 10, headers = browserHeaders).document
                }.getOrNull() ?: continue

                if (fetchedDescription.isNullOrBlank()) {
                    fetchedDescription = extractMissAvDescription(missAvDoc)
                }
                if (fetchedActors.isEmpty()) {
                    fetchedActors = extractMissAvActors(missAvDoc)
                }
                if (!fetchedDescription.isNullOrBlank() && fetchedActors.isNotEmpty()) break
            }
        }

        // ---- Plot: displayTitle + blank line + description ----
        val cleanDesc = fetchedDescription?.trim().orEmpty()
        val plotText: String = if (cleanDesc.isNotEmpty()) {
            displayTitle + "\n\n" + cleanDesc
        } else {
            displayTitle
        }

        val loadDataJson = LoadData(videoUrl, horizontalPoster, dvdId).toJson()

        return newMovieLoadResponse(displayTitle, videoUrl, TvType.NSFW, loadDataJson) {
            // Both posters use the horizontal image on the details page.
            this.posterUrl           = horizontalPoster
            this.backgroundPosterUrl = horizontalPoster

            this.posterHeaders = browserHeaders + mapOf("Referer" to "$mainUrl/")
            this.plot          = plotText
            this.actors        = fetchedActors

            // Only set if the values were found in the HTML
            if (releaseYear != null)     this.year     = releaseYear
            if (durationMinutes != null) this.duration = durationMinutes
            if (genres.isNotEmpty())     this.tags     = genres
        }
    }

    // ==================== Load links (streams) ====================

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

            // 1. MISSAV (Primary Extractor)
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
                                        this.referer = "https://missav.ws/"
                                        this.quality = Qualities.Unknown.value
                                    }
                                )
                                foundStream.set(true)
                            }
                        }
                    }
                }
            }

            // 2. JABLE (Secondary Extractor)
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
                                this.referer = "https://jable.tv/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        foundStream.set(true)
                    }
                }
            }

            // 3. JAVTIFUL
            launch {
                runCatching {
                    val javtifulSearchDoc = app.get("https://javtiful.com/search?q=$cleanCode", headers = browserHeaders).document
                    val path = javtifulSearchDoc.selectFirst("body > main > section.front-section > div > div.front-video-grid > article > a")?.attr("href")
                        ?: javtifulSearchDoc.selectFirst("article a")?.attr("href")

                    if (!path.isNullOrBlank()) {
                        val fullUrl = if (path.startsWith("http")) path else "https://javtiful.com$path"
                        val document = app.get(fullUrl, headers = browserHeaders).document
                        val scriptData = document.selectFirst("script#frontWatchConfig")?.data()

                        if (!scriptData.isNullOrBlank()) {
                            val config = parseJson<JavtifulWatchConfig>(scriptData)
                            config.playerSources?.forEach { source ->
                                if (!source.src.isNullOrBlank()) {
                                    callback.invoke(
                                        newExtractorLink(
                                            "Javtiful",
                                            "Javtiful",
                                            source.src,
                                            if (source.src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                        ) {
                                            this.referer = "https://javtiful.com/"
                                            this.quality = Qualities.Unknown.value
                                        }
                                    )
                                    foundStream.set(true)
                                }
                            }
                        }
                    }
                }
            }

            // 4. SUBTITLES
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
