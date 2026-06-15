package com.streamhub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parsedSafe
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class StreamHubProvider : MainAPI() {
    override var mainUrl = "https://api.xyra.stream"
    override var name = "StreamHub"
    override val hasMainPage = false // Skipping homepage as requested
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)

    // API Configurations
    private val tmdbBaseUrl = "https://api.tmdb.org/3"
    private val tmdbApiKey = "ea118e768e75a1fe3b53dc99c9e4de09"
    private val xyraApiKey = "freekey"

    // --- Search Implementation ---
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=$query&include_adult=false"
        val response = app.get(searchUrl).parsedSafe<TmdbSearchResponse>()

        return response?.results?.mapNotNull { movie ->
            val title = movie.title ?: return@mapNotNull null
            val id = movie.id?.toString() ?: return@mapNotNull null
            val posterPath = movie.posterPath
            val posterUrl = if (posterPath != null) "https://image.tmdb.org/t/p/w500$posterPath" else null

            MovieSearchResponse(
                title = title,
                url = id, // Passing the TMDB ID as the URL for the load phase
                apiName = this.name,
                type = TvType.Movie,
                posterUrl = posterUrl,
                year = movie.releaseDate?.substringBefore("-")?.toIntOrNull()
            )
        } ?: emptyList()
    }

    // --- Media Details Implementation ---
    override suspend fun load(url: String): LoadResponse? {
        // Here 'url' is the TMDB ID we passed in the SearchResponse
        val tmdbId = url
        val detailsUrl = "$tmdbBaseUrl/movie/$tmdbId?api_key=$tmdbApiKey"
        
        val details = app.get(detailsUrl).parsedSafe<TmdbDetailsResponse>() ?: return null

        val title = details.title ?: return null
        val posterPath = details.posterPath
        val posterUrl = if (posterPath != null) "https://image.tmdb.org/t/p/w500$posterPath" else null
        val backgroundPath = details.backdropPath
        val backgroundUrl = if (backgroundPath != null) "https://image.tmdb.org/t/p/w1280$backgroundPath" else null

        return newMovieLoadResponse(title, tmdbId, TvType.Movie, tmdbId) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backgroundUrl
            this.year = details.releaseDate?.substringBefore("-")?.toIntOrNull()
            this.plot = details.overview
            this.rating = (details.voteAverage?.times(10))?.toInt() // Converts 8.5 to 850, Cloudstream standardizes it
            this.tags = details.genres?.mapNotNull { it.name }
        }
    }

    // --- Streaming Links Implementation ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 'data' is the TMDB ID passed from the LoadResponse
        val tmdbId = data
        val streamUrl = "$mainUrl/v1/streamhub/streams?api_key=$xyraApiKey&tmdb_id=$tmdbId"

        val response = app.get(streamUrl).parsedSafe<XyraResponse>() ?: return false

        response.streams?.forEach { stream ->
            val qualityInfo = stream.title ?: ""
            
            // Explicitly ignore massive 2160p sizes
            if (qualityInfo.contains("2160p", ignoreCase = true)) {
                return@forEach
            }

            val link = stream.url ?: return@forEach
            val providerName = stream.name ?: "Unknown"
            val isM3u8 = link.contains(".m3u8", ignoreCase = true)

            // Attempt to map Cloudstream Qualities
            val mappedQuality = when {
                qualityInfo.contains("1080p") -> Qualities.P1080.value
                qualityInfo.contains("720p") -> Qualities.P720.value
                qualityInfo.contains("480p") -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = "$providerName - $qualityInfo",
                    url = link,
                    referer = "",
                    quality = mappedQuality,
                    isM3u8 = isM3u8
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
        @JsonProperty("vote_average") val voteAverage: Double?,
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
}
