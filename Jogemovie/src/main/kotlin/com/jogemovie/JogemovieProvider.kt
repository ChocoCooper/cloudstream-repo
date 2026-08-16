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
    override val supportsLatest = true

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
    private var currentApiKeyIndex = 0

    private fun getTmdbApiKey(): String {
        val key = tmdbApiKeys[currentApiKeyIndex]
        currentApiKeyIndex = (currentApiKeyIndex + 1) % tmdbApiKeys.size
        return key
    }

    // Cache for TMDB results (key: "cleanTitle|year")
    private val tmdbCache = mutableMapOf<String, TmdbMovie?>()

    override val mainPage = MainPageLayout(
        prebuilt = listOf(
            HomePageList("Korean Movies", "k19movie"),
            HomePageList("Japanese Movies", "j19movie")
        )
    )

    private val baseUrl by lazy { getBaseUrl() }

    private fun getBaseUrl(): String {
        try {
            val response = app.get(mainUrl, allowRedirects = true)
            return response.url.toString().replaceAfterLast("/", "").dropLast(1)
        } catch (e: Exception) {
            return "https://v25.jogemovie.net"
        }
    }

    // ----------------------------------------------------------------------
    // TMDB search – returns Korean title + English originalTitle
    // ----------------------------------------------------------------------
    private suspend fun searchTmdb(query: String, year: String? = null): TmdbMovie? {
        val cacheKey = "$query|$year"
        tmdbCache[cacheKey]?.let { return it }

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
                            overview = first.getString("overview"),
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
        tmdbCache[cacheKey] = null
        return null
    }

    // ----------------------------------------------------------------------
    // Main page: show movies with English titles (if available)
    // ----------------------------------------------------------------------
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val section = request.data as String
        val url = "$baseUrl/$section/"
        val document = app.get(url).document
        val videos = parseMovieItems(document)
        return HomePageResponse(
            listOf(
                HomePageList(
                    name = if (section == "k19movie") "Korean Movies" else "Japanese Movies",
                    list = videos,
                    isHorizontal = false
                )
            )
        )
    }

    // ----------------------------------------------------------------------
    // Search: translate English query -> Korean via TMDB, then search site
    // ----------------------------------------------------------------------
    override suspend fun search(query: String): List<SearchResponse> {
        // 1. Get Korean title from TMDB using the English query
        val tmdbResult = searchTmdb(query)
        val searchTerm = tmdbResult?.title ?: query // fallback to original if no TMDB match

        // 2. Search the site with the Korean title
        val encoded = URLEncoder.encode(searchTerm, "UTF-8")
        val url = "$baseUrl/?s=$encoded"
        val document = app.get(url).document

        // 3. Parse items – will again try to get English titles via TMDB
        return parseMovieItems(document).map { it.toSearchResult() }
    }

    // ----------------------------------------------------------------------
    // Parse movie items from site HTML, enrich with TMDB (English titles)
    // ----------------------------------------------------------------------
    private suspend fun parseMovieItems(document: Document): List<Video> {
        val items = document.select(".item")
        return items.mapNotNull { item ->
            val titleElement = item.selectFirst("h3 a")
            val rawTitle = titleElement?.text()?.trim() ?: return@mapNotNull null
            val link = titleElement.attr("href")
            if (link.isBlank()) return@mapNotNull null

            val imgElement = item.selectFirst(".item-img a img")
            var poster = imgElement?.attr("src")?.let {
                if (it.startsWith("//")) "https:$it" else it
            } ?: ""

            val date = item.selectFirst(".meta .date")?.text()?.trim() ?: ""

            // Extract year (e.g., "(2022)") to improve TMDB matching
            val yearPattern = Regex("\\((\\d{4})\\)")
            val year = yearPattern.find(rawTitle)?.groupValues?.get(1)
            val cleanTitle = rawTitle.replace(yearPattern, "").trim()

            // Try TMDB lookup
            val tmdbData = searchTmdb(cleanTitle, year)

            // Choose English title if TMDB found a match; otherwise keep Korean title
            val displayTitle = if (tmdbData != null && tmdbData.originalTitle.isNotBlank()) {
                tmdbData.originalTitle
            } else {
                rawTitle  // fallback to original Korean title
            }

            // Use TMDB poster if available; otherwise keep site poster
            val finalPoster = if (tmdbData != null && tmdbData.posterPath.isNotBlank()) {
                "$imageBase${tmdbData.posterPath}"
            } else {
                poster
            }

            Video(
                title = displayTitle,
                url = link,
                posterUrl = finalPoster,
                addDate = date,
                metadata = mapOf(
                    "koreanTitle" to rawTitle,      // keep original for debugging
                    "tmdbId" to (tmdbData?.id?.toString() ?: ""),
                    "overview" to (tmdbData?.overview ?: "")
                )
            )
        }
    }

    // ----------------------------------------------------------------------
    // Extract video embed links from detail page (unchanged)
    // ----------------------------------------------------------------------
    override suspend fun loadVideoLinks(
        data: String,
        videoCallback: (VideoLink) -> Unit
    ) {
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

        var found = false
        for ((index, url) in urlsToFetch.withIndex()) {
            try {
                val doc = if (url == finalPageUrl) finalDoc else app.get(url).document
                val iframe = doc.selectFirst(".player iframe")
                if (iframe != null) {
                    val embedUrl = iframe.attr("src")
                    if (embedUrl.isNotBlank()) {
                        val quality = if (urlsToFetch.size > 1) "Source ${index + 1}" else "Default"
                        videoCallback.invoke(VideoLink(embedUrl, quality, true))
                        found = true
                    }
                }
            } catch (_: Exception) {
                // skip failed tape
            }
        }

        if (!found) {
            throw ErrorLoadingException("No embed iframe found on any tape")
        }
    }

    private fun Video.toSearchResult(): SearchResponse =
        SearchResponse(
            title = title,
            url = url,
            posterUrl = posterUrl,
            addDate = addDate
        )

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
