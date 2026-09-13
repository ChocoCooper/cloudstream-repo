package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import java.net.URLEncoder

class StreamHubProvider : MainAPI() {

    companion object {
        // ============================================================
        //  SIMKL CONFIG — SINGLE SOURCE OF TRUTH FOR THE WHOLE PLUGIN
        // ============================================================
        const val SIMKL_CLIENT_ID    = "06c50bb7a74b59fb0d1630bf528671feaa8b982e1dac505ad819445fb5effda6"
        const val SIMKL_APP_NAME     = "cloudstream"
        const val SIMKL_APP_VERSION  = "1.0"
        const val SIMKL_BASE         = "https://api.simkl.com"
        const val SIMKL_CDN_BASE     = "https://data.simkl.in"

        fun simklHeaders(): Map<String, String> =
            mapOf("User-Agent" to "$SIMKL_APP_NAME/$SIMKL_APP_VERSION")

        fun simklUrl(endpoint: String, extra: String = ""): String {
            val base = "$SIMKL_BASE/$endpoint"
            val sep = if (endpoint.contains("?")) "&" else "?"
            val tail = if (extra.isBlank()) "" else "&$extra"
            return "$base${sep}client_id=$SIMKL_CLIENT_ID" +
                "&app-name=$SIMKL_APP_NAME" +
                "&app-version=$SIMKL_APP_VERSION" +
                tail
        }

        fun simklCdnUrl(path: String): String =
            "$SIMKL_CDN_BASE/$path?client_id=$SIMKL_CLIENT_ID" +
                "&app-name=$SIMKL_APP_NAME" +
                "&app-version=$SIMKL_APP_VERSION"

        const val SIMKL_POSTER_BASE = "https://wsrv.nl/?url=https://simkl.in/posters/"
        const val SIMKL_FANART_BASE = "https://wsrv.nl/?url=https://simkl.in/fanart/"

        fun simklPoster(path: String?): String? =
            path?.takeIf { it.isNotBlank() }?.let { "${SIMKL_POSTER_BASE}${it}_m.webp&q=90" }

        fun simklFanart(path: String?): String? =
            path?.takeIf { it.isNotBlank() }?.let { "${SIMKL_FANART_BASE}${it}_m.webp&q=90" }
    }

    override var mainUrl      = "https://streamhub.app"
    override var name         = "StreamHub"
    override val hasMainPage  = true
    override var lang         = "ta"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    // ============================================================
    //  SIMKL DATA MODELS
    // ============================================================

    /** Matches the search response item shape from /search/{type}. */
    private data class SimklSearchResult(
        @JsonProperty("title")         val title: String?       = null,
        @JsonProperty("year")          val year: Int?           = null,
        @JsonProperty("poster")        val poster: String?      = null,
        @JsonProperty("fanart")        val fanart: String?      = null,
        @JsonProperty("overview")      val overview: String?    = null,
        @JsonProperty("genres")        val genres: List<String>? = null,
        @JsonProperty("endpoint_type") val endpointType: String? = null,
        @JsonProperty("ids")           val ids: SimklIds?       = null
    )

    /** IDs as returned by Simkl. Note: search/trending use `simkl_id`. */
    private data class SimklIds(
        @JsonProperty("simkl_id") val simklId: Int?   = null,
        @JsonProperty("simkl")    val simkl: Int?     = null, // detail endpoint variant
        @JsonProperty("slug")     val slug: String?   = null,
        @JsonProperty("tmdb")     val tmdb: String?   = null,
        @JsonProperty("mal")      val mal: Any?       = null,
        @JsonProperty("anilist")  val anilist: Any?   = null,
        @JsonProperty("imdb")     val imdb: String?   = null
    )

    /** Detail endpoint response (movies/{id}, tv/{id}, anime/{id}). */
    private data class SimklDetail(
        @JsonProperty("title")          val title: String?             = null,
        @JsonProperty("year")           val year: Int?                 = null,
        @JsonProperty("overview")       val overview: String?          = null,
        @JsonProperty("genres")         val genres: List<String>?      = null,
        @JsonProperty("poster")         val poster: String?            = null,
        @JsonProperty("fanart")         val fanart: String?            = null,
        @JsonProperty("ids")            val ids: SimklIds?             = null,
        @JsonProperty("runtime")        val runtime: Int?              = null,
        @JsonProperty("total_episodes") val totalEpisodes: Int?        = null,
        @JsonProperty("seasons")        val seasons: List<SimklSeason>? = null
    )

