package com.NoTorrent

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import java.net.URLDecoder

class NoTorrentProvider : MainAPI() {
    override var mainUrl = "https://api.xyra.stream"
    override var name = "NoTorrent"
    override val hasMainPage = true // Enabled Homepage
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)

    // API Configurations
    private val tmdbBaseUrl = "https://api.tmdb.org/3"
    private val xyraApiKey = "freekey"

    // Master Key List for Failover and Rotation
    private val masterTmdbKeys = listOf(
        "fb7bb23f03b6994dafc674c074d01761", "e55425032d3d0f371fc776f302e7c09b",
        "8301a21598f8b45668d5711a814f01f6", "8cf43ad9c085135b9479ad5cf6bbcbda",
        "da63548086e399ffc910fbc08526df05", "13e53ff644a8bd4ba37b3e1044ad24f3",
        "269890f657dddf4635473cf4cf456576", "a2f888b27315e62e471b2d587048f32e",
        "8476a7ab80ad76f0936744df0430e67c", "5622cafbfe8f8cfe358a29c53e19bba0",
        "ae4bd1b6fce2a5648671bfc171d15ba4", "257654f35e3dff105574f97fb4b97035",
        "2f4038e83265214a0dcd6ec2eb3276f5", "9e43f45f94705cc8e1d5a0400d19a7b7",
        "af6887753365e14160254ac7f4345dd2", "06f10fc8741a672af455421c239a1ffc",
        "09ad8ace66eec34302943272db0e8d2c", "ea118e768e75a1fe3b53dc99c9e4de09"
    ).distinct()

    private val allTmdbKeys = masterTmdbKeys.toMutableList()

    /**
     * Helper function that attempts to make a TMDB request.
     * If a key throws a 401 or 429 error, it catches it and immediately tries a different random backup key.
     */
    private suspend inline fun <reified T : Any> invokeTmdbApi(urlBuilder: (String) -> String): T? {
        val keysToTry = allTmdbKeys.shuffled() // Load balance requests randomly
        for (key in keysToTry) {
            try {
                val url = urlBuilder(key)
                val res = app.get(url)
                return res.parsed<T>()
            } catch (e: Exception) {
                // Ignore the crash, cycle to the next key in the loop
            }
        }
        return null
    }

    // --- Homepage Implementation ---
    override val mainPage = mainPageOf(
        "$tmdbBaseUrl/trending/movie/week?api_key=API_KEY" to "Trending",
        "$tmdbBaseUrl/discover/movie?api_key=API_KEY&with_genres=28&sort_by=release_date.desc&vote_count.gte=50&include_adult=false" to "Action",
        "$tmdbBaseUrl/discover/movie?api_key=API_KEY&with_genres=12&sort_by=release_date.desc&vote_count.gte=50&include_adult=false" to "Adventure",
        "$tmdbBaseUrl/discover/movie?api_key=API_KEY&with_genres=35&sort_by=release_date.desc&vote_count.gte=50&include_adult=false" to "Comedy",
        "$tmdbBaseUrl/discover/movie?api_key=API_KEY&with_genres=80&sort_by=release_date.desc&vote_count.gte=50&include_adult=false" to "Crime",
        "$tmdbBaseUrl/discover/movie?api_key=API_KEY&with_genres=53&sort_by=release_date.desc&vote_count.gte=50&include_adult=false" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val response = invokeTmdbApi<TmdbSearchResponse> { key ->
            // Replace our placeholder with a fresh key and append the scroll page number
            request.data.replace("API_KEY", key) + "&page=$page"
        } ?: return null

        val homeItems = response.results?.mapNotNull { movie ->
            val title = movie.title ?: return@mapNotNull null
            val id = movie.id?.toString() ?: return@mapNotNull null
            val posterUrl = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val fullTmdbUrl = "$tmdbBaseUrl/movie/$id"

            newMovieSearchResponse(title, url = fullTmdbUrl, type = TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = movie.releaseDate?.substringBefore("-")?.toIntOrNull()
            }
        } ?: emptyList()

        return newHomePageResponse(request.name, homeItems)
    }

    // --- Search Implementation ---
    override suspend fun search(query: String): List<SearchResponse> {
        val response = invokeTmdbApi<TmdbSearchResponse> { key ->
            "$tmdbBaseUrl/search/movie?api_key=$key&query=$query&include_adult=false"
        } ?: return emptyList()

        return response.results?.mapNotNull { movie ->
            val title = movie.title ?: return@mapNotNull null
            val id = movie.id?.toString() ?: return@mapNotNull null
            val posterUrl = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            
            val fullTmdbUrl = "$tmdbBaseUrl/movie/$id"

            newMovieSearchResponse(title, url = fullTmdbUrl, type = TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = movie.releaseDate?.substringBefore("-")?.toIntOrNull()
            }
        } ?: emptyList()
    }

    // --- Media Details Implementation ---
    override suspend fun load(url: String): LoadResponse? {
        val response = invokeTmdbApi<TmdbDetailsResponse> { key ->
            "$url?api_key=$key"
        } ?: return null

        val title = response.title ?: return null
        val tmdbId = url.substringAfterLast("/")

        return newMovieLoadResponse(title, url, TvType.Movie, tmdbId) {
            this.posterUrl = response.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            this.backgroundPosterUrl = response.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
            this.year = response.releaseDate?.substringBefore("-")?.toIntOrNull()
            this.plot = response.plot 
            this.tags = response.genres?.mapNotNull { it.name }
        }
    }

    // --- Streaming Links & Subtitles Implementation ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        // 1. Fetch Subtitles from WyzieSubs
        try {
            val wyzieUrl = "https://sub.wyzie.io/search?id=$data"
            val wyzieResponse = app.get(wyzieUrl).parsed<WyzieSubtitleResponse>()
            
            wyzieResponse.subtitles?.forEach { sub ->
                val subUrl = sub.url ?: return@forEach
                val subLang = sub.lang ?: "Unknown"
                
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = subLang,
                        url = subUrl
                    )
                )
            }
        } catch (e: Exception) {}

        // 2. Fetch Video Streams from Xyra (NoTorrent)
        val streamUrl = "$mainUrl/v1/streamhub/notorrent?api_key=$xyraApiKey&tmdb_id=$data"
        val response = app.get(streamUrl).parsed<XyraResponse>()

        response.streams?.forEach { stream ->
            val qualityInfo = stream.title ?: ""
            if (qualityInfo.contains("2160p", ignoreCase = true)) return@forEach

            val link = stream.url ?: return@forEach
            val rawProviderName = stream.name ?: "Unknown"
            val isM3u8 = link.contains(".m3u8", ignoreCase = true)

            // --- Multi-Audio Language Parser ---
            var languageTag = ""
            if (rawProviderName.contains("•")) {
                val rawLang = rawProviderName.substringAfter("•").trim()
                val parsedLang = rawLang
                    .replace("Audio", "", ignoreCase = true)
                    .replace("Original", "Original", ignoreCase = true)
                    .replace("हिंदी ऑडियो", "Hindi")
                    .replace("ಕನ್ನಡ ಆಡಿಯೋ", "Kannada")
                    .replace("മലയാളം ഓഡിയോ", "Malayalam")
                    .replace("தமிழ் ஒலி", "Tamil")
                    .replace("తెలుగు ఆడియో", "Telugu")
                    .replace("castellano", "Castellano", ignoreCase = true)
                    .replace("latino", "Latino", ignoreCase = true)
                    .trim()
                
                if (parsedLang.isNotEmpty()) {
                    languageTag = "[$parsedLang]"
                }
            }

            val mappedQuality = when {
                qualityInfo.contains("1080p") -> Qualities.P1080.value
                qualityInfo.contains("720p") -> Qualities.P720.value
                qualityInfo.contains("480p") -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            val cleanLinkName = if (mappedQuality == Qualities.Unknown.value && qualityInfo.isNotBlank()) {
                "NoTorrent $languageTag - $qualityInfo".trim()
            } else {
                "NoTorrent $languageTag".trim()
            }

            // Headers & Referer Parser (Bypasses 2004 Errors)
            var referer = ""
            val requestHeaders = mutableMapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
            )

            if (link.contains("headers=")) {
                try {
                    val encodedHeaders = link.substringAfter("headers=").substringBefore("&")
                    val decodedHeaders = URLDecoder.decode(encodedHeaders, "UTF-8")
                    val parsedHeaders = parseJson<Map<String, String>>(decodedHeaders)
                    requestHeaders.putAll(parsedHeaders)
                    referer = requestHeaders["referer"] ?: requestHeaders["Referer"] ?: ""
                } catch (e: Exception) {}
            }

            @Suppress("DEPRECATION", "DEPRECATION_ERROR")
            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = cleanLinkName,
                    url = link,
                    referer = referer,
                    quality = mappedQuality,
                    isM3u8 = isM3u8,
                    headers = requestHeaders 
                )
            )
        }

        return true
    }

    // --- Data Classes for JSON Parsing ---

    data class TmdbSearchResponse(
        @JsonProperty("results") val results: List<TmdbMovie>?
    )

    data class TmdbMovie(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("release_date") val releaseDate: String?
    )

    data class TmdbDetailsResponse(
        @JsonProperty("title") val title: String?,
        @JsonProperty("overview") val plot: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("genres") val genres: List<TmdbGenre>?
    )

    data class TmdbGenre(
        @JsonProperty("name") val name: String?
    )

    data class XyraResponse(
        @JsonProperty("success") val success: Boolean?,
        @JsonProperty("streams") val streams: List<XyraStream>?
    )

    data class XyraStream(
        @JsonProperty("name") val name: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("url") val url: String?
    )

    data class WyzieSubtitleResponse(
        @JsonProperty("subtitles") val subtitles: List<WyzieSubtitle>?
    )

    data class WyzieSubtitle(
        @JsonProperty("url") val url: String?,
        @JsonProperty("lang") val lang: String?
    )
}
