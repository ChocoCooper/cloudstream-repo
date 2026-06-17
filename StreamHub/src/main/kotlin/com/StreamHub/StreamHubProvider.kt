package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class StreamHubProvider : MainAPI() {
    override var mainUrl = "https://111movies.net"
    override var name = "StreamHub"
    // Disabled homepage to isolate search and metadata testing
    override val hasMainPage = false
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // TMDB Base Configuration
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
            
            details.seasons?.forEach { season ->
                val seasonNum = season.seasonNumber ?: return@forEach
                if (seasonNum == 0) return@forEach // Skip specials
                
                val episodeCount = season.episodeCount ?: 0
                for (epNum in 1..episodeCount) {
                    val epUrl = "$mainUrl/tv/$tmdbId/$seasonNum/$epNum"
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = "Episode $epNum"
                            this.season = seasonNum
                            this.episode = epNum
                        }
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
        // Stream implementation postponed for testing search/load logic.
        // Once ready, we will implement this using Cloudstream's Extractor API or app.evaluateJs.
        
        /* // Example of a safe, compiling ExtractorLink using positional arguments to avoid named parameter errors
        callback.invoke(
            ExtractorLink(
                this.name,
                "StreamHub",
                "https://example.com/stream.m3u8",
                mainUrl,
                Qualities.Unknown.value,
                true
            )
        )
        return true
        */
        
        return false
    }
}
