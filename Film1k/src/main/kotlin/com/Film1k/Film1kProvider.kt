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

    private val TAG = "Film1kDebug"

    private val cinemetaBaseUrl = "https://v3-cinemeta.strem.io/meta/movie"
    private val openSubtitlesBaseUrl = "https://opensubtitles-v3.strem.io/subtitles/movie"
    private val openSubtitlesMaxResults = 10
    private val isHorizontalImages = false
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

    /**
     * POST to admin-ajax.php with action_change_player_eroz.
     * Logs everything for diagnostics.
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
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to mainUrl,
                    "Accept" to "application/json, text/javascript, */*; q=0.01"
                ),
                referer = referer,
                verify = false
            )

            val body = response.text
            android.util.Log.d(TAG, "AJAX key=$key status=${response.code} len=${body.length}")
            android.util.Log.d(TAG, "AJAX key=$key body=${body.take(400)}")

            val json = JSONObject(body)
            val videoHtml = json.optString("video", "")
            if (videoHtml.isBlank()) {
                android.util.Log.w(TAG, "AJAX key=$key: empty 'video' field")
                return null
            }

            val src = Regex(
                """<iframe[^>]+src\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(videoHtml)?.groupValues?.get(1)

            android.util.Log.d(TAG, "AJAX key=$key: iframe src=$src")
            src
        } catch (e: Exception) {
            android.util.Log.e(TAG, "AJAX key=$key failed", e)
            null
        }
    }

    private suspend fun routeToExtractor(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            embedUrl.contains("film1k.xyz") -> {
                android.util.Log.d(TAG, "Route → Film1kExtractor: $embedUrl")
                Film1kExtractor().getUrl(embedUrl, mainUrl, subtitleCallback, callback)
            }
            embedUrl.contains("turbovidhls.com") -> {
                android.util.Log.d(TAG, "Route → TurboVidHLSExtractor: $embedUrl")
                TurboVidHLSExtractor().getUrl(embedUrl, mainUrl, subtitleCallback, callback)
            }
            embedUrl.contains("hgcloud.to") -> {
                android.util.Log.d(TAG, "Route → HgCloudExtractor: $embedUrl")
                HgCloudExtractor().getUrl(embedUrl, mainUrl, subtitleCallback, callback)
            }
            embedUrl.contains("abyssplayer.com") -> {
                android.util.Log.d(TAG, "Route → AbyssPlayer (skipped): $embedUrl")
            }
            embedUrl.contains(".mp4") || embedUrl.contains(".m3u8") -> {
                android.util.Log.d(TAG, "Route → Direct media: $embedUrl")
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
            else -> {
                android.util.Log.d(TAG, "Route → loadExtractor: $embedUrl")
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

        android.util.Log.d(TAG, "=== loadLinks START: $data ===")

        val doc = try {
            app.get(data, verify = false, cacheTime = 0).document
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to fetch detail page", e)
            return@coroutineScope false
        }
        android.util.Log.d(TAG, "Detail page fetched, html len=${doc.html().length}")

        val collectedLinks = mutableListOf<ExtractorLink>()
        val collectingCallback: (ExtractorLink) -> Unit = { link ->
            android.util.Log.d(TAG, ">>> Link collected: ${link.url} type=${link.type}")
            collectedLinks.add(link)
        }

        val subtitleJob = async {
            val docText = doc.html()
            val imdbId = Regex("imdb\\.com/title/(tt\\d+)").find(docText)?.groupValues?.get(1)
                ?: Regex("tt\\d{7,8}").find(docText)?.value
            if (imdbId != null) fetchOpenSubtitles(imdbId) else emptyList()
        }

        val postId = doc.selectFirst("[data-ide]")?.attr("data-ide")
            ?: Regex("""data-ide=["'](\d+)["']""").find(doc.html())?.groupValues?.get(1)
        android.util.Log.d(TAG, "postId=$postId")

        val embedUrls = mutableListOf<String>()

        // Static extraction
        doc.select("#my-video > source").forEach { source ->
            val src = getImageUrl(source)
            if (!src.isNullOrBlank()) {
                val fixed = fixUrl(src)
                embedUrls.add(fixed)
                android.util.Log.d(TAG, "Static <source>: $fixed")
            }
        }
        doc.select("#video-op-a > div > iframe").forEach { iframe ->
            val src = getImageUrl(iframe)
            if (!src.isNullOrBlank()) {
                val fixed = fixUrl(src)
                embedUrls.add(fixed)
                android.util.Log.d(TAG, "Static <iframe>: $fixed")
            }
        }
        doc.select("#Eroz > div > ul > li > a").forEach { aTag ->
            val href = aTag.attr("href").ifBlank { aTag.attr("data-link") }
            if (href.isNotBlank()) {
                val fixed = fixUrl(href)
                embedUrls.add(fixed)
                android.util.Log.d(TAG, "Static <a>: $fixed")
            }
        }

        // AJAX extraction for keys 0..3
        if (postId != null) {
            android.util.Log.d(TAG, "Calling AJAX for keys 0..3")
            val ajaxResults = (0..3).map { key ->
                async { key to fetchServerEmbed(postId, key, data) }
            }.awaitAll()

            ajaxResults.forEach { (key, url) ->
                if (url != null && !embedUrls.contains(url)) {
                    embedUrls.add(url)
                    android.util.Log.d(TAG, "AJAX added key=$key url=$url")
                }
            }
        } else {
            android.util.Log.w(TAG, "No postId — AJAX extraction skipped")
        }

        android.util.Log.d(TAG, "Total embedUrls=${embedUrls.size}")
        embedUrls.forEachIndexed { i, u -> android.util.Log.d(TAG, "  [$i] $u") }

        embedUrls.map { videoUrl ->
            async {
                try {
                    routeToExtractor(videoUrl, subtitleCallback, collectingCallback)
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "Route failed for $videoUrl", e)
                }
            }
        }.awaitAll()

        subtitleJob.await().forEach { subtitleCallback(it) }

        android.util.Log.d(TAG, "Total collected links=${collectedLinks.size}")
        val sortedLinks = collectedLinks.sortedByDescending { it.type == ExtractorLinkType.M3U8 }
        sortedLinks.firstOrNull()?.let {
            android.util.Log.d(TAG, ">>> Emitting link: ${it.url}")
            callback.invoke(it)
        } ?: android.util.Log.w(TAG, "No links collected — nothing to emit")

        android.util.Log.d(TAG, "=== loadLinks END ===")

        return@coroutineScope collectedLinks.isNotEmpty()
    }
}
