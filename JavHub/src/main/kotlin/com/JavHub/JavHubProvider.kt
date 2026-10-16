package com.JavHub

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

data class LoadData(
    val url: String,
    val poster: String? = null,
    val code: String? = null
)

class JavHubProvider : MainAPI() {
    override var mainUrl              = "https://javtrailers.com"
    override var name                 = "JavHub"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)

    private val subtitleCatUrl = "https://www.subtitlecat.com"
    private val missAvUrl      = "https://missav.ws"

    private val ignoredGenres = setOf(
        "Hi-Def", "4K", "Exclusive Distribution", "Featured Actress"
    ).map { it.lowercase() }.toSet()

    // One-shot instantiation of each extractor.
    private val extractors: List<JavExtractor> = listOf(
        MissAvExtractor(),
        JableExtractor(),
        JavtifulExtractor(),
        JavguruExtractor()
    )

    override val mainPage = mainPageOf(
        "Madonna" to "Madonna",
        "NSFS"    to "Nagae Style"
    )

    // ------- (unchanged helpers: extractCode, verticalImageUrl,
    //          horizontalImageUrl, findInfoRow, extractReleaseYear,
    //          extractDurationMinutes, extractGenres,
    //          extractMissAvDescription, extractMissAvActors,
    //          parseSearchPage, getMainPage, search, load) -------

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
            val document = app.get(videoUrl, timeout = 15, headers = BROWSER_HEADERS).document
            val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: ""
            code = extractCode(rawTitle)
        }
        if (code == null) return false

        val cleanCode   = code.lowercase()
        val foundStream = AtomicBoolean(false)

        coroutineScope {

            // ---- Run every extractor concurrently ----
            extractors.forEach { extractor ->
                launch {
                    runCatching {
                        if (extractor.extract(cleanCode, callback)) {
                            foundStream.set(true)
                        }
                    }
                }
            }

            // ---- Subtitles (kept inline) ----
            launch {
                runCatching {
                    val searchDoc = app.get(
                        "$subtitleCatUrl/index.php?search=$code",
                        timeout = 15,
                        headers = BROWSER_HEADERS
                    ).document

                    val foundSubs = mutableListOf<String>()
                    val subtitlePageLinks = searchDoc
                        .select("table.sub-table > tbody > tr > td > a[href^=\"subs/\"]")
                        .filter { it.text().contains(code, ignoreCase = true) }
                        .mapNotNull { el ->
                            el.attr("href").let { href ->
                                if (href.startsWith("http")) href
                                else "$subtitleCatUrl/$href"
                            }
                        }
                        .distinct()

                    subtitlePageLinks.forEach { subPageUrl ->
                        runCatching {
                            val subPageDoc = app.get(
                                subPageUrl,
                                timeout = 10,
                                headers = BROWSER_HEADERS
                            ).document

                            val enHref = subPageDoc
                                .selectFirst("div.sub-single > span > a#download_en")
                                ?.attr("href")

                            if (!enHref.isNullOrBlank()) {
                                val fullEnUrl = if (enHref.startsWith("http")) enHref
                                                else "$subtitleCatUrl/$enHref"
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

    // --------------------------------------------------------------
    // The helpers below are the SAME as your original file. They are
    // reproduced here so the provider remains a single, self-contained
    // unit — no behavioural change was made to them.
    // --------------------------------------------------------------

    private fun extractCode(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""\b([a-zA-Z0-9]{2,8}(?:-[a-zA-Z0-9]{2,8})?-\d{2,6})\b""")
        return regex.find(text)?.value?.uppercase()
    }

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

    private fun horizontalImageUrl(doc: Document): String? {
        val url = doc.selectFirst("meta[property=og:image]")?.attr("content")?.trim()
        if (url.isNullOrBlank() || url.startsWith("data:")) return null
        return fixUrlNull(url)
    }

    private fun findInfoRow(doc: Document, vararg labels: String): String? {
        val normalizedLabels = labels.map { it.trim().lowercase().removeSuffix(":") }
        for (p in doc.select("#info-row p")) {
            val spanText = p.selectFirst("span")?.text()?.trim()?.lowercase()?.removeSuffix(":")
                ?: continue
            if (spanText in normalizedLabels) {
                val labelSpan = p.selectFirst("span")
                val fullText  = p.text().trim()
                val labelText = labelSpan?.text()?.trim().orEmpty()
                val value = if (labelText.isNotEmpty() && fullText.startsWith(labelText)) {
                    fullText.removePrefix(labelText).trim()
                } else fullText
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

    private fun extractGenres(doc: Document): List<String> {
        for (p in doc.select("#info-row p")) {
            val spanText = p.selectFirst("span")?.text()?.trim()?.lowercase()?.removeSuffix(":")
                ?: continue
            if (spanText == "categories") {
                return p.select("a")
                    .map { it.text().trim().decodeHtmlEntities() }
                    .filter { it.isNotBlank() }
                    .filter { it.lowercase() !in ignoredGenres }
                    .distinct()
            }
        }
        return emptyList()
    }

    private fun extractMissAvDescription(doc: Document): String? {
        val descEl = doc.selectFirst("div.mb-1.text-secondary.break-all") ?: return null
        return descEl.text()?.trim()?.decodeHtmlEntities()?.trim()?.ifBlank { null }
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val encoded = URLEncoder.encode(request.data, "UTF-8")
        val url = if (page <= 1) "$mainUrl/search/$encoded"
                  else "$mainUrl/search/$encoded?page=$page"
        val document = app.get(url, headers = BROWSER_HEADERS).document
        val items = parseSearchPage(document)
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return coroutineScope {
            (1..2).map { page ->
                async {
                    runCatching {
                        val url = if (page == 1) "$mainUrl/search/$encoded"
                                  else "$mainUrl/search/$encoded?page=$page"
                        val document = app.get(
                            url,
                            headers = BROWSER_HEADERS,
                            timeout = 15
                        ).document
                        parseSearchPage(document)
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.url }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val loadData = runCatching { parseJson<LoadData>(url) }.getOrNull()
        val videoUrl = loadData?.url ?: url

        val document = app.get(videoUrl, headers = BROWSER_HEADERS).document

        val rawTitle = document.selectFirst("h1")?.text()?.trim()?.decodeHtmlEntities()
            ?: loadData?.code
            ?: "Unknown"

        val dvdId = loadData?.code ?: extractCode(rawTitle)
        val displayTitle = if (!dvdId.isNullOrBlank() &&
                                !rawTitle.startsWith(dvdId, ignoreCase = true)) {
            "$dvdId $rawTitle"
        } else rawTitle

        val cleanCode = dvdId?.lowercase()

        val verticalPoster: String? = verticalImageUrl(
            document.selectFirst("#thumbnailContainer img")
        )
        val horizontalPoster: String? = horizontalImageUrl(document)

        val releaseYear: Int?     = extractReleaseYear(document)
        val durationMinutes: Int? = extractDurationMinutes(document)
        val genres: List<String>  = extractGenres(document)

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
                    app.get("$missAvUrl/en/$slug", timeout = 10, headers = BROWSER_HEADERS).document
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

        val cleanDesc = fetchedDescription?.trim().orEmpty()
        val plotText: String = if (cleanDesc.isNotEmpty()) {
            val separator = if (displayTitle.trimEnd().endsWith(".")) " " else ". "
            displayTitle.trimEnd() + separator + cleanDesc
        } else displayTitle

        val loadDataJson = LoadData(videoUrl, verticalPoster, dvdId).toJson()

        return newMovieLoadResponse(displayTitle, videoUrl, TvType.NSFW, loadDataJson) {
            this.posterUrl = verticalPoster
            this.backgroundPosterUrl = horizontalPoster
            this.posterHeaders = BROWSER_HEADERS + mapOf("Referer" to "$mainUrl/")
            this.plot          = plotText
            this.actors        = fetchedActors

            if (releaseYear != null)     this.year     = releaseYear
            if (durationMinutes != null) this.duration = durationMinutes
            if (genres.isNotEmpty())     this.tags     = genres
        }
    }
}