    private data class SimklSeason(
        @JsonProperty("number")   val number: Int?                  = null,
        @JsonProperty("episodes") val episodes: List<SimklEpisode>? = null
    )

    private data class SimklEpisode(
        @JsonProperty("number") val number: Int?   = null,
        @JsonProperty("title")  val title: String? = null,
        @JsonProperty("img")    val img: String?   = null
    )

    /** Trending CDN file wrapper: { "tv": [...], "movies": [...], "anime": [...] } */
    private data class SimklTrendingWrapper(
        @JsonProperty("tv")     val tv: List<SimklTrendingItem>?     = null,
        @JsonProperty("movies") val movies: List<SimklTrendingItem>? = null,
        @JsonProperty("anime")  val anime: List<SimklTrendingItem>?  = null
    )

    private data class SimklTrendingItem(
        @JsonProperty("title")   val title: String?          = null,
        @JsonProperty("poster")  val poster: String?         = null,
        @JsonProperty("fanart")  val fanart: String?         = null,
        @JsonProperty("overview") val overview: String?      = null,
        @JsonProperty("genres")  val genres: List<String>?   = null,
        @JsonProperty("year")    val year: Int?              = null,
        @JsonProperty("ids")     val ids: SimklIds?          = null,
        @JsonProperty("url")     val url: String?            = null
    )

    // ============================================================
    //  SIMKL API CALLS
    // ============================================================

