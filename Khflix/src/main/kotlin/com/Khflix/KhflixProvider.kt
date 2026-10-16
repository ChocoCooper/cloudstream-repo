// KhflixProvider.kt
package com.khflix

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KhflixProvider : MainAPI() {
    override var mainUrl = "https://khflix.com"
    override var name = "Khflix"
    override var lang = "en"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie, TvType.TvSeries, TvType.AsianDrama
    )

    private val webViewResolver by lazy { WebViewResolver() }

    companion object {
        private const val TAG = "Khflix"
        private const val PLAYER_API = "https://khflix.com/wp-json/dooplayer/v2/"
        private const val HLS_HOST = "hls.khflix.top"
        private const val MP4_HOST = "videos.khflix.top"
        private const val CDN_VIDEO_HOST = "cdn1.khflixcdnvideos.top"
        private const val SUB_HOST = "subtitle.khflix.top"

        // Confirmed subtitle languages (from Khflix.Playlist.js)
        private val SUBTITLE_LANGS = listOf(
            "Khmer", "Arabic", "Filipino", "Hindi",
            "Indonesian", "Thai", "Vietnamese", "Malay"
        )
    }

    // ==================================================================
    // HOME PAGE — Section rows
    // ==================================================================
    override val mainPage = mainPageOf(
        "" to "Latest",
        "movies" to "Movies",
        "tvshows" to "TV Shows",
        "genre/action" to "Action",
        "genre/comedy" to "Comedy",
        "genre/drama" to "Drama",
        "genre/horror" to "Horror",
        "genre/romance" to "Romance",
        "genre/thriller" to "Thriller",
        "genre/k-drama" to "K-Drama",
        "genre/c-drama" to "C-Drama",
        "genre/j-drama" to "J-Drama",
        "genre/movie-speak-khmer" to "Khmer Dubbed",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty())
            "$mainUrl/page/$page/"
        else
            "$mainUrl/${request.data}/page/$page/"

        val doc = fetchDocument(url)
        val items = doc.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request, items, hasNext = items.isNotEmpty())
    }

    // ==================================================================
    // SEARCH
    // ==================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val doc = fetchDocument("$mainUrl/?s=${query.replace(" ", "+")}")
        val out = mutableListOf<SearchResponse>()

        // Primary: div.result-item (search-specific markup)
        doc.select("div.result-item").forEach { el ->
            el.toSearchResult()?.let { out.add(it) }
        }
        // Fallback: article.item
        if (out.isEmpty()) {
            doc.select("article.item").forEach { el ->
                el.toSearchResult()?.let { out.add(it) }
            }
        }
        return out.distinctBy { it.url }
    }

    // ==================================================================
    // LOAD — Movie / TV details
    // ==================================================================
    override suspend fun load(url: String): LoadResponse? {
        val doc = fetchDocument(url)
        val html = doc.outerHtml()

        // ----- Title (confirmed: div.sheader h1) -----
        val title = doc.selectFirst("div.sheader h1")?.text()?.trim()
            ?: doc.selectFirst("div.data h1")?.text()?.trim()
            ?: doc.title().substringBefore(" - ").trim()

        // ----- Poster (confirmed: div.poster img on khflix.com) -----
        val poster = doc.selectFirst("div.poster img")?.attr("src")
            ?.trim()?.takeIf { it.isNotBlank() }

        // ----- Backdrop (confirmed: og:image[1] on tmdb) -----
        val ogImages = doc.select("meta[property='og:image']")
            .mapNotNull { it.attr("content").trim().takeIf { c -> c.isNotBlank() } }
        val backdrop = ogImages.getOrNull(1) ?: ogImages.lastOrNull() ?: poster

        // ----- Year -----
        val year = Regex("""(\d{4})""")
            .find(doc.selectFirst("span.date")?.text() ?: "")?.value?.toIntOrNull()

        // ----- Genres -----
        val genres = doc.select("div.sgeneros a")
            .map { it.text().trim() }.filter { it.isNotEmpty() }.distinct()

        // ----- IMDb rating -----
        val rating = extractImdbRating(doc)

        // ----- Plot -----
        val plot = doc.selectFirst("div#info .wp-content p")?.text()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        val isTv = url.contains("/tvshows/")

        return if (isTv) {
            val khflixConfig = extractKhflixConfig(html)
            val seriesPrefix = khflixConfig?.get("series")
            val episodeCount = khflixConfig?.get("episodes")?.toIntOrNull() ?: 0

            val episodes = mutableListOf<Episode>()
            if (seriesPrefix != null && episodeCount > 0) {
                for (n in 1..episodeCount) {
                    val padded = n.toString().padStart(2, '0')
                    val data = "$seriesPrefix|tv|$padded"
                    episodes.add(newEpisode(data) {
                        this.name = "Episode $n"
                        this.episode = n
                        this.season = 1
                    })
                }
            }
            Log.d(TAG, "TV series: $title, ${episodes.size} episodes (prefix=$seriesPrefix)")

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = genres
                this.rating = rating
            }
        } else {
            // For movies: data = "postId|movie"
            val postId = extractPostId(html)
            Log.d(TAG, "Movie: $title, postId=$postId")

            newMovieLoadResponse(title, url, TvType.Movie, "$postId|movie") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.tags = genres
                this.rating = rating
            }
        }
    }

    // ==================================================================
    // LOAD LINKS — Video sources + subtitles
    // ==================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 2) return false

        val videoUrls = mutableSetOf<String>()
        val subUrls = mutableSetOf<String>()

        when (parts[1]) {
            // ----------------------------------------------------------
            // MOVIE: use dooplayer REST API + fallback to page HTML
            // ----------------------------------------------------------
            "movie" -> {
                val postId = parts[0]
                Log.d(TAG, "Movie loadLinks: postId=$postId")

                // Primary: REST API to get embed URL, then extract source
                for (nume in 1..5) {
                    try {
                        val resp = app.get("$PLAYER_API$postId/movie/$nume").text
                        val embedUrl = Regex(""""embed_url"\s*:\s*"([^"]*)"""")
                            .find(resp)?.groupValues?.get(1)
                            ?.replace("\\/", "/")
                            ?: ""
                        if (embedUrl.isNotEmpty() && embedUrl != "null") {
                            val decoded = decodeJwPlayerUrl(embedUrl)
                            if (decoded.isNotEmpty()) videoUrls.add(decoded)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Player API call failed for nume=$nume", e)
                    }
                    delayMs(300)
                }

                // Fallback: scrape the movie page for m3u8/mp4 URLs
                val pageUrl = parts.getOrNull(2)
                val moviePage = if (pageUrl.isNullOrBlank()) {
                    // We don't have the page URL, use referer approach
                    null
                } else pageUrl

                if (moviePage != null) {
                    val pageHtml = app.get(moviePage).text
                    extractFromHtml(pageHtml, videoUrls, subUrls)
                }
            }

            // ----------------------------------------------------------
            // TV EPISODE: deterministic URL from khflixConfig
            // ----------------------------------------------------------
            "tv" -> {
                val seriesPrefix = parts[0]              // "Revenge.From.A.Wife.S01E"
                val padded = parts.getOrNull(2) ?: "01"  // "01"
                Log.d(TAG, "TV loadLinks: prefix=$seriesPrefix ep=$padded")

                // Video: https://cdn1.khflixcdnvideos.top/{prefix}{EE}.mp4
                val videoUrl = "https://$CDN_VIDEO_HOST/${seriesPrefix}${padded}.mp4"
                videoUrls.add(videoUrl)

                // Subtitles: 9 languages (confirmed list)
                for (lang in SUBTITLE_LANGS) {
                    subUrls.add("https://$SUB_HOST/${seriesPrefix}${padded}.${lang}.vtt")
                }
                // English default (no language suffix)
                subUrls.add("https://$SUB_HOST/${seriesPrefix}${padded}.vtt")
            }
        }

        Log.d(TAG, "Emitting ${videoUrls.size} video(s), ${subUrls.size} subtitle(s)")

        // ----- Emit video extractor links -----
        videoUrls.forEach { src ->
            val type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8
                       else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(this.name, this.name, src, type) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                       "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                       "Chrome/124.0.0.0 Safari/537.36"
                    )
                }
            )
        }

        // ----- Emit subtitle files -----
        subUrls.forEach { subUrl ->
            val language = extractLanguageFromSubtitleUrl(subUrl)
            subtitleCallback.invoke(newSubtitleFile(language, subUrl))
        }

        return videoUrls.isNotEmpty()
    }

    // ==================================================================
    // HELPERS
    // ==================================================================

    /**
     * Extract video + subtitle URLs from a page's raw HTML.
     * Used for the movie fallback.
     */
    private fun extractFromHtml(html: String, videos: MutableSet<String>,
                                subs: MutableSet<String>) {
        Regex("""https?://$HLS_HOST/[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""")
            .findAll(html).forEach { videos.add(it.value) }
        Regex("""https?://$MP4_HOST/[^"'\s<>\\]+\.(?:mp4|mkv|webm)""")
            .findAll(html).forEach { videos.add(it.value) }
        Regex("""https?://$CDN_VIDEO_HOST/[^"'\s<>\\]+\.mp4""")
            .findAll(html).forEach { videos.add(it.value) }
        Regex("""https?://$SUB_HOST/[^"'\s<>\\]+\.vtt""")
            .findAll(html).forEach { subs.add(it.value) }
    }

    /**
     * Extract window.khflixConfig from a TV series page.
     * Example: {series: "Revenge.From.A.Wife.S01E", episodes: 26, image: "..."}
     */
    private fun extractKhflixConfig(html: String): Map<String, String>? {
        val m = Regex("""window\.khflixConfig\s*=\s*\{([^}]+)\}""").find(html)
            ?: return null
        val body = m.groupValues[1]
        val out = mutableMapOf<String, String>()
        // String values: key: "value"
        Regex("""(\w+)\s*:\s*"([^"]*)"""").findAll(body).forEach {
            out[it.groupValues[1]] = it.groupValues[2]
        }
        // Numeric values: key: 26
        Regex("""(\w+)\s*:\s*(\d+)""").findAll(body).forEach {
            out[it.groupValues[1]] = it.groupValues[2]
        }
        return out.takeIf { it.isNotEmpty() }
    }

    /**
     * Extract post ID from HTML.
     * Confirmed sources: id="post-XXXX", "post_id":"XXXX"
     */
    private fun extractPostId(html: String): String {
        val patterns = listOf(
            Regex("""id="post-(\d+)""""),
            Regex(""""post_id"\s*:\s*"?(\d+)"?"""),
            Regex("""postid-(\d+)"""),
            Regex("""data-post=["'](\d+)["']"""),
        )
        for (p in patterns) {
            p.find(html)?.let { return it.groupValues[1] }
        }
        return ""
    }

    /**
     * Decode jwplayer wrapper URL → real video URL.
     * "https://khflix.com/jwplayer/?source=<ENCODED>&id=26161&type=mp4"
     *   → "https://hugh.cdn.rumble.cloud/video/.../OSG6A.aaa.mp4"
     */
    private fun decodeJwPlayerUrl(embedUrl: String): String {
        if (!embedUrl.contains("jwplayer/?source=")) {
            // If it's a direct video URL already, return it
            return if (embedUrl.startsWith("http") &&
                (embedUrl.contains(".mp4") || embedUrl.contains(".m3u8") ||
                 embedUrl.contains(".mkv"))) embedUrl else ""
        }
        val sourceParam = embedUrl.substringAfter("source=").substringBefore("&")
        return try {
            java.net.URLDecoder.decode(sourceParam, "UTF-8")
        } catch (e: Exception) {
            sourceParam
        }
    }

    /**
     * Language detection from VTT URL.
     * Confirmed patterns:
     *   Aladdin.2019.Arabic.vtt          → "Arabic"
     *   Aladdin.2019.vtt                 → "English"
     *   Revenge.From.A.Wife.S01E01.Thai.vtt → "Thai"
     */
    private fun extractLanguageFromSubtitleUrl(url: String): String {
        val filename = url.substringAfterLast('/').removeSuffix(".vtt")
        val segments = filename.split(".")
        if (segments.size < 2) return "Subtitle"

        val last = segments.last()

        // If last segment is a year or a series code (S01E01) → default English
        if (last.matches(Regex("""\d{4}"""))) return "English"
        if (last.matches(Regex("""S\d+E\d+""", RegexOption.IGNORE_CASE))) return "English"

        // Known language list (case-insensitive)
        val known = setOf(
            "arabic", "filipino", "hindi", "indonesian", "khmer", "malay",
            "thai", "vietnamese", "english", "chinese", "korean", "japanese"
        )
        if (last.lowercase() in known) return last.replaceFirstChar(Char::uppercase)

        return last.replaceFirstChar(Char::uppercase)
    }

    /**
     * Parse a card element into a SearchResponse.
     * Homepage: article.item > div.image > a (poster) + h3.title (name)
     * Search:   div.result-item (different markup, but shares h3/div.title)
     */
    private fun Element.toSearchResult(): SearchResponse? {
        // Find link
        val a = selectFirst("a[href]") ?: return null
        val href = a.attr("href").takeIf { it.isNotBlank() } ?: return null
        if (!href.contains("/movies/") && !href.contains("/tvshows/")) return null

        // Find title (tries multiple selectors)
        val title = selectFirst("h3.title, h3, div.title, .data h3, .title")
            ?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: a.attr("title").takeIf { it.isNotBlank() }
            ?: return null

        // Find poster
        val poster = selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }

        val type = if (href.contains("/tvshows/")) TvType.TvSeries else TvType.Movie

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = poster
        }
    }

    private fun extractImdbRating(doc: Document): Double? {
        doc.select("div.custom_fields").forEach { field ->
            val label = field.selectFirst("b.variante")?.text()?.lowercase() ?: ""
            if (label.contains("imdb")) {
                field.selectFirst("span.valor")?.text()?.trim()?.toDoubleOrNull()
                    ?.let { return it }
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Network helpers
    // ------------------------------------------------------------------
    private suspend fun fetchHtml(url: String): String? {
        return try {
            val html = app.get(url).text
            if (html.contains("cf-challenge") ||
                html.contains("Just a moment") ||
                html.contains("Checking your browser")) {
                Log.d(TAG, "Cloudflare challenge detected — using WebViewResolver")
                webViewResolver.resolveUsingWebView(url)
            } else html
        } catch (e: Exception) {
            Log.e(TAG, "fetchHtml failed: $url", e)
            null
        }
    }

    private suspend fun fetchDocument(url: String): Document =
        Jsoup.parse(fetchHtml(url) ?: "", url)

    private suspend fun delayMs(ms: Long) {
        kotlinx.coroutines.delay(ms)
    }
}
