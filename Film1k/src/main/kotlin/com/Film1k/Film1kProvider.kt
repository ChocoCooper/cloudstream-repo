package com.Film1k

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode

// --- Cinemeta Data Classes ---
data class CinemetaResponse(val meta: CinemetaMeta? = null)

data class CinemetaMeta(
    val name: String? = null,
    val genres: List<String>? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val year: String? = null,
    val cast: List<String>? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val country: String? = null,
    val language: String? = null
)

// --- WP-JSON Data Classes ---
data class WpPost(
    val link: String? = null,
    val title: WpRendered? = null,
    val content: WpRendered? = null,
    val meta: WpMeta? = null
)

data class WpRendered(val rendered: String? = null)
data class WpMeta(val fifu_image_url: String? = null)

// --- Subtitle Data Classes ---
data class StremioSubtitle(
    val id: String? = null,
    val url: String? = null,
    val lang: String? = null,
    val score: Double? = null,
    val downloads: Int? = null
)

data class StremioSubtitlesResponse(val subtitles: List<StremioSubtitle>? = null)

class Film1kProvider : MainAPI() {
    override var mainUrl = "https://www.film1k.com"
    override var name = "Film1k"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val cinemetaBaseUrl = "https://v3-cinemeta.strem.io/meta/movie"
    private val openSubtitlesBaseUrl = "https://opensubtitles-v3.strem.io/subtitles/movie"
    private val openSubtitlesMaxResults = 10
    private val isHorizontalImages = false

    // AJAX endpoint used by the theme's server-switching mechanism
    private val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

    override val mainPage = mainPageOf(
        "$mainUrl/wp-json/wp/v2/posts?tags=11&per_page=8" to "USA Movies",
        "$mainUrl/wp-json/wp/v2/posts?tags=58&per_page=8" to "1990s Movies"
    )

