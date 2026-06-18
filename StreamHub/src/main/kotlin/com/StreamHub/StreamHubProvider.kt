package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
import java.net.URLEncoder

class StreamHubProvider : MainAPI() {
    override var mainUrl = "https://streamhub.app" 
    override var name = "StreamHub"
    override val hasMainPage = true 
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    // Master list of TMDB Keys
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
    
    private data class TmdbDetails(
        @JsonProperty("id") val id: Int?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("poster_path") val posterPath: String?,
        @JsonProperty("backdrop_path") val backdropPath: String?,
        @JsonProperty("release_date") val releaseDate: String?,
        @JsonProperty("first_air_date") val firstAirDate: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TmdbGenre>?,
        @JsonProperty("seasons") val seasons: List<TmdbSeason>?,
        @JsonProperty("images") val images: TmdbImages?
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

    private data class TmdbSeasonDetails(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>?
    )

    private data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("still_path") val stillPath: String?,
        @JsonProperty("overview") val overview: String?
    )

    // --- CORE LOGIC ---
    private suspend inline fun <reified T : Any> fetchTmdb(url: String): T? {
        val keysToTry = tmdbApiKeys.shuffled().take(3)
        for (key in keysToTry) {
            try {
                val finalUrl = url.replace("{API_KEY}", key)
                val response = app.get(finalUrl).parsedSafe<T>()
                if (response != null) return response
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }

    // --- HOMEPAGE CONFIGURATION ---
    override val mainPage = mainPageOf(
        "$tmdbBase/trending/movie/week?api_key={API_KEY}" to "Trending Movies",
        "$tmdbBase/trending/tv/week?api_key={API_KEY}" to "Trending Shows",
        "$tmdbBase/discover/tv?api_key={API_KEY}&with_genres=16&sort_by=first_air_date.desc&with_original_language=ja&vote_average.gte=6&vote_count.gte=10&without_keywords=10121,9706,264386,280003,158718,281741" to "Trending Anime",
        "$tmdbBase/discover/tv?api_key={API_KEY}&with_original_language=ko&sort_by=first_air_date.desc&vote_average.gte=5&vote_count.gte=5&without_keywords=289844,291807,5832" to "Trending K-Drama",
        "$tmdbBase/discover/tv?api_key={API_KEY}&with_original_language=zh&sort_by=first_air_date.desc&vote_average.gte=4&vote_count.gte=2&without_genres=16,10759,10765,10768&with_keywords=9840|4265&without_keywords=289844,280003" to "Trending C-Drama"
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

    // --- SEARCH LOGIC ---
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

    // --- DETAILS & EPISODE LOGIC ---
    override suspend fun load(url: String): LoadResponse? {
        val isMovie = url.contains("/movie/")
        val tmdbId = url.substringAfterLast("/")
        
        val endpoint = if (isMovie) "movie" else "tv"
        // Appended images to request to grab the Title Logo
        val detailsUrl = "$tmdbBase/$endpoint/$tmdbId?api_key={API_KEY}&append_to_response=images"
        
        val details = fetchTmdb<TmdbDetails>(detailsUrl) ?: return null
        
        val title = details.title ?: details.name ?: return null
        val poster = details.posterPath?.let { "$imageBase$it" }
        val backdrop = details.backdropPath?.let { "$backdropBase$it" }

        // --- NEW HERO SECTION METADATA ---
        val parsedYear = (details.releaseDate ?: details.firstAirDate)?.split("-")?.firstOrNull()?.toIntOrNull()
        val parsedTags = details.genres?.mapNotNull { it.name }
        
        // Grab the English logo if it exists, otherwise grab the first available logo
        val logoPath = details.images?.logos?.firstOrNull { it.lang == "en" }?.filePath 
            ?: details.images?.logos?.firstOrNull()?.filePath
        val parsedLogo = logoPath?.let { "$backdropBase$it" }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = details.overview
                this.year = parsedYear
                this.tags = parsedTags
                this.duration = details.runtime // Renders as "120 min"
                
                // If your Cloudstream build throws a "logoUrl" error, simply delete this line:
                // this.logoUrl = parsedLogo 
            }
        } else {
            val episodes = coroutineScope {
                details.seasons?.filter { (it.seasonNumber ?: 0) > 0 }?.map { season ->
                    async {
                        val seasonUrl = "$tmdbBase/tv/$tmdbId/season/${season.seasonNumber}?api_key={API_KEY}"
                        val seasonDetails = fetchTmdb<TmdbSeasonDetails>(seasonUrl)
                        
                        seasonDetails?.episodes?.mapNotNull { ep ->
                            val epNum = ep.episodeNumber ?: return@mapNotNull null
                            val epUrl = "$mainUrl/tv/$tmdbId/${season.seasonNumber}/$epNum"
                            
                            newEpisode(epUrl) {
                                this.name = ep.name ?: "Episode $epNum"
                                this.season = season.seasonNumber
                                this.episode = epNum
                                this.posterUrl = ep.stillPath?.let { "$imageBase$it" }
                                this.description = ep.overview
                            }
                        } ?: emptyList()
                    }
                }?.awaitAll()?.flatten() ?: emptyList()
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = details.overview
                this.year = parsedYear
                this.tags = parsedTags
                // Total seasons tag is generated natively by Cloudstream using the 'episodes' list

                // If your Cloudstream build throws a "logoUrl" error, simply delete this line:
                // this.logoUrl = parsedLogo
            }
        }
    }

    // --- EXTRACTOR ROUTING ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundLinks = false

        if (Movies111Extractor.getStream(data, callback)) {
            foundLinks = true
        }

        if (VidcoreExtractor.getStream(data, callback)) {
            foundLinks = true
        }

        if (VidlinkExtractor.getStream(data, callback)) {
            foundLinks = true
        }
        
        return foundLinks
    }
}
