package com.jogemovie

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import java.net.URLEncoder
import org.json.JSONObject

class JogemovieProvider : MainAPI() {
    override var mainUrl = "https://jogemovie.com"
    override var name = "Jogemovie"
    override var lang = "en"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie)

    // TMDB configuration
    private val tmdbBase = "https://api.tmdb.org/3"
    private val imageBase = "https://image.tmdb.org/t/p/w500"

    private val tmdbApiKeys = listOf(
        "fb7bb23f03b6994dafc674c074d01761", "e55425032d3d0f371fc776f302e7c09b",
        "8301a21598f8b45668d5711a814f01f6", "8cf43ad9c085135b9479ad5cf6bbcbda",
        "da63548086e399ffc910fbc08526df05", "13e53ff644a8bd4ba37b3e1044ad24f3",
        "269890f657dddf4635473cf4cf456576", "a2f888b27315e62e471b2d587048f32e",
        "8476a7ab80ad76f0936744df0430e67c", "5622cafbfe8f8cfe358a29c53e19bba0",
        "ae4bd1b6fce2a5648671bfc171d15ba4", "257654f35e3dff105574f97fb4b97035",
        "2f4038e83265214a0dcd6ec2eb3276f5", "9e43f45f94705cc8e1d5a0400d19a7b7",
        "af6887753365e14160254ac7f4345dd2", "06f10fc8741a672af455421c239a1ffc",
        "09ad8ace66eec34302943272db0e8d2c", "ea118e768e75a1fe3b53dc99c9e4de09"
    )
    private val currentApiKeyIndex = java.util.concurrent.atomic.AtomicInteger(0)

    private fun getTmdbApiKey(): String {
        val index = currentApiKeyIndex.getAndUpdate { (it + 1) % tmdbApiKeys.size }
        return tmdbApiKeys[index]
    }

    // Cache for TMDB results (key: "cleanTitle|year").
    // Uses a ConcurrentHashMap because parseMovieItems() now looks up items in
    // parallel via amap(), so multiple coroutines can read/write this at once.
    // ConcurrentHashMap doesn't permit null values, so a "no match" result is
    // represented by the NOT_FOUND sentinel instead of null.
    private val tmdbCache = java.util.concurrent.ConcurrentHashMap<String, TmdbMovie>()
    private val NOT_FOUND = TmdbMovie(-1, "", "", "", "", "")

    // mainPageOf maps a "data" string (used to build the section URL) to a display name.
    // request.data / request.name below come from these pairs.
    override val mainPage = mainPageOf(
        "k19movie" to "Korean Movies",
        "j19movie" to "Japanese Movies"
    )

    private var cachedBaseUrl: String? = null

    private suspend fun getBaseUrl(): String {
        cachedBaseUrl?.let { return it }
        val resolved = try {
            val response = app.get(mainUrl, allowRedirects = true)
            response.url.toString().replaceAfterLast("/", "").dropLast(1)
        } catch (e: Exception) {
            "https://v25.jogemovie.net"
        }
        cachedBaseUrl = resolved
        return resolved
    }

    // ----------------------------------------------------------------------
    // TMDB search – returns Korean title + English originalTitle
    // ----------------------------------------------------------------------
    private suspend fun searchTmdb(query: String, year: String? = null): TmdbMovie? {
        val cacheKey = "$query|$year"
        // ConcurrentHashMap can't store null values, so "no match found" is cached
        // as NOT_FOUND rather than null, and translated back to null on the way out.
        tmdbCache[cacheKey]?.let { return if (it === NOT_FOUND) null else it }

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val yearParam = year?.let { "&year=$it" } ?: ""

        // Try all API keys until success
        for (i in tmdbApiKeys.indices) {
            val key = getTmdbApiKey()
            val url = "$tmdbBase/search/movie?api_key=$key&query=$encodedQuery&language=ko-KR$yearParam"
            try {
                val response = app.get(url, timeout = 5000)
                if (response.code == 200) {
                    val json = JSONObject(response.text)
                    val results = json.getJSONArray("results")
                    if (results.length() > 0) {
                        val first = results.getJSONObject(0)
                        val movie = TmdbMovie(
                            id = first.getInt("id"),
                            title = first.getString("title"),                  // Korean title
                            originalTitle = first.getString("original_title"), // English title
                            overview = first.optString("overview", ""),
                            posterPath = first.optString("poster_path", ""),
                            releaseDate = first.optString("release_date", "")
                        )
                        tmdbCache[cacheKey] = movie
                        return movie
                    }
                }
            } catch (_: Exception) {
                // try next key
            }
        }
        tmdbCache[cacheKey] = NOT_FOUND
        return null
    }

    // ----------------------------------------------------------------------
    // TMDB search – returns *all* candidate results (used by search(), so the
    // user can pick the right movie from Cloudstream's search UI before we
    // ever touch jogemovie.com).
    // ----------------------------------------------------------------------
    private suspend fun searchTmdbMulti(query: String): List<TmdbMovie> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")

        for (i in tmdbApiKeys.indices) {
            val key = getTmdbApiKey()
            val url = "$tmdbBase/search/movie?api_key=$key&query=$encodedQuery&language=ko-KR"
            try {
                val response = app.get(url, timeout = 5000)
                if (response.code == 200) {
                    val json = JSONObject(response.text)
                    val results = json.getJSONArray("results")
                    val movies = mutableListOf<TmdbMovie>()
                    for (idx in 0 until results.length()) {
                        val item = results.getJSONObject(idx)
                        movies.add(
                            TmdbMovie(
                                id = item.getInt("id"),
                                title = item.getString("title"),                  // Korean title
                                originalTitle = item.getString("original_title"), // English title
                                overview = item.optString("overview", ""),
                                posterPath = item.optString("poster_path", ""),
                                releaseDate = item.optString("release_date", "")
                            )
                        )
                    }
                    return movies
                }
            } catch (_: Exception) {
                // try next key
            }
        }
        return emptyList()
    }

    // ----------------------------------------------------------------------
    // Main page: show movies with English titles (if available)
    // ----------------------------------------------------------------------
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val section = request.data
        val url = "${getBaseUrl()}/$section/"
        val document = app.get(url).document
        val items = parseMovieItems(document)
        return newHomePageResponse(request.name, items)
    }

    // ----------------------------------------------------------------------
    // Search: query TMDB directly so the user picks from real movie results
    // (English title, correct poster, release year) instead of noisy site
    // search results. We deliberately do NOT touch jogemovie.com here - the
    // Korean title + year needed to find it on the site is only resolved once
    // the user actually selects a result, inside load().
    // ----------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        val results = searchTmdbMulti(query)
        return results.mapNotNull { movie ->
            val displayTitle = movie.originalTitle.ifBlank { movie.title }
            if (displayTitle.isBlank()) return@mapNotNull null

            val releaseYear = movie.releaseDate.take(4).takeIf { it.length == 4 }

            // Package what load() needs to find this on jogemovie.com later.
            // This is NOT a real URL - it's an opaque payload that load()
            // recognizes and unpacks (see loadFromTmdbPayload).
            val payload = JSONObject().apply {
                put("koreanTitle", movie.title)
                put("englishTitle", displayTitle)
                put("year", releaseYear)
                put("posterPath", movie.posterPath)
            }.toString()

            val label = if (releaseYear != null) "$displayTitle ($releaseYear)" else displayTitle

            newMovieSearchResponse(label, payload, TvType.Movie) {
                this.posterUrl = if (movie.posterPath.isNotBlank()) "$imageBase${movie.posterPath}" else null
            }
        }
    }

    // ----------------------------------------------------------------------
    // Parse movie items from site HTML, enrich with TMDB (English titles)
    // Strips leading site "category tag" prefixes like 「한국영화」, 【NEW】, [HD], etc.
    // so the TMDB query and the Korean fallback title only contain the actual
    // movie title (and year), not the site's own labeling.
    // e.g. "「한국영화」 몰래하는 섹스 임장 (2026)" -> "몰래하는 섹스 임장 (2026)"
    private val bracketPrefixPattern = Regex("^(?:[\\[［【「『][^\\]］】」』]*[\\]］】」』]\\s*)+")

    private fun stripBracketTags(title: String): String =
        title.replace(bracketPrefixPattern, "").trim()

    // ----------------------------------------------------------------------
    private suspend fun parseMovieItems(document: Document): List<SearchResponse> {
        val items = document.select(".item")
        // Run the per-item TMDB lookups in parallel instead of one-at-a-time.
        // A page with 20-40 items previously meant 20-40 sequential HTTP round
        // trips before anything rendered, which is slow enough that users would
        // back out mid-load and flood logcat with cancelled-request IOExceptions.
        return items.amap { item ->
            val titleElement = item.selectFirst("h3 a")
            val rawTitle = titleElement?.text()?.trim() ?: return@amap null
            val link = titleElement.attr("href")
            if (link.isBlank()) return@amap null

            val imgElement = item.selectFirst(".item-img a img")
            val sitePoster = imgElement?.attr("src")?.let {
                if (it.startsWith("//")) "https:$it" else it
            } ?: ""

            // Strip site tags like 「한국영화」 / [HD] / 【NEW】 before doing anything else,
            // so both the TMDB query and the Korean fallback title are clean.
            val untaggedTitle = stripBracketTags(rawTitle).ifBlank { rawTitle }

            // Extract year (e.g., "(2022)") to improve TMDB matching
            val yearPattern = Regex("\\((\\d{4})\\)")
            val year = yearPattern.find(untaggedTitle)?.groupValues?.get(1)
            val cleanTitle = untaggedTitle.replace(yearPattern, "").trim()

            // Try TMDB lookup using the tag-free, year-free title
            val tmdbData = searchTmdb(cleanTitle, year)

            // Choose English title if TMDB found a match; otherwise keep the
            // tag-stripped Korean title (still includes the year, if present)
            val displayTitle = if (tmdbData != null && tmdbData.originalTitle.isNotBlank()) {
                tmdbData.originalTitle
            } else {
                untaggedTitle
            }

            // Use TMDB poster if available; otherwise keep site poster
            val finalPoster = if (tmdbData != null && tmdbData.posterPath.isNotBlank()) {
                "$imageBase${tmdbData.posterPath}"
            } else {
                sitePoster
            }

            newMovieSearchResponse(displayTitle, link, TvType.Movie) {
                this.posterUrl = finalPoster
            }
        }.filterNotNull()
    }

    // ----------------------------------------------------------------------
    // Load: two possible shapes of `url` land here.
    //
    // 1. A real jogemovie.com detail-page URL - happens when the user browsed
    //    to this item from the main page (getMainPage/parseMovieItems), where
    //    we already had the site's own link. We just fetch it for metadata.
    //
    // 2. A TMDB payload (JSON, not a real URL) - happens when the user picked
    //    this item from search(), where we only had TMDB data so far. Here we
    //    take the Korean title + year out of that payload, search
    //    jogemovie.com with it, and resolve it to a real detail-page URL
    //    before continuing - this is the "extract Korean title + year, feed
    //    it into the site search, then crawl" step.
    // ----------------------------------------------------------------------
    override suspend fun load(url: String): LoadResponse {
        if (url.trim().startsWith("{")) {
            return loadFromTmdbPayload(url)
        }

        val document = app.get(url).document
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.selectFirst("h1, h3")?.text()?.trim()
            ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // Resolves a TMDB search-result payload to a real jogemovie.com detail
    // page by searching the site with the Korean title (and disambiguating
    // with the release year, since site titles include "(YYYY)").
    private suspend fun loadFromTmdbPayload(payload: String): LoadResponse {
        val json = JSONObject(payload)
        val koreanTitle = json.optString("koreanTitle", "")
        val englishTitle = json.optString("englishTitle", koreanTitle).ifBlank { koreanTitle }
        val year = json.optString("year", "").takeIf { it.isNotBlank() }
        val posterPath = json.optString("posterPath", "")

        val searchTerm = koreanTitle.ifBlank { englishTitle }
        if (searchTerm.isBlank()) {
            throw ErrorLoadingException("No title available to search jogemovie with")
        }

        val encoded = URLEncoder.encode(searchTerm, "UTF-8")
        val siteUrl = "${getBaseUrl()}/?s=$encoded"
        val document = app.get(siteUrl).document
        val items = document.select(".item")

        // Prefer a result whose "(YYYY)" year matches TMDB's release year;
        // fall back to the first search result if no year match is found.
        val yearPattern = Regex("\\((\\d{4})\\)")
        val matched = items.firstOrNull { item ->
            if (year == null) return@firstOrNull false
            val itemTitle = item.selectFirst("h3 a")?.text() ?: return@firstOrNull false
            yearPattern.find(itemTitle)?.groupValues?.get(1) == year
        } ?: items.firstOrNull()
        ?: throw ErrorLoadingException("No jogemovie result found for \"$searchTerm\"")

        val detailLink = matched.selectFirst("h3 a")?.attr("href")
        if (detailLink.isNullOrBlank()) {
            throw ErrorLoadingException("Matched jogemovie result had no detail link")
        }

        val poster = if (posterPath.isNotBlank()) {
            "$imageBase$posterPath"
        } else {
            matched.selectFirst(".item-img a img")?.attr("src")?.let {
                if (it.startsWith("//")) "https:$it" else it
            }
        }

        // dataUrl = detailLink, so loadLinks() below receives the real
        // jogemovie.com detail page and can crawl a.MVlink -> tapes -> iframe
        // exactly as it already does for the direct-browse case.
        return newMovieLoadResponse(englishTitle, detailLink, TvType.Movie, detailLink) {
            this.posterUrl = poster
        }
    }

    // ----------------------------------------------------------------------
    // Extract video embed links from detail page
    // ----------------------------------------------------------------------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val detailDoc = app.get(data).document
        val mvLink = detailDoc.selectFirst("a.MVlink")
            ?: throw ErrorLoadingException("No MVlink found on detail page")
        val finalPageUrl = mvLink.attr("href")
        if (finalPageUrl.isBlank()) {
            throw ErrorLoadingException("Empty MVlink href")
        }

        val finalDoc = app.get(finalPageUrl).document
        val tapeLinks = finalDoc.select(".pagination.post-tape li a")
            .mapNotNull { it.attr("href") }
            .filter { it.isNotBlank() }

        val urlsToFetch = if (tapeLinks.isNotEmpty()) tapeLinks else listOf(finalPageUrl)

        // CloudStream's registered extractors run inside its own internal
        // safeApiCall wrapper, so a crashing extractor (e.g. a broken embed
        // host handler) fails silently from here - loadExtractor won't throw,
        // it'll just deliver zero links. Track actual emissions ourselves via
        // a counting wrapper instead of trusting "no exception" as success.
        var linksEmitted = 0
        val countingCallback: (ExtractorLink) -> Unit = {
            linksEmitted++
            callback(it)
        }

        var iframeFound = false
        for (tapeUrl in urlsToFetch) {
            try {
                val doc = if (tapeUrl == finalPageUrl) finalDoc else app.get(tapeUrl).document
                val iframe = doc.selectFirst(".player iframe")
                val embedUrl = iframe?.attr("src")
                if (embedUrl.isNullOrBlank()) continue
                iframeFound = true

                val before = linksEmitted
                try {
                    // Let CloudStream's extractor framework figure out how to
                    // resolve this embed host into a playable link.
                    loadExtractor(embedUrl, finalPageUrl, subtitleCallback, countingCallback)
                } catch (_: Exception) {
                    // A matched extractor threw instead of failing gracefully;
                    // fall through to the raw-URL fallback below.
                }

                if (linksEmitted == before) {
                    // The matched extractor produced nothing (crashed silently,
                    // or no extractor matched this host). Offer the raw iframe
                    // URL directly - it's sometimes already a playable stream
                    // even when the specific extractor chokes on the page.
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = embedUrl
                        ) {
                            this.referer = finalPageUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    linksEmitted++
                }
            } catch (_: Exception) {
                // skip failed tape
            }
        }

        if (!iframeFound) {
            throw ErrorLoadingException("No embed iframe found on any tape")
        }
        return linksEmitted > 0
    }

    // Helper data class for TMDB results
    private data class TmdbMovie(
        val id: Int,
        val title: String,          // Korean title
        val originalTitle: String,  // English title
        val overview: String,
        val posterPath: String,
        val releaseDate: String
    )
}
