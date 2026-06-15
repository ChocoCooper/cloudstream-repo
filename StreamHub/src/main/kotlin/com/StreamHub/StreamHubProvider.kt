package com.StreamHub // This MUST match the plugin file

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.* // Resolves ExtractorLink, Qualities, and newExtractorLink

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
        val response = app.get(searchUrl).parsed<TmdbSearchResponse>()

        return response.results?.mapNotNull { movie ->
            val title = movie.title ?: return@mapNotNull null
            val id = movie.id?.toString() ?: return@mapNotNull null
            val posterUrl = movie.posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }

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
            this.plot = details.plot 
            this.tags = details.genres?.mapNotNull { it.name }
            // Removed the score assignment entirely to fix the Double vs Score type mismatch
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
