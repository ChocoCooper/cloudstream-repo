package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import java.net.URLEncoder

class StreamHub : MainAPI() {
    override var mainUrl = "https://111movies.net"
    override var name = "StreamHub"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private val tmdbApiKey = "ea118e768e75a1fe3b53dc99c9e4de09"
    private val tmdbBase = "https://api.tmdb.org/3"
    private val imageBase = "https://image.tmdb.org/t/p/w500"

    // Minimal TMDB Data Classes
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
        @JsonProperty("seasons") val seasons: List<TmdbSeason>?
    )

    private data class TmdbSeason(
        @JsonProperty("season_number") val seasonNumber: Int?,
        @JsonProperty("episode_count") val episodeCount: Int?
    )

    override suspend fun getMainPage(page: Int, request : MainPageRequest): HomePageResponse {
        val url = "$tmdbBase/trending/all/week?api_key=$tmdbApiKey&page=$page"
        val response = app.get(url).parsedSafe<TmdbSearchResponse>()
        
        val items = response?.results?.mapNotNull { result ->
            val isMovie = result.mediaType == "movie" || result.title != null
            val title = result.title ?: result.name ?: return@mapNotNull null
            val id = result.id ?: return@mapNotNull null
            val poster = result.posterPath?.let { "$imageBase$it" }
            
            // Encode the TMDB ID into the URL so we know what to fetch in load()
            val urlPath = if (isMovie) "movie/$id" else "tv/$id"
            
            newMovieSearchResponse(title, "$mainUrl/$urlPath", TvType.Movie) {
                this.posterUrl = poster
            }
        } ?: emptyList()

        return HomePageResponse(arrayListOf(HomePageList("Trending Now", items)), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$tmdbBase/search/multi?api_key=$tmdbApiKey&query=$encodedQuery"
        val response = app.get(url).parsedSafe<TmdbSearchResponse>()

        return response?.results?.mapNotNull { result ->
            val isMovie = result.mediaType == "movie" || result.title != null
            val title = result.title ?: result.name ?: return@mapNotNull null
            val id = result.id ?: return@mapNotNull null
            val poster = result.posterPath?.let { "$imageBase$it" }
            
            val urlPath = if (isMovie) "movie/$id" else "tv/$id"

            newMovieSearchResponse(title, "$mainUrl/$urlPath", if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = poster
            }
        } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val isMovie = url.contains("/movie/")
        val tmdbId = url.substringAfterLast("/")
        
        val endpoint = if (isMovie) "movie" else "tv"
        val detailsUrl = "$tmdbBase/$endpoint/$tmdbId?api_key=$tmdbApiKey"
        
        val details = app.get(detailsUrl).parsedSafe<TmdbDetails>() ?: return null
        
        val title = details.title ?: details.name ?: return null
        val poster = details.posterPath?.let { "$imageBase$it" }
        val backdrop = details.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }

        if (isMovie) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = details.overview
            }
        } else {
            val episodes = mutableListOf<Episode>()
            
            // Loop through seasons and populate episodes
            details.seasons?.forEach { season ->
                val seasonNum = season.seasonNumber ?: return@forEach
                if (seasonNum == 0) return@forEach // Skip specials
                
                val episodeCount = season.episodeCount ?: 0
                for (epNum in 1..episodeCount) {
                    // Build the specific 111movies URL for this episode
                    val epUrl = "$mainUrl/tv/$tmdbId/$seasonNum/$epNum"
                    episodes.add(
                        Episode(
                            data = epUrl,
                            name = "Episode $epNum",
                            season = seasonNum,
                            episode = epNum
                        )
                    )
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = details.overview
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' holds the formatted URL: https://111movies.net/movie/{tmdbid} 
        // or https://111movies.net/tv/{tmdbid}/{season}/{episode}
        
        var streamUrl: String? = null

        // This utilizes Cloudstream's WebView interceptor to execute the obfuscated JS
        // and catch the raw M3U8 request sent by the Fluid Player.
        AppUtils.loadWebView(data) { request ->
            val requestUrl = request.url.toString()
            
            // Check if the network request is fetching the HLS playlist
            if (requestUrl.contains(".m3u8") || requestUrl.contains("master.m3u8") || requestUrl.contains("index.m3u8")) {
                streamUrl = requestUrl
                true // Returning true stops the WebView once we capture the link
            } else {
                false
            }
        }

        if (streamUrl != null) {
            callback.invoke(
                ExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = streamUrl!!,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return true
        }

        return false
    }
}
