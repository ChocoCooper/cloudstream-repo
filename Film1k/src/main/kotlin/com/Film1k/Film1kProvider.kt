package com.Film1k

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.net.URI
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

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

    // ==================================================================
    // TOGGLE FLAGS
    // ==================================================================
    private val ENABLE_HGCLOUD = false

    // ==================================================================
    // CACHES & STATE (companion so they live for the process lifetime)
    // ==================================================================
    companion object {
        // 1.3 — Persistent (session) Cinemeta cache
        private val cinemetaCache = ConcurrentHashMap<String, CinemetaMeta>()

        // 1.2 — Fire-and-forget scope for pre-warming
        private val prewarmScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        // 2.2 — Dead-host tracking
        private class HostFailure(var count: Int, var lastFailureMs: Long)
        private val deadHosts = ConcurrentHashMap<String, HostFailure>()
        private const val DEAD_HOST_THRESHOLD = 3
        private const val DEAD_HOST_TTL_MS = 5 * 60 * 1000L
    }

    private val cinemetaBaseUrl = "https://v3-cinemeta.strem.io/meta/movie"
    private val openSubtitlesBaseUrl = "https://opensubtitles-v3.strem.io/subtitles/movie"
    private val openSubtitlesMaxResults = 10
    private val isHorizontalImages = false
    private val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"

    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/wp-json/wp/v2/posts?tags=11&per_page=8" to "USA Movies",
        "$mainUrl/wp-json/wp/v2/posts?tags=58&per_page=8" to "1990s Movies"
    )

    private val titleJunkRegex = Regex(
        "full movie online|movie poster watch online|watch movie online|watch tv online|watch series online|movie poster|watch online|film1k",
        RegexOption.IGNORE_CASE
    )

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

    // 2.2 — Dead host tracking
    private fun getHost(url: String): String? = try {
        URI(url).host
    } catch (_: Throwable) {
        null
    }

    private fun isHostDead(url: String): Boolean {
        val host = getHost(url) ?: return false
        val failure = deadHosts[host] ?: return false
        if (System.currentTimeMillis() - failure.lastFailureMs > DEAD_HOST_TTL_MS) {
            deadHosts.remove(host)
            return false
        }
        return failure.count >= DEAD_HOST_THRESHOLD
    }

    private fun markHostFailure(url: String) {
        val host = getHost(url) ?: return
        val now = System.currentTimeMillis()
        deadHosts.compute(host) { _, existing ->
            if (existing == null) HostFailure(1, now)
            else {
                existing.count += 1
                existing.lastFailureMs = now
                existing
            }
        }
        val count = deadHosts[host]?.count ?: 0
        if (count >= DEAD_HOST_THRESHOLD) {
            android.util.Log.e(TAG, "markHostFailure: $host marked DEAD (count=$count)")
        }
    }

    private fun markHostSuccess(url: String) {
        getHost(url)?.let {
            deadHosts.remove(it)
        }
    }

    // ------------------------------------------------------------------
    // Homepage & search
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}&page=$page"
        android.util.Log.e(TAG, "getMainPage URL: $url")

        // 2.1 — retry on transient network failures
        val responseText = Film1kResolver.retry(times = 3, initialDelayMs = 500) {
            val r = app.get(url, headers = browserHeaders, verify = false)
            android.util.Log.e(TAG, "getMainPage status=${r.code} len=${r.text.length}")
            r.text
        } ?: run {
            android.util.Log.e(TAG, "getMainPage FAILED after retries")
            return newHomePageResponse(emptyList())
        }

        val wpPosts = tryParseJson<List<WpPost>>(responseText) ?: run {
            android.util.Log.e(TAG, "getMainPage: JSON parse returned null")
            return newHomePageResponse(emptyList())
        }
        android.util.Log.e(TAG, "getMainPage: parsed ${wpPosts.size} posts")
        val items = parseWpPosts(wpPosts)
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = isHorizontalImages),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiUrl = "$mainUrl/wp-json/wp/v2/posts?search=$query&per_page=15&orderby=relevance"
        android.util.Log.e(TAG, "search URL: $apiUrl")

        // 2.1 — retry (this was the exact call that timed out in your log)
        val responseText = Film1kResolver.retry(times = 3, initialDelayMs = 500) {
            app.get(apiUrl, headers = browserHeaders, verify = false).text
        } ?: run {
            android.util.Log.e(TAG, "search FAILED after retries")
            return emptyList()
        }

        val wpPosts = tryParseJson<List<WpPost>>(responseText) ?: return emptyList()
        return parseWpPosts(wpPosts)
    }

    private suspend fun parseWpPosts(wpPosts: List<WpPost>): List<SearchResponse> = coroutineScope {
        wpPosts.take(8).map { post ->
            async(Dispatchers.IO) {
                try {
                    val mediaUrl = post.link?.takeIf { it.isNotBlank() } ?: return@async null
                    val rawName = post.title?.rendered ?: return@async null
                    val siteTitle = cleanTitle(rawName)

                    val imdbId = post.content?.rendered?.let { html ->
                        Regex("imdb\\.com/title/(tt\\d+)").find(html)?.groupValues?.get(1)
                            ?: Regex("tt\\d{7,8}").find(html)?.value
                    }

                    val cinemeta = if (imdbId != null) fetchCinemetaData(imdbId) else null
                    val finalTitle = cinemeta?.name?.takeIf { it.isNotBlank() } ?: siteTitle

                    val cinemetaPosterUrl = imdbId?.let {
                        "https://images.metahub.space/poster/medium/$it/img"
                    }
                    val wpPosterUrl = post.meta?.fifu_image_url?.takeIf { it.isNotBlank() }
                        ?: post.content?.rendered?.let { html ->
                            Regex("src=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                        }?.let { fixUrl(it) }

                    val finalPosterUrl = cinemetaPosterUrl ?: wpPosterUrl
                    val yearInt = cinemeta?.year?.toIntOrNull()
                        ?: Regex("\\((\\d{4})\\)").find(rawName)?.groupValues?.get(1)?.toIntOrNull()

                    newMovieSearchResponse(finalTitle, mediaUrl, TvType.NSFW) {
                        this.posterUrl = finalPosterUrl
                        this.year = yearInt
                    }
                } catch (e: Throwable) {
                    android.util.Log.e(TAG, "parseWpPosts: entry FAILED", e)
                    null
                }
            }
        }.awaitAll().filterNotNull()
    }

    // ------------------------------------------------------------------
    // Cinemeta — 1.3 in-memory cache + 2.1 retry
    // ------------------------------------------------------------------
    private suspend fun fetchCinemetaData(imdbId: String): CinemetaMeta? {
        cinemetaCache[imdbId]?.let { return it }
        val result = Film1kResolver.retry(times = 3, initialDelayMs = 400) {
            val responseText = app.get("$cinemetaBaseUrl/$imdbId.json", cacheTime = 1440).text
            tryParseJson<CinemetaResponse>(responseText)?.meta
        }
        if (result != null) cinemetaCache[imdbId] = result
        return result
    }

    private suspend fun fetchOpenSubtitles(imdbId: String): List<SubtitleFile> {
        val requestUrl = "$openSubtitlesBaseUrl/$imdbId.json"
        val responseText = Film1kResolver.retry(times = 2, initialDelayMs = 400) {
            app.get(requestUrl, cacheTime = 1440).text
        } ?: return emptyList()
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

    // ------------------------------------------------------------------
    // Detail page — includes 1.2 pre-warm
    // ------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = browserHeaders, verify = false, cacheTime = 1440).document

        // 1.2 — fire-and-forget pre-warm of the Film1k resolver details cache.
        // Find the film1k.xyz embed code from the static HTML (present as
        // `<source src="https://film1k.xyz/e/<code>/...">`). The details fetch
        // takes ~500 ms; by the time the user clicks Play, the cache is warm.
        val embedCode = Regex("""film1k\.xyz/e/([a-zA-Z0-9]+)""")
            .find(doc.html())?.groupValues?.get(1)
        if (embedCode != null) {
            android.util.Log.e(TAG, "load: pre-warming Film1k details for code=$embedCode")
            prewarmScope.launch {
                try {
                    Film1kResolver.prewarmDetails(embedCode)
                    android.util.Log.e(TAG, "load: pre-warm complete for code=$embedCode")
                } catch (_: Throwable) {
                    // ignore — pre-warm is best-effort
                }
            }
        }

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
    // AJAX — 2.1 retry
    // ------------------------------------------------------------------
    private suspend fun fetchServerEmbed(postId: String, key: Int, referer: String): String? {
        return try {
            val r = Film1kResolver.retry(times = 2, initialDelayMs = 400) {
                app.post(
                    ajaxUrl,
                    data = mapOf(
                        "action" to "action_change_player_eroz",
                        "ide" to postId,
                        "key" to key.toString()
                    ),
                    headers = browserHeaders + mapOf(
                        "X-Requested-With" to "XMLHttpRequest",
                        "Origin" to mainUrl,
                        "Accept" to "application/json, text/javascript, */*; q=0.01",
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
                    ),
                    referer = referer,
                    verify = false
                )
            } ?: run {
                android.util.Log.e(TAG, "AJAX key=$key failed after retries")
                return null
            }

            android.util.Log.e(TAG, "AJAX key=$key status=${r.code} len=${r.text.length}")

            val json = JSONObject(r.text)
            val videoHtml = json.optString("video", "")
            if (videoHtml.isBlank()) {
                android.util.Log.e(TAG, "AJAX key=$key: EMPTY 'video' field")
                return null
            }

            val src = Regex(
                """<iframe[^>]+src\s*=\s*["']([^"']+)["']""",
                RegexOption.IGNORE_CASE
            ).find(videoHtml)?.groupValues?.get(1)

            android.util.Log.e(TAG, "AJAX key=$key iframe src=$src")
            src
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "AJAX key=$key EXCEPTION", e)
            null
        }
    }

    // ------------------------------------------------------------------
    // Router — 2.2 dead host tracking
    // ------------------------------------------------------------------
    private suspend fun routeToExtractor(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 2.2 — short-circuit known-dead hosts
        if (isHostDead(embedUrl)) {
            android.util.Log.e(TAG, "Route → SKIPPED (dead host): $embedUrl")
            return
        }

        // Track whether the extractor produces any links for host health
        val emitted = AtomicInteger(0)
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            emitted.incrementAndGet()
            callback.invoke(link)
        }

        try {
            when {
                embedUrl.contains("film1k.xyz") && embedUrl.contains("/e/") -> {
                    android.util.Log.e(TAG, "Route → Film1k: $embedUrl")
                    Film1kExtractor().getUrl(embedUrl, mainUrl, subtitleCallback, wrappedCallback)
                }
                embedUrl.contains("turbovidhls.com") -> {
                    android.util.Log.e(TAG, "Route → TurboVid: $embedUrl")
                    TurboVidHLSExtractor().getUrl(embedUrl, mainUrl, subtitleCallback, wrappedCallback)
                }
                embedUrl.contains("hgcloud.to") -> {
                    if (!ENABLE_HGCLOUD) {
                        android.util.Log.e(TAG, "Route → HgCloud SKIPPED (disabled): $embedUrl")
                        return
                    }
                    android.util.Log.e(TAG, "Route → HgCloud: $embedUrl")
                    HgCloudExtractor().getUrl(embedUrl, mainUrl, subtitleCallback, wrappedCallback)
                }
                embedUrl.contains("abyssplayer.com") -> {
                    android.util.Log.e(TAG, "Route → Abyss SKIPPED: $embedUrl")
                    return
                }
                else -> {
                    // Strict direct-media detection
                    val lower = embedUrl.lowercase()
                    val hasEmbedMarker =
                        lower.contains("/e/") ||
                        lower.contains("/embed/") ||
                        lower.contains("/v/") ||
                        lower.contains("?v=") ||
                        lower.contains("/player") ||
                        lower.contains("/watch")
                    val endsWithMediaExt = lower.contains(".mp4") || lower.contains(".m3u8")

                    if (embedUrl.startsWith("http") && endsWithMediaExt && !hasEmbedMarker) {
                        android.util.Log.e(TAG, "Route → Direct media: $embedUrl")
                        callback.invoke(
                            newExtractorLink(
                                name = "Direct",
                                source = "Direct",
                                url = embedUrl,
                                type = if (lower.contains(".m3u8"))
                                    ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                        emitted.incrementAndGet()
                    } else {
                        android.util.Log.e(TAG, "Route → GenericEmbed: $embedUrl")
                        GenericEmbedExtractor().getUrl(embedUrl, mainUrl, subtitleCallback, wrappedCallback)
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "routeToExtractor FAILED for $embedUrl", e)
        }

        // 2.2 — update host health
        if (emitted.get() > 0) {
            markHostSuccess(embedUrl)
        } else {
            markHostFailure(embedUrl)
        }
    }

    // ------------------------------------------------------------------
    // loadLinks — 1.1 interleaved routing (static starts before AJAX finishes)
    // ------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = supervisorScope {

        val pipelineStart = System.currentTimeMillis()
        android.util.Log.e(TAG, "=== loadLinks START: $data ===")

        val doc = try {
            app.get(data, headers = browserHeaders, verify = false, cacheTime = 0).document
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "loadLinks: fetch FAILED", e)
            return@supervisorScope false
        }
        android.util.Log.e(
            TAG,
            "loadLinks: doc fetched len=${doc.html().length} in ${System.currentTimeMillis() - pipelineStart}ms"
        )

        val emittedUrls = Collections.synchronizedSet(mutableSetOf<String>())
        val emittedCount = AtomicInteger(0)

        val emittingCallback: (ExtractorLink) -> Unit = { link ->
            if (emittedUrls.add(link.url)) {
                val n = emittedCount.incrementAndGet()
                android.util.Log.e(
                    TAG,
                    ">>> EMITTING #$n [${link.name}] ${link.url} " +
                    "(pipeline t+${System.currentTimeMillis() - pipelineStart}ms)"
                )
                try {
                    callback.invoke(link)
                } catch (e: Throwable) {
                    android.util.Log.e(TAG, "callback.invoke failed", e)
                }
            } else {
                android.util.Log.e(TAG, ">>> DEDUPED [${link.name}]: ${link.url}")
            }
        }

        val subtitleJob = async(Dispatchers.IO) {
            try {
                val docText = doc.html()
                val imdbId = Regex("imdb\\.com/title/(tt\\d+)").find(docText)?.groupValues?.get(1)
                    ?: Regex("tt\\d{7,8}").find(docText)?.value
                if (imdbId != null) fetchOpenSubtitles(imdbId) else emptyList()
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "subtitle fetch failed", e)
                emptyList()
            }
        }

        val postId = doc.selectFirst("[data-ide]")?.attr("data-ide")
            ?: Regex("""data-ide=["'](\d+)["']""").find(doc.html())?.groupValues?.get(1)
        android.util.Log.e(TAG, "loadLinks: postId=$postId")

        // 1.1 — Fire AJAX immediately, but don't await it yet
        val ajaxDeferred = if (postId != null) {
            (0..3).map { key ->
                async(Dispatchers.IO) { key to fetchServerEmbed(postId, key, data) }
            }
        } else {
            android.util.Log.e(TAG, "loadLinks: NO postId — AJAX skipped")
            emptyList()
        }

        // 1.1 — Start routing static URLs right away, in parallel with AJAX
        val staticRoutingJobs = mutableListOf<Job>()

        doc.select("#my-video > source").forEach { source ->
            val src = getImageUrl(source)
            if (!src.isNullOrBlank() &&
                src.startsWith("http") &&
                !src.startsWith("https://www.film1k.com/") &&
                !(src.contains("film1k.xyz") && src.endsWith(".mp4"))
            ) {
                val fixed = fixUrl(src)
                android.util.Log.e(TAG, "static <source>: $fixed")
                staticRoutingJobs.add(
                    launch(Dispatchers.IO) {
                        routeToExtractor(fixed, subtitleCallback, emittingCallback)
                    }
                )
            }
        }
        doc.select("#video-op-a > div > iframe").forEach { iframe ->
            val src = getImageUrl(iframe)
            if (!src.isNullOrBlank() &&
                src.startsWith("http") &&
                !src.contains("about:blank")
            ) {
                val fixed = fixUrl(src)
                android.util.Log.e(TAG, "static <iframe>: $fixed")
                staticRoutingJobs.add(
                    launch(Dispatchers.IO) {
                        routeToExtractor(fixed, subtitleCallback, emittingCallback)
                    }
                )
            }
        }

        // Now await AJAX results (they've been running in parallel)
        val ajaxStart = System.currentTimeMillis()
        val ajaxResults = ajaxDeferred.mapNotNull { deferred ->
            try {
                deferred.await()
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "AJAX await failed", e)
                null
            }
        }
        android.util.Log.e(
            TAG,
            "loadLinks: AJAX complete in ${System.currentTimeMillis() - ajaxStart}ms"
        )

        val ajaxRoutingJobs = ajaxResults.mapNotNull { (key, url) ->
            if (url != null && url.startsWith("http")) {
                android.util.Log.e(TAG, "loadLinks: AJAX key=$key routing $url")
                launch(Dispatchers.IO) {
                    routeToExtractor(url, subtitleCallback, emittingCallback)
                }
            } else null
        }

        // Wait for all extractors
        (staticRoutingJobs + ajaxRoutingJobs).joinAll()

        val subtitles = try {
            subtitleJob.await()
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "subtitleJob await failed", e)
            emptyList()
        }
        subtitles.forEach { subtitleCallback(it) }

        val totalEmitted = emittedCount.get()
        android.util.Log.e(
            TAG,
            "loadLinks: emitted $totalEmitted link(s) in ${System.currentTimeMillis() - pipelineStart}ms total"
        )
        android.util.Log.e(TAG, "=== loadLinks END ===")

        return@supervisorScope totalEmitted > 0
    }
}
