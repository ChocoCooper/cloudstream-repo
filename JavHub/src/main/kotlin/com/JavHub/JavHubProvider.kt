package com.JavHub

import android.util.Base64
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

// ============ JavTrailers API models ============

data class JavTrailersVideo(
    @JsonProperty("_id")         val id: String? = null,
    @JsonProperty("title")       val title: String? = null,
    @JsonProperty("jpTitle")     val jpTitle: String? = null,
    @JsonProperty("contentId")   val contentId: String? = null,
    @JsonProperty("dvdId")       val dvdId: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("duration")    val duration: Int? = null,
    @JsonProperty("image")       val image: String? = null
)

data class JavTrailersListResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("count")   val count: Int? = null,
    @JsonProperty("videos")  val videos: List<JavTrailersVideo>? = null
)

data class JavTrailersDetailResponse(
    @JsonProperty("success") val success: Boolean? = null,
    @JsonProperty("video")   val video: JavTrailersVideo? = null
)

data class MeiliSearchResponse(
    @JsonProperty("hits")       val hits: List<MeiliHit>? = null,
    @JsonProperty("totalHits")  val totalHits: Int? = null,
    @JsonProperty("page")       val page: Int? = null,
    @JsonProperty("totalPages") val totalPages: Int? = null
)

data class MeiliHit(
    @JsonProperty("_id")         val id: String? = null,
    @JsonProperty("id")          val id2: String? = null,
    @JsonProperty("title")       val title: String? = null,
    @JsonProperty("enTitle")     val enTitle: String? = null,
    @JsonProperty("jpTitle")     val jpTitle: String? = null,
    @JsonProperty("contentId")   val contentId: String? = null,
    @JsonProperty("dvdId")       val dvdId: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("duration")    val duration: Int? = null,
    @JsonProperty("image")       val image: String? = null
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

    // ---- JavTrailers endpoints / tokens ----
    private val JAVTRAILERS_API          = "$mainUrl/api"
    private val JAVTRAILERS_AUTH         = "AELAbPQCh_fifd93wMvf_kxMD_fqkUAVf@BVgb2!md@TNW8bUEopFExyGCoKRcZX"
    private val JAVTRAILERS_SEARCH_TOKEN = "e8f7f0a9891342bcde8aeee404526aa3c94ba743b914d1211456201d64318788"
    private val JAVTRAILERS_SEARCH_HOST  = "https://search.javtrailers.com"
    private val JAVTRAILERS_IMAGE_BASE   = "https://images.javtrailers.com/digital/video"

    // Madonna studio Mongo _id (used as home page section identifier)
    private val MADONNA_STUDIO_ID = "5b2934755b1ff448d9a7b700"

    override val mainPage = mainPageOf(
        MADONNA_STUDIO_ID to "Madonna"
    )

    // ==================== Title / code helpers ====================

    private fun cleanTitleText(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return raw
            .trim('-', '_', '|', ' ', '[', ']')
            .replace(Regex("""\s{2,}"""), " ")
            .trim()
            .ifBlank { null }
    }

    private fun extractCode(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val regex = Regex("""\b([a-zA-Z0-9]{2,8}(?:-[a-zA-Z0-9]{2,8})?-\d{2,6})\b""")
        return regex.find(text)?.value?.uppercase()
    }

    private fun buildBgCoverUrl(contentId: String?): String? {
        if (contentId.isNullOrBlank()) return null
        return "$JAVTRAILERS_IMAGE_BASE/$contentId/${contentId}pl.w800.webp"
    }

    private fun extractContentIdFromUrl(url: String): String? {
        return Regex("""/video/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1)
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

        return text?.trim()?.decodeHtmlEntities()?.ifBlank { null }
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

    // ==================== Mapping helpers ====================

    private fun JavTrailersVideo.toSearchResult(): SearchResponse? {
        val cid = contentId ?: return null
        val dvd = dvdId ?: return null
        val rawTitle = title ?: jpTitle ?: return null

        val displayTitle = "$dvd $rawTitle"
        val poster = buildBgCoverUrl(cid) ?: image

        val data = LoadData(
            url = "$mainUrl/video/$cid",
            poster = poster,
            code = dvd
        ).toJson()

        return newMovieSearchResponse(displayTitle, data, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    private fun MeiliHit.toSearchResult(): SearchResponse? {
        val cid = contentId ?: return null
        val dvd = dvdId ?: return null
        val rawTitle = title ?: enTitle ?: jpTitle ?: return null

        val displayTitle = "$dvd $rawTitle"
        val poster = buildBgCoverUrl(cid) ?: image

        val data = LoadData(
            url = "$mainUrl/video/$cid",
            poster = poster,
            code = dvd
        ).toJson()

        return newMovieSearchResponse(displayTitle, data, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ==================== Home page ====================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val studioId = request.data
        val apiPage  = (page - 1).coerceAtLeast(0)
        val url      = "$JAVTRAILERS_API/videos?studio=$studioId&page=$apiPage"

        val headers = browserHeaders + mapOf(
            "Authorization" to JAVTRAILERS_AUTH,
            "Accept"        to "*/*",
            "Referer"       to "$mainUrl/videos"
        )

        val response = app.get(url, headers = headers).text
        val data     = parseJson<JavTrailersListResponse>(response)
        val videos   = data.videos ?: emptyList()
        val items    = videos.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, items, hasNext = videos.size >= 24)
    }

    // ==================== Search ====================

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")

        return coroutineScope {
            (1..2).map { page ->
                async {
                    runCatching {
                        val url = "$JAVTRAILERS_SEARCH_HOST/indexes/videos/search" +
                                "?q=$encoded&page=$page&sort=releaseDate:desc&hitsPerPage=24"
                        val headers = browserHeaders + mapOf(
                            "Authorization" to "Bearer $JAVTRAILERS_SEARCH_TOKEN",
                            "Accept"        to "*/*",
                            "Origin"        to mainUrl,
                            "Referer"       to "$mainUrl/"
                        )
                        val response = app.get(url, headers = headers, timeout = 15).text
                        val data = parseJson<MeiliSearchResponse>(response)
                        (data.hits ?: emptyList()).mapNotNull { it.toSearchResult() }
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.url }
        }
    }

    // ==================== Load ====================

    override suspend fun load(url: String): LoadResponse {
        val loadData = runCatching { parseJson<LoadData>(url) }.getOrNull()
        val videoUrl = loadData?.url ?: url

        val contentId = extractContentIdFromUrl(videoUrl)
            ?: loadData?.code?.lowercase()

        // Fetch JavTrailers detail API for bg cover + canonical metadata
        var detailVideo: JavTrailersVideo? = null
        if (!contentId.isNullOrBlank()) {
            val headers = browserHeaders + mapOf(
                "Authorization" to JAVTRAILERS_AUTH,
                "Accept"        to "*/*",
                "Referer"       to "$mainUrl/video/$contentId"
            )
            val detailResp = runCatching {
                app.get("$JAVTRAILERS_API/video/$contentId", headers = headers, timeout = 15).text
            }.getOrNull()
            detailVideo = detailResp
                ?.let { runCatching { parseJson<JavTrailersDetailResponse>(it) }.getOrNull() }
                ?.video
        }

        // Background cover (w800 webp) — from detail API, else constructed
        val bgCover = detailVideo?.image
            ?: loadData?.poster
            ?: buildBgCoverUrl(contentId)

        // DVD id / title
        val dvdId = detailVideo?.dvdId ?: loadData?.code
        val rawTitle = detailVideo?.title ?: loadData?.code ?: "Unknown"
        val displayTitle = if (!dvdId.isNullOrBlank() &&
                                !rawTitle.startsWith(dvdId, ignoreCase = true)) {
            "$dvdId $rawTitle"
        } else rawTitle

        val cleanCode = dvdId?.lowercase()

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

        val plotText = fetchedDescription?.ifBlank { null } ?: rawTitle
        val loadDataJson = LoadData(videoUrl, bgCover, dvdId).toJson()

        return newMovieLoadResponse(displayTitle, videoUrl, TvType.NSFW, loadDataJson) {
            this.posterUrl           = bgCover
            this.backgroundPosterUrl = bgCover
            this.plot                = plotText
            this.actors              = fetchedActors
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