    private suspend fun fetchSimklDetail(type: String, id: String): SimklDetail? {
        return try {
            val url = simklUrl("$type/$id", "extended=full")
            val text = app.get(url, timeout = 15L, headers = simklHeaders()).text
            AppUtils.tryParseJson<SimklDetail>(text)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun searchSimklRaw(query: String): List<SimklSearchResult> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val types = listOf("movie", "tv", "anime")
        val allResults = mutableListOf<SimklSearchResult>()

        for (type in types) {
            try {
                val url = simklUrl("search/$type", "q=$encoded&extended=full")
                val text = app.get(url, timeout = 15L, headers = simklHeaders()).text
                val results = AppUtils.tryParseJson<List<SimklSearchResult>>(text) ?: continue
                allResults.addAll(results)
            } catch (_: Exception) {
                // Skip this type on failure, continue with others
            }
        }
        return allResults
    }

    // ============================================================
    //  ID EXTRACTION HELPERS
    // ============================================================

    private fun extractId(value: Any?): String? = when (value) {
        is Int    -> value.toString()
        is Long   -> value.toString()
        is String -> value
        is Map<*, *> -> (value["id"] as? Number)?.toInt()?.toString()
            ?: (value["ids"] as? Map<*, *>)?.get("id")?.let { it as? Number }?.toInt()?.toString()
        else -> null
    }

    private fun getSimklId(ids: SimklIds?): String? {
        return ids?.simklId?.toString() ?: ids?.simkl?.toString()
    }

    // ============================================================
    //  MAIN PAGE
    // ============================================================

    override val mainPage = mainPageOf(
        simklCdnUrl("discover/trending/today_100.json") to "Simkl Trending Today",
        simklCdnUrl("discover/trending/movies_today_100.json") to "Simkl Trending Movies",
        simklCdnUrl("discover/trending/tv_today_100.json") to "Simkl Trending TV Shows",
        simklCdnUrl("discover/trending/anime_today_100.json") to "Simkl Trending Anime",
        simklCdnUrl("calendar/v2/tv.json") to "Airing Today",
        simklCdnUrl("calendar/v2/anime.json") to "Airing Anime"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return try {
            val text = app.get(request.data, timeout = 15L, headers = simklHeaders()).text
            val items = parseHomepageData(text, request.name)
            if (items.isEmpty()) return null
            newHomePageResponse(request.name, items)
        } catch (e: Exception) {
            println("StreamHub getMainPage error: ${e.message}")
            null
        }
    }

    private fun parseHomepageData(text: String, sectionName: String): List<SearchResponse> {
        // Try wrapper shape first (trending files)
        val wrapper = AppUtils.tryParseJson<SimklTrendingWrapper>(text)
        if (wrapper != null) {
            val list = when {
                sectionName.contains("Movies", true) -> wrapper.movies
                sectionName.contains("TV", true)     -> wrapper.tv
                sectionName.contains("Anime", true)  -> wrapper.anime
                else -> (wrapper.movies ?: emptyList()) + (wrapper.tv ?: emptyList()) + (wrapper.anime ?: emptyList())
            }
            return list?.mapNotNull { mapTrendingItemToSearchResponse(it) } ?: emptyList()
        }

        // Try flat list shape (calendar files)
        val flat = AppUtils.tryParseJson<List<SimklSearchResult>>(text)
        if (flat != null) {
            return flat.mapNotNull { mapSimklResultToSearchResponse(it) }
        }

        return emptyList()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return searchSimklRaw(query).mapNotNull { mapSimklResultToSearchResponse(it) }
    }

    private fun mapSimklResultToSearchResponse(result: SimklSearchResult): SearchResponse? {
        val title   = result.title ?: return null
        val simklId = getSimklId(result.ids) ?: return null
        val type    = result.endpointType ?: "movie"

        val (urlPath, tvType) = when (type) {
            "movies" -> "movie/$simklId" to TvType.Movie
            "anime"  -> "anime/$simklId" to TvType.Anime
            else     -> "tv/$simklId"    to TvType.TvSeries
        }

        return newMovieSearchResponse(title, "$mainUrl/$urlPath", tvType) {
            this.posterUrl = simklPoster(result.poster)
        }
    }

    private fun mapTrendingItemToSearchResponse(item: SimklTrendingItem): SearchResponse? {
        val title   = item.title ?: return null
        val simklId = getSimklId(item.ids) ?: return null

        // Determine type from url path: "/tv/..." or "/anime/..." or "/movies/..."
        val urlPath = item.url ?: return null
        val (path, tvType) = when {
            urlPath.contains("/anime/")  -> "anime/$simklId" to TvType.Anime
            urlPath.contains("/movies/") -> "movie/$simklId" to TvType.Movie
            urlPath.contains("/tv/")     -> "tv/$simklId"    to TvType.TvSeries
            else -> "movie/$simklId" to TvType.Movie
        }

        return newMovieSearchResponse(title, "$mainUrl/$path", tvType) {
            this.posterUrl = simklPoster(item.poster)
        }
    }

    // ============================================================
    //  LOAD DETAILS
    // ============================================================

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.substringBefore("?")
        val isMovie  = cleanUrl.contains("/movie/") || cleanUrl.contains("/movies/")
        val isAnime  = cleanUrl.contains("/anime/")
        val simklId  = cleanUrl.substringAfterLast("/")

        val type = when {
            isMovie -> "movies"
            isAnime -> "anime"
            else    -> "tv"
        }

        val details = fetchSimklDetail(type, simklId) ?: return null
        return buildLoadResponse(details, type, simklId)
    }