    private val titleJunkRegex = Regex(
        "full movie online|movie poster watch online|watch movie online|watch tv online|watch series online|movie poster|watch online|film1k",
        RegexOption.IGNORE_CASE
    )

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(titleJunkRegex, "")
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\s+"), " ")
            .trim(' ', '-', '|', ':')
            .trim()
    }

    private fun getImageUrl(el: Element?): String? {
        if (el == null) return null
        return el.attr("data-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: el.attr("data-lazy-src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: el.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
    }

    private fun extractDetailPoster(doc: Document): String? {
        val imgEl = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > img")
            ?: doc.selectFirst("article img")
        val manualPoster = getImageUrl(imgEl)
        val ogPoster = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        return manualPoster ?: ogPoster
    }

    private suspend fun getValidImageUrl(primaryUrl: String?, fallbackUrl: String?): String? {
        val primary = primaryUrl?.takeIf { it.isNotBlank() && !it.equals("N/A", ignoreCase = true) }
        val fallback = fallbackUrl?.takeIf { it.isNotBlank() }
        if (primary == null) return fallback
        return try {
            val isValid = app.head(primary, cacheTime = 1440).code == 200
            if (isValid) primary else fallback
        } catch (e: Exception) {
            fallback
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        val responseText = try {
            app.get(url, verify = false).text
        } catch (e: Exception) {
            return newHomePageResponse(emptyList())
        }
        val wpPosts = tryParseJson<List<WpPost>>(responseText) ?: emptyList()
        val items = parseWpPosts(wpPosts)
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = isHorizontalImages),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$query&per_page=15&orderby=relevance"
        val responseText = try {
            app.get(apiUrl, verify = false).text
        } catch (e: Exception) {
            return emptyList()
        }
        val wpPosts = tryParseJson<List<WpPost>>(responseText) ?: return emptyList()
        return parseWpPosts(wpPosts)
    }

    private fun parseWpPosts(wpPosts: List<WpPost>): List<SearchResponse> {
        return wpPosts.take(8).mapNotNull { post ->
            val mediaUrl = post.link?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val rawName = post.title?.rendered ?: return@mapNotNull null
            val mediaName = cleanTitle(rawName)

            val imdbId = post.content?.rendered?.let { html ->
                Regex("imdb\\.com/title/(tt\\d+)").find(html)?.groupValues?.get(1)
                    ?: Regex("tt\\d{7,8}").find(html)?.value
            }

            val cinemetaPosterUrl = imdbId?.let {
                "https://images.metahub.space/poster/medium/$it/img"
            }

            val wpPosterUrl = post.meta?.fifu_image_url?.takeIf { it.isNotBlank() }
                ?: post.content?.rendered?.let { html ->
                    Regex("src=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                }?.let { fixUrl(it) }

            val finalPosterUrl = cinemetaPosterUrl ?: wpPosterUrl
            val yearInt = Regex("\\((\\d{4})\\)").find(rawName)?.groupValues?.get(1)?.toIntOrNull()

            newMovieSearchResponse(mediaName, mediaUrl, TvType.NSFW) {
                this.posterUrl = finalPosterUrl
                this.year = yearInt
            }
        }
    }

    private suspend fun fetchCinemetaData(imdbId: String): CinemetaMeta? {
        return try {
            val responseText = app.get("$cinemetaBaseUrl/$imdbId.json", cacheTime = 1440).text
            tryParseJson<CinemetaResponse>(responseText)?.meta
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetchOpenSubtitles(imdbId: String): List<SubtitleFile> {
        val requestUrl = "$openSubtitlesBaseUrl/$imdbId.json"
        val responseText = try {
            app.get(requestUrl, cacheTime = 1440).text
        } catch (e: Exception) {
            return emptyList()
        }
        val parsed = tryParseJson<StremioSubtitlesResponse>(responseText) ?: return emptyList()
        return parsed.subtitles
            ?.filter { it.lang.equals("eng", ignoreCase = true) || it.lang.equals("en", ignoreCase = true) }
            ?.filter { !it.url.isNullOrBlank() }
            ?.sortedWith(
                compareByDescending<StremioSubtitle> { it.downloads ?: -1 }
                    .thenByDescending { it.score ?: -1.0 }
            )
            ?.take(openSubtitlesMaxResults)
            ?.mapNotNull { sub -> sub.url?.let { SubtitleFile("English", it) } }
            ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, verify = false, cacheTime = 1440).document
        val manualPosterUrl = extractDetailPoster(doc)?.let { fixUrl(it) }

        val manualRawName = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div > img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("h1.entry-title, h2.entry-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: doc.title()

        val manualMediaName = cleanTitle(manualRawName)

        var manualPlot: String? = null
        val descContainer = doc.selectFirst("#Ez-Wp > div > div.Container > div > aside > div > div")

        fun cleanupText(text: String): String {
            return text.replace(Regex("\\s+([.,;:!?])"), "$1")
                .replace(Regex("\\s+"), " ").trim()
        }

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
            var extracted = cleanupText(builder.toString()).removePrefix(",").removePrefix(":").trim()
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
            if (text.startsWith(titleText)) text = text.removePrefix(titleText)
            text = cleanupText(text).removePrefix(",").removePrefix(":").trim()
            if (text.isBlank()) return null
            val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            val filtered = sentences.filterNot { it.contains("Film1k", ignoreCase = true) }
                .joinToString(" ").trim()
            return filtered.ifBlank { text }.ifBlank { null }
        }

        manualPlot = extractAfterLabel("Description") ?: extractAfterLabel("Plot") ?: extractTitleLeadParagraph()
        manualPlot = manualPlot?.let { cleanupText(it) }
            ?.replace("^\\s*:\\s*".toRegex(), "")
            ?.replace("^\\s*\"|\"\\s*$".toRegex(), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val tags1 = doc.select("#Ez-Wp > div > div.Container > div > aside > div > p:nth-child(4) > a").map { it.text() }
        val tags2 = doc.select("#Ez-Wp > div > div.Container > div > aside > div > p:nth-child(6) > a").map { it.text() }
        val manualTags = (tags1 + tags2).filter { it.isNotBlank() }.distinct()

        val imdbId = Regex("imdb\\.com/title/(tt\\d+)").find(doc.html())?.groupValues?.get(1)
            ?: Regex("tt\\d{7,8}").find(doc.html())?.value

        val cinemeta = imdbId?.let { fetchCinemetaData(it) }

        val mediaName = cinemeta?.name?.takeIf { it.isNotBlank() } ?: manualMediaName
        val yearInt = cinemeta?.year?.toIntOrNull()
            ?: cinemeta?.releaseInfo?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        val finalPosterUrl = getValidImageUrl(cinemeta?.poster, manualPosterUrl)
        val finalBackgroundUrl = getValidImageUrl(cinemeta?.background, finalPosterUrl)
        val plot = cinemeta?.description?.takeIf { it.isNotBlank() } ?: manualPlot

        val allTags = mutableListOf<String>()
        cinemeta?.genres?.takeIf { it.isNotEmpty() }?.let { allTags.addAll(it) }
        cinemeta?.country?.takeIf { it.isNotBlank() }?.let { allTags.add(it) }
        cinemeta?.language?.takeIf { it.isNotBlank() }?.let { allTags.add(it) }
        if (allTags.isEmpty() && manualTags.isNotEmpty()) allTags.addAll(manualTags)

        val allActors = mutableListOf<ActorData>()
        cinemeta?.cast?.forEach { castName ->
            allActors.add(ActorData(Actor(castName), roleString = "Cast"))
        }

        val durationInt = cinemeta?.runtime?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }
        val ratingText = cinemeta?.imdbRating?.takeIf { it.isNotBlank() }

        return newMovieLoadResponse(mediaName, url, TvType.Movie, url) {
            this.posterUrl = finalPosterUrl
            this.backgroundPosterUrl = finalBackgroundUrl
            this.logoUrl = cinemeta?.logo
            this.year = yearInt
            this.plot = plot
            this.tags = allTags.distinct()
            this.score = ratingText?.let { Score.from10(it) }
            this.duration = durationInt
            if (allActors.isNotEmpty()) this.actors = allActors
        }
    }

    // ------------------------------------------------------------------
    // Server URL fetch via the confirmed AJAX mechanism
    // ------------------------------------------------------------------

    /**
     * Calls the theme's action_change_player_eroz endpoint for the given
     * server key (0-3), and extracts the iframe src from the returned
     * HTML fragment.
     *
     * Confirmed from the theme's JS (dump_theme_litespeed.js):
     *   $.ajax({ url: erozPublic.url, method: 'POST',
     *            data: { action: 'action_change_player_eroz',
     *                    ide: ide, key: key } })
     */
    private suspend fun fetchServerEmbed(
        postId: String,
        key: Int,
        referer: String
    ): String? {
        return try {
            val response = app.post(
                ajaxUrl,
                data = mapOf(
                    "action" to "action_change_player_eroz",
                    "ide" to postId,
                    "key" to key.toString()
                ),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                referer = referer,
                verify = false
            )

            val json = JSONObject(response.text)
            val videoHtml = json.optString("video", "")
            if (videoHtml.isBlank()) return null

            // Extract iframe src (case-insensitive to handle Option 4's
            // uppercase <IFRAME SRC="...">)
            Regex(
                """<iframe[^>]+src\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(videoHtml)?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Routes an embed URL to the appropriate extractor based on hostname.
     */
    private suspend fun routeToExtractor(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            // Option 1 — Film1k native (already implemented)
            embedUrl.contains("film1k.xyz") -> {
                Film1kExtractor().getUrl(embedUrl, mainUrl, subtitleCallback, callback)
            }
            // Option 3 — TurboVidHLS (direct MP4 in HTML)
            embedUrl.contains("turbovidhls.com") -> {
                TurboVidHLSExtractor().getUrl(embedUrl, mainUrl, subtitleCallback, callback)
            }
            // Option 4 — HgCloud (WebViewResolver required)
            embedUrl.contains("hgcloud.to") -> {
                HgCloudExtractor().getUrl(embedUrl, mainUrl, subtitleCallback, callback)
            }
            // Option 2 — AbyssPlayer (skipped)
            embedUrl.contains("abyssplayer.com") -> {
                // Intentionally skipped per user request
            }
            // Direct media fallback
            embedUrl.contains(".mp4") || embedUrl.contains(".m3u8") -> {
                callback.invoke(
                    newExtractorLink(
                        name = "Film1k Direct",
                        source = "Film1k Direct",
                        url = embedUrl,
                        type = if (embedUrl.contains(".m3u8"))
                            ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            // Let CloudStream's native extractor registry try
            else -> {
                loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {

        // 1. Fetch the detail page first — this establishes cookies needed
        //    by the AJAX calls
        val doc = try {
            app.get(data, verify = false, cacheTime = 0).document
        } catch (e: Exception) {
            return@coroutineScope false
        }

        val collectedLinks = mutableListOf<ExtractorLink>()
        val collectingCallback: (ExtractorLink) -> Unit = { link -> collectedLinks.add(link) }

        // 2. Kick off subtitle fetch in parallel
        val subtitleJob = async {
            val docText = doc.html()
            val imdbId = Regex("imdb\\.com/title/(tt\\d+)").find(docText)?.groupValues?.get(1)
                ?: Regex("tt\\d{7,8}").find(docText)?.value
            if (imdbId != null) fetchOpenSubtitles(imdbId) else emptyList()
        }

        // 3. Extract post ID (used by AJAX)
        val postId = doc.selectFirst("[data-ide]")?.attr("data-ide")
            ?: Regex("""data-ide=["'](\d+)["']""").find(doc.html())?.groupValues?.get(1)

        // 4. Collect embed URLs — both from static HTML and from AJAX
        val embedUrls = mutableListOf<String>()

        // 4a. Static extraction (Option 1's direct source is often present)
        doc.select("#my-video > source").forEach { source ->
            val src = getImageUrl(source)
            if (!src.isNullOrBlank()) embedUrls.add(fixUrl(src))
        }
        doc.select("#video-op-a > div > iframe").forEach { iframe ->
            val src = getImageUrl(iframe)
            if (!src.isNullOrBlank()) embedUrls.add(fixUrl(src))
        }

        // 4b. AJAX-based extraction — iterate all 4 servers
        if (postId != null) {
            val ajaxResults = (0..3).map { key ->
                async { fetchServerEmbed(postId, key, data) }
            }.awaitAll()

            ajaxResults.filterNotNull().forEach { url ->
                if (!embedUrls.contains(url)) embedUrls.add(url)
            }
        }

        // 5. Route each embed URL to the correct extractor
        embedUrls.map { videoUrl ->
            async {
                routeToExtractor(videoUrl, subtitleCallback, collectingCallback)
            }
        }.awaitAll()

        // 6. Emit subtitles
        subtitleJob.await().forEach { subtitleCallback(it) }

        // 7. Emit the best link (M3U8 first, then others)
        val sortedLinks = collectedLinks.sortedByDescending { it.type == ExtractorLinkType.M3U8 }
        sortedLinks.firstOrNull()?.let { callback.invoke(it) }

        return@coroutineScope collectedLinks.isNotEmpty()
    }
}
