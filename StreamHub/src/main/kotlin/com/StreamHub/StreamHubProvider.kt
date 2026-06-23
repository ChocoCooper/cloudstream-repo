package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class StreamHubProvider : MainAPI() {
    override var mainUrl = "https://streamhub.app" 
    override var name = "StreamHub"
    override val hasMainPage = true 
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    // --- WYZIESUBS API KEY ---
    private val wyzieApiKey = "wyzie-qadj2lucrwvfqglskqdy67jy7zkaptgo"

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

    private val tmdbBase = "https://api.tmdb.org/3"
    private val imageBase = "https://image.tmdb.org/t/p/w500"
    private val backdropBase = "https://image.tmdb.org/t/p/original"

    // --- TMDB DATA CLASSES ---
    private data class TmdbSearchResponse(@JsonProperty("results") val results: List<TmdbResult>?)
    private data class TmdbResult(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("media_type") val mediaType: String?
    )
    
    private data class TmdbExternalIds(@JsonProperty("imdb_id") val imdbId: String?)
    private data class TmdbDetails(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("genres") val genres: List<TmdbGenre>?,
        @JsonProperty("seasons") val seasons: List<TmdbSeason>?,
        @JsonProperty("images") val images: TmdbImages?,
        @JsonProperty("external_ids") val externalIds: TmdbExternalIds?
    )

    private data class TmdbGenre(@JsonProperty("name") val name: String?)
    private data class TmdbImages(@JsonProperty("logos") val logos: List<TmdbImage>?)
    private data class TmdbImage(
        @JsonProperty("file_path") val filePath: String?,
        @JsonProperty("iso_639_1") val lang: String?
    )
    private data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int?,
        @JsonProperty("episode_count") val episodeCount: Int?
    )

    // --- CORE LOGIC ---
    private suspend inline fun <reified T : Any> fetchTmdb(url: String): T? {
        val keysToTry = tmdbApiKeys.shuffled().take(3)
        for (key in keysToTry) {
            try {
                val finalUrl = url.replace("{API_KEY}", key)
                val response = app.get(finalUrl).parsedSafe<T>()
                if (response != null) return response
            } catch (e: Exception) { continue }
        }
        return null
    }

    override val mainPage = mainPageOf(
        "$tmdbBase/trending/movie/week?api_key={API_KEY}" to "Trending Movies",
        "$tmdbBase/trending/tv/week?api_key={API_KEY}" to "Trending Shows",
        "$tmdbBase/discover/tv?api_key={API_KEY}&with_genres=16&sort_by=first_air_date.desc&with_original_language=ja&vote_average.gte=6&vote_count.gte=10&without_keywords=10121,9706,264386,280003,158718,281741" to "Trending Anime",
        "$tmdbBase/discover/tv?api_key={API_KEY}&with_original_language=ko&sort_by=first_air_date.desc&vote_average.gte=5&vote_count.gte=5&without_keywords=289844,291807,5832" to "Trending K-Drama",
        "$tmdbBase/discover/tv?api_key={API_KEY}&with_original_language=zh&sort_by=first_air_date.desc&vote_average.gte=4&vote_count.gte=2&without_genres=16,10759,10765,10768&with_keywords=9840|4265&without_keywords=289844,280003" to "Trending C-Drama",
        "$tmdbBase/discover/tv?api_key={API_KEY}&with_original_language=th&sort_by=first_air_date.desc&vote_average.gte=1&vote_count.gte=1&without_keywords=289844,291807,280003,158718&with_keywords=9840" to "Trending Thai Drama",
        "$tmdbBase/discover/movie?api_key={API_KEY}&with_original_language=ja&sort_by=release_date.desc&vote_average.gte=5&vote_count.gte=5&without_keywords=225273,289844,158718&with_genres=16" to "Trending Anime Movies",
        "$tmdbBase/discover/movie?api_key={API_KEY}&with_original_language=ko|zh|th|ja&sort_by=release_date.desc&vote_average.gte=5&vote_count.gte=1&without_keywords=225273,289844,158718&with_genres=10749&without_genres=16" to "Trending Asian Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = request.data + "&page=$page"
        val response = fetchTmdb<TmdbSearchResponse>(url) ?: return null
        
        val items = response.results?.filter { it.posterPath != null }?.mapNotNull { result ->
            val isMovie = result.mediaType == "movie" || request.name.contains("Movie")
            val title = result.title ?: result.name ?: return@mapNotNull null
            val id = result.id ?: return@mapNotNull null
            val poster = "$imageBase${result.posterPath}"
            val urlPath = if (isMovie) "movie/$id" else "tv/$id"
            
            newMovieSearchResponse(title, "$mainUrl/$urlPath", if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = poster
            }
        } ?: emptyList()
        
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$tmdbBase/search/multi?api_key={API_KEY}&query=$encodedQuery"
        val response = fetchTmdb<TmdbSearchResponse>(url)

        return response?.results?.filter { it.posterPath != null }?.mapNotNull { result ->
            val isMovie = result.mediaType == "movie" || result.title != null
            val title = result.title ?: result.name ?: return@mapNotNull null
            val id = result.id ?: return@mapNotNull null
            val poster = "$imageBase${result.posterPath}"
            val urlPath = if (isMovie) "movie/$id" else "tv/$id"

            newMovieSearchResponse(title, "$mainUrl/$urlPath", if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = poster
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.substringBefore("?")
        val isMovie = cleanUrl.contains("/movie/")
        val tmdbId = cleanUrl.substringAfterLast("/")
        
        val endpoint = if (isMovie) "movie" else "tv"
        val detailsUrl = "$tmdbBase/$endpoint/$tmdbId?api_key={API_KEY}&append_to_response=images,external_ids&include_image_language=en,null"
        
        val details = fetchTmdb<TmdbDetails>(detailsUrl) ?: return null
        
        val title = details.title ?: details.name ?: return null
        val poster = details.posterPath?.let { "$imageBase$it" }
        val backdrop = details.backdropPath?.let { "$backdropBase$it" } 
        val imdbId = details.externalIds?.imdbId ?: "null"

        val parsedYear = (details.releaseDate ?: details.firstAirDate)?.split("-")?.firstOrNull()?.toIntOrNull()
        val parsedTags = details.genres?.mapNotNull { it.name } ?: emptyList()
        val logoPath = details.images?.logos?.firstOrNull { it.lang == "en" }?.filePath 
        val parsedLogo = logoPath?.let { "$backdropBase$it" }

        if (isMovie) {
            val dataUrl = "$mainUrl/movie/$tmdbId?imdb=$imdbId"
            return newMovieLoadResponse(title, dataUrl, TvType.Movie, dataUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = details.overview
                this.year = parsedYear
                this.tags = parsedTags
                this.logoUrl = parsedLogo 
            }
        } else {
            val episodes = mutableListOf<Episode>()
            details.seasons?.filter { (it.seasonNumber ?: 0) > 0 }?.forEach { season ->
                val sNum = season.seasonNumber ?: return@forEach
                val epCount = season.episodeCount ?: 0
                for (epNum in 1..epCount) {
                    val epUrl = "$mainUrl/tv/$tmdbId/$sNum/$epNum?imdb=$imdbId"
                    episodes.add(newEpisode(epUrl) {
                        this.name = "Episode $epNum"
                        this.season = sNum
                        this.episode = epNum
                    })
                }
            }

            val dataUrl = "$mainUrl/tv/$tmdbId?imdb=$imdbId"
            return newTvSeriesLoadResponse(title, dataUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = details.overview
                this.year = parsedYear
                this.tags = parsedTags 
                this.logoUrl = parsedLogo
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.substringBefore("?")
        val isMovie = cleanData.contains("/movie/")
        
        val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(cleanData)?.groupValues?.get(1) ?: return false
        val season = Regex("""/tv/\d+/(\d+)""").find(cleanData)?.groupValues?.get(1)?.toIntOrNull()
        val episode = Regex("""/tv/\d+/\d+/(\d+)""").find(cleanData)?.groupValues?.get(1)?.toIntOrNull()
        val imdbId = data.substringAfter("imdb=", "").substringBefore("&").takeIf { it.isNotBlank() && it != "null" }

        return coroutineScope {
            
            // --- 1. FULLY ISOLATED SUBTITLE NETWORK ---
            val subJobs = listOf(
                async {
                    // Layer 1: Stremio OpenSubtitles v3 (Using IMDb)
                    if (imdbId != null) {
                        try {
                            val osUrl = if(isMovie) "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json" else "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$season:$episode.json"
                            val res = app.get(osUrl, timeout = 5).text
                            val subs = JSONObject(res).optJSONArray("subtitles")
                            if (subs != null) {
                                for (i in 0 until subs.length()) {
                                    val sub = subs.getJSONObject(i)
                                    val url = sub.optString("url")
                                    val lang = sub.optString("lang")
                                    if (url.isNotBlank() && lang.isNotBlank()) subtitleCallback.invoke(newSubtitleFile(lang, url))
                                }
                            }
                        } catch (e: Exception) {}
                    }
                },
                async {
                    // Layer 2: Legacy Stremio OpenSubtitles
                    if (imdbId != null) {
                        try {
                            val osLegacyUrl = if(isMovie) "https://opensubtitles.strem.io/stremio/v1/subtitles/movie/$imdbId.json" else "https://opensubtitles.strem.io/stremio/v1/subtitles/series/$imdbId:$season:$episode.json"
                            val res = app.get(osLegacyUrl, timeout = 5).text
                            val subs = JSONObject(res).optJSONArray("subtitles")
                            if (subs != null) {
                                for (i in 0 until subs.length()) {
                                    val sub = subs.getJSONObject(i)
                                    val url = sub.optString("url")
                                    val lang = sub.optString("lang")
                                    if (url.isNotBlank() && lang.isNotBlank()) subtitleCallback.invoke(newSubtitleFile(lang, url))
                                }
                            }
                        } catch (e: Exception) {}
                    }
                },
                async {
                    // Layer 3: Stremio OpenSubtitles (Using TMDB Fallback for Anime)
                    try {
                        val osTmdbUrl = if(isMovie) "https://opensubtitles.strem.fun/subtitles/movie/tmdb:$tmdbId.json" else "https://opensubtitles.strem.fun/subtitles/series/tmdb:$tmdbId:$season:$episode.json"
                        val res = app.get(osTmdbUrl, timeout = 5).text
                        val subs = JSONObject(res).optJSONArray("subtitles")
                        if (subs != null) {
                            for (i in 0 until subs.length()) {
                                val sub = subs.getJSONObject(i)
                                val url = sub.optString("url")
                                val lang = sub.optString("lang")
                                if (url.isNotBlank() && lang.isNotBlank()) subtitleCallback.invoke(newSubtitleFile(lang, url))
                            }
                        }
                    } catch (e: Exception) {}
                },
                async {
                    // Layer 4: Wyziesubs API
                    // FIX: Replaced AppUtils.tryParseJson with raw JSONObject/JSONArray parsing to prevent Kotlin Compiler Memory Crash
                    try {
                        var wyzieUrl = "https://sub.wyzie.io/search?id=$tmdbId&source=all&key=$wyzieApiKey"
                        if (!isMovie && season != null && episode != null) wyzieUrl += "&season=$season&episode=$episode"
                        val wyzieResponse = app.get(wyzieUrl, timeout = 5).text
                        
                        val array = if (wyzieResponse.trim().startsWith("{")) {
                            JSONObject(wyzieResponse).optJSONArray("subtitles") ?: JSONArray()
                        } else {
                            JSONArray(wyzieResponse)
                        }

                        for (i in 0 until array.length()) {
                            val sub = array.getJSONObject(i)
                            val subUrl = sub.optString("url")
                            val lang = sub.optString("display").takeIf { it.isNotBlank() } ?: sub.optString("language", "English")
                            if (subUrl.isNotBlank()) {
                                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
                            }
                        }
                    } catch (e: Exception) {}
                }
            )

            // --- 2. FAST NATIVE WEBVIEW SERVERS (STATIC ONLY) ---
            val webviewExtractors = listOf(
                async {
                    withTimeoutOrNull(15000) {
                        try {
                            val embedUrl = cleanData.replace("https://streamhub.app", "https://111movies.net")
                            val interceptor = WebViewResolver(Regex(""".*\.m3u8.*"""))
                            val response = app.get(embedUrl, interceptor = interceptor)
                            if (response.url.contains(".m3u8")) {
                                callback.invoke(ExtractorLink(
                                    source = "111movies", 
                                    name = "111movies", 
                                    url = response.url, 
                                    referer = "https://111movies.net/", 
                                    quality = Qualities.P1080.value, 
                                    type = ExtractorLinkType.M3U8
                                ))
                                true
                            } else false
                        } catch(e: Exception) { false }
                    } ?: false
                },
                async {
                    withTimeoutOrNull(15000) {
                        try {
                            val domains = listOf("https://vidcore.net", "https://vidup.to")
                            val interceptor = WebViewResolver(Regex(""".*\.m3u8.*"""))
                            var success = false
                            for (domain in domains) {
                                val targetUrl = cleanData.replace("https://streamhub.app", domain)
                                val response = app.get(targetUrl, interceptor = interceptor)
                                if (response.url.contains(".m3u8")) {
                                    val sourceName = if (domain.contains("vidcore")) "Vidcore" else "Vidup"
                                    callback.invoke(ExtractorLink(
                                        source = sourceName, 
                                        name = sourceName, 
                                        url = response.url, 
                                        referer = "$domain/", 
                                        quality = Qualities.P1080.value, 
                                        type = ExtractorLinkType.M3U8
                                    ))
                                    success = true
                                    break
                                }
                            }
                            success
                        } catch(e: Exception) { false }
                    } ?: false
                },
                async {
                    withTimeoutOrNull(15000) {
                        try {
                            val vidlinkUrl = cleanData.replace("https://streamhub.app", "https://vidlink.pro")
                            val interceptor = WebViewResolver(Regex(""".*\.m3u8.*"""))
                            val response = app.get(vidlinkUrl, interceptor = interceptor)
                            if (response.url.contains(".m3u8")) {
                                callback.invoke(ExtractorLink(
                                    source = "Vidlink", 
                                    name = "Vidlink", 
                                    url = response.url, 
                                    referer = "https://vidlink.pro/", 
                                    quality = Qualities.P1080.value, 
                                    type = ExtractorLinkType.M3U8
                                ))
                                true
                            } else false
                        } catch(e: Exception) { false }
                    } ?: false
                }
            )

            val serverResults = webviewExtractors.awaitAll()
            subJobs.awaitAll()

            serverResults.any { it }
        }
    }
}