    private suspend fun buildLoadResponse(
        details: SimklDetail,
        type: String,
        simklId: String
    ): LoadResponse? {
        val title     = details.title ?: return null
        val poster    = simklPoster(details.poster)
        val fanart    = simklFanart(details.fanart)
        val tmdbId    = details.ids?.tmdb
        val imdbId    = details.ids?.imdb
        val malId     = extractId(details.ids?.mal)
        val anilistId = extractId(details.ids?.anilist)
        val year      = details.year
        val tags      = details.genres ?: emptyList()

        val idQuery = buildString {
            append("simkl=").append(simklId)
            tmdbId?.let    { append("&tmdb=").append(it) }
            imdbId?.let    { append("&imdb=").append(it) }
            malId?.let     { append("&mal=").append(it) }
            anilistId?.let { append("&anilist=").append(it) }
        }

        return if (type == "movies") {
            val dataUrl = "$mainUrl/movie/$simklId?$idQuery"
            newMovieLoadResponse(title, dataUrl, TvType.Movie, dataUrl) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = fanart
                this.plot                = details.overview
                this.year                = year
                this.tags                = tags
            }
        } else {
            val episodes = mutableListOf<Episode>()
            details.seasons?.forEach { season ->
                val sNum = season.number ?: return@forEach
                season.episodes?.forEach { ep ->
                    val epNum = ep.number ?: return@forEach
                    val epUrl = "$mainUrl/$type/$simklId/$sNum/$epNum?$idQuery"
                    episodes.add(newEpisode(epUrl) {
                        this.name    = ep.title ?: "Episode $epNum"
                        this.season  = sNum
                        this.episode = epNum
                        this.posterUrl = ep.img?.let { "${SIMKL_POSTER_BASE}${it}_m.webp&q=90" }
                    })
                }
            }
            val tvType = if (type == "anime") TvType.Anime else TvType.TvSeries
            val dataUrl = "$mainUrl/$type/$simklId?$idQuery"
            newTvSeriesLoadResponse(title, dataUrl, tvType, episodes) {
                this.posterUrl           = poster
                this.backgroundPosterUrl = fanart
                this.plot                = details.overview
                this.year                = year
                this.tags                = tags
            }
        }
    }

    // ============================================================
    //  LOAD LINKS
    // ============================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.substringBefore("?")
        val parts = cleanData.split("/").filter { it.isNotBlank() }
        if (parts.size < 2) return false

        val type      = parts[0]
        val simklId   = parts[1]
        val season    = if (parts.size >= 3) parts[2].toIntOrNull() else null
        val episode   = if (parts.size >= 4) parts[3].toIntOrNull() else null
        val isMovie   = type == "movies" || type == "movie"

        val tmdbId    = data.substringAfter("tmdb=", "").substringBefore("&").takeIf { it.isNotBlank() && it != "null" }
        val imdbId    = data.substringAfter("imdb=", "").substringBefore("&").takeIf { it.isNotBlank() && it != "null" }
        val malId     = data.substringAfter("mal=", "").substringBefore("&").takeIf { it.isNotBlank() && it != "null" }
        val anilistId = data.substringAfter("anilist=", "").substringBefore("&").takeIf { it.isNotBlank() && it != "null" }

        return coroutineScope {
            val jobs = mutableListOf<Deferred<Boolean>>()

            // --- Vidlove (movies + tv) ---
            if (type == "movies" || type == "movie" || type == "tv") {
                jobs.add(async {
                    VidloveExtractor.getStreams(
                        simklId = simklId,
                        type = if (type == "movie") "movies" else type,
                        season = season,
                        episode = episode,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                        tmdbHint = tmdbId
                    )
                })
            }

            // --- ZokoAnime (anime only) ---
            if (type == "anime" && episode != null && (malId != null || anilistId != null)) {
                jobs.add(async {
                    ZokoAnimeExtractor.getStreams(
                        malId = malId,
                        anilistId = anilistId,
                        episode = episode,
                        callback = callback
                    )
                })
            }

            // --- OpenSubtitles (parallel) ---
            if (imdbId != null) {
                jobs.add(async {
                    try {
                        val osUrl = if (isMovie) {
                            "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
                        } else {
                            "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$season:$episode.json"
                        }
                        val subs = JSONObject(app.get(osUrl, timeout = 8L).text).optJSONArray("subtitles")
                        if (subs != null) {
                            for (i in 0 until subs.length()) {
                                val sub = subs.getJSONObject(i)
                                val subUrl  = sub.optString("url")
                                val subLang = sub.optString("lang")
                                if (subUrl.isNotBlank() && subLang.equals("English", ignoreCase = true)) {
                                    subtitleCallback.invoke(SubtitleFile(subLang, subUrl))
                                }
                            }
                        }
                    } catch (_: Exception) {}
                    false
                })
            }

            val results = jobs.awaitAll()
            results.any { it }
        }
    }
}
