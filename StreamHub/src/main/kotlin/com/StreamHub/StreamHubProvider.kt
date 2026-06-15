package com.lagradost.cloudstream3.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
// Removed the broken AppUtils.parsedSafe import

class StreamHubProvider : MainAPI() {
    override var mainUrl = "https://api.xyra.stream"
    override var name = "StreamHub"
    override val hasMainPage = false
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)

    // API Configurations
    private val tmdbBaseUrl = "https://api.tmdb.org/3"
    private val tmdbApiKey = "ea118e768e75a1fe3b53dc99c9e4de09"
    private val xyraApiKey = "freekey"

    // --- Search Implementation ---
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$tmdbBaseUrl/search/movie?api_key=$tmdbApiKey&query=$query&include_adult=false"
        // Using .parsed() to fix the unresolved reference
        val response = app.get(searchUrl).parsed<TmdbSearchResponse>()

        return response.results?.mapNotNull { movie ->
            val title = movie.title ?: return@mapNotNull null
            val id = movie.id?.toString() ?: return@mapNotNull null
            val posterUrl = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }

            // Using the builder to fix MovieSearchResponse constructor deprecation
            newMovieSearchResponse(title, url = id, type = TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = movie.releaseDate?.substringBefore("-")?.toIntOrNull()
            }
        } ?: emptyList()
    }

    // --- Media Details Implementation ---
    override suspend fun load(url: String): LoadResponse? {
        val detailsUrl = "$tmdbBaseUrl/movie/$url?api_key=$tmdbApiKey"
        val details = app.get(detailsUrl).parsed<TmdbDetailsResponse>()
        val title = details.title ?: return null

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = details.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            this.backgroundPosterUrl = details.backdropPath?.let { "https://image.tmdb.org/t/p/w1280$it" }
            this.year = details.releaseDate?.substringBefore("-")?.toIntOrNull()
            // Fixed 'overview' Unresolved Reference and migrated 'rating' to 'score'
            this.plot = details.plot 
            this.score = details.voteAverage
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
        val streamUrl = "$mainUrl/v1/streamhub/streams?api_key=$xyraApiKey&tmdb_id=$data"
        val response = app.get(streamUrl).parsed<XyraResponse>()

        response.streams?.forEach { stream ->
            val qualityInfo = stream.title ?: ""
            if (qualityInfo.contains("2160p", ignoreCase = true)) return@forEach

            val link = stream.url ?: return@forEach
            val providerName = stream.name ?: "Unknown"
            val isM3u8 = link.contains(".m3u8", ignoreCase = true)

            val mappedQuality = when {
                qualityInfo.contains("1080p") -> Qualities.P1080.value
                qualityInfo.contains("720p") -> Qualities.P720.value
                qualityInfo.contains("480p") -> Qualities.P480.value
                else -> Qualities.Unknown.value
            }

            // Using newExtractorLink to fix ExtractorLink constructor deprecation
            callback.invoke(
                newExtractorLink(
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
