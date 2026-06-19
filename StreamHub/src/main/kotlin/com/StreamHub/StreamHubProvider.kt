package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll
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

    private data class WyzieSub(
        @JsonProperty("url") val url: String?,
        @JsonProperty("language") val language: String?,
        @JsonProperty("display") val display: String?
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
            
            // FIX: Removed backgroundPosterUrl from SearchResponse to fix the Action compiler error
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
        val backdrop = details.backdropPath?.let { "$backdropBase$it" } // Perfectly valid in LoadResponse for the Hero Page
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
            
            // --- 1. TRIPLE LAYER SUBTITLE NETWORK ---
            val subJob = async {
                // Layer 1: Official Stremio OpenSubtitles v3
                if (imdbId != null) {
                    try {
                        val os3Url = if(isMovie) "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json" 
                                     else "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$season:$episode.json"
                        val res = app.get(os3Url, timeout = 5).text
                        val subs = JSONObject(res).optJSONArray("subtitles")
                        if (subs != null) {
                            for (i in 0 until subs.length()) {
                                val sub = subs.getJSONObject(i)
                                val url = sub.optString("url")
                                val lang = sub.optString("lang")
                                if (url.isNotBlank() && lang.isNotBlank()) subtitleCallback.invoke(SubtitleFile(lang, url))
                            }
                        }
                    } catch (e: Exception) {}
                }

                // Layer 2: Legacy Stremio OpenSubtitles
                if (imdbId != null) {
                    try {
                        val osLegacyUrl = if(isMovie) "https://opensubtitles.strem.io/stremio/v1/subtitles/movie/$imdbId.json" 
                                          else "https://opensubtitles.strem.io/stremio/v1/subtitles/series/$imdbId:$season:$episode.json"
                        val res = app.get(osLegacyUrl, timeout = 5).text
                        val subs = JSONObject(res).optJSONArray("subtitles")
                        if (subs != null) {
                            for (i in 0 until subs.length()) {
                                val sub = subs.getJSONObject(i)
                                val url = sub.optString("url")
                                val lang = sub.optString("lang")
                                if (url.isNotBlank() && lang.isNotBlank()) subtitleCallback.invoke(SubtitleFile(lang, url))
                            }
                        }
                    } catch (e: Exception) {}
                }

                // Layer 3: Wyziesubs API
                try {
                    var wyzieUrl = "https://sub.wyzie.io/search?id=$tmdbId&source=all&key=$wyzieApiKey"
                    if (!isMovie && season != null && episode != null) wyzieUrl += "&season=$season&episode=$episode"
                    val wyzieResponse = app.get(wyzieUrl, timeout = 5).text
                    
                    val wyzieList = AppUtils.tryParseJson<List<WyzieSub>>(wyzieResponse) 
                        ?: JSONObject(wyzieResponse).optJSONArray("subtitles")?.let { array ->
                            (0 until array.length()).mapNotNull { i -> AppUtils.tryParseJson<WyzieSub>(array.getJSONObject(i).toString()) }
                        }

                    wyzieList?.forEach { sub ->
                        val subUrl = sub.url ?: return@forEach
                        val lang = sub.display ?: sub.language ?: "English"
                        subtitleCallback.invoke(SubtitleFile(lang, subUrl))
                    }
                } catch (e: Exception) {}
            }

            // --- 2. BLAZING FAST DIRECT APIs ---
            val directExtractors = listOf(
                async {
                    // API 1: Madplay CDN 
                    // FIX: Replaced 'url =' with 'streamUrl =' to fix the Github Actions compilation mismatch
                    try {
                        val url = if (season == null) "https://cdn.madplay.site/api/hls/unknown/${tmdbId}/master.m3u8"
                                  else "https://cdn.madplay.site/api/hls/unknown/${tmdbId}/season_${season}/episode_${episode}/master.m3u8"
                        var found = false
                        M3u8Helper.generateM3u8(
                            source = "Madplay CDN", 
                            streamUrl = url, 
                            referer = "https://madplay.site/",
                            headers = mapOf("Origin" to "https://madplay.site")
                        ).forEach { link ->
                            callback.invoke(link)
                            found = true
                        }
                        found
                    } catch(e: Exception) { false }
                },
                async {
                    // API 2: VidSrcWtf Direct Streams
                    try {
                        val url = if (isMovie) "https://www.vidsrc.wtf/main/movie/$tmdbId" else "https://www.vidsrc.wtf/main/tv/$tmdbId/$season/$episode"
                        val res = app.get(url, timeout = 5, headers = mapOf("Origin" to "https://www.vidsrc.wtf", "Referer" to "https://www.vidsrc.wtf/")).text
                        val streamUrl = JSONObject(res).getJSONObject("stream").getString("url")
                        if (streamUrl.contains(".m3u8")) {
                            callback.invoke(newExtractorLink(
                                "VidSrcWtf", 
                                "VidSrcWtf", 
                                streamUrl, 
                                ExtractorLinkType.M3U8
                            ) {
                                this.headers = mapOf("Origin" to "https://www.vidsrc.wtf", "Referer" to "https://www.vidsrc.wtf/")
                                this.quality = Qualities.P1080.value
                            })
                            true
                        } else false
                    } catch(e: Exception) { false }
                },
                async {
                    // API 3: Playsrc API
                    try {
                        val url = if (season == null) "https://api.madplay.site/api/playsrc?id=$tmdbId&token=direct" else "https://madplay.site/api/movies/holly?id=$tmdbId&season=$season&episode=$episode&token=direct"
                        val resText = app.get(url, timeout = 5).text
                        val jsonArray = JSONArray(resText)
                        var found = false
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.getJSONObject(i)
                            val file = obj.getString("file")
                            if (file.isNotBlank()) {
                                callback.invoke(newExtractorLink(
                                    "Playsrc", 
                                    "Playsrc", 
                                    file, 
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.headers = mapOf("Origin" to "https://madplay.site")
                                    this.quality = Qualities.P1080.value
                                })
                                found = true
                            }
                        }
                        found
                    } catch(e: Exception) { false }
                },
                async {
                    // API 4: Streamvix Stremio Addon
                    try {
                        if (imdbId == null) return@async false
                        val url = if (isMovie) "https://streamvix.com/stream/movie/$imdbId.json" else "https://streamvix.com/stream/series/$imdbId:$season:$episode.json"
                        val res = app.get(url, timeout = 5).text
                        val streams = JSONObject(res).optJSONArray("streams") ?: return@async false
                        var found = false
                        for(i in 0 until streams.length()) {
                            val streamUrl = streams.getJSONObject(i).optString("url")
                            if (streamUrl.contains(".m3u8")) {
                                callback.invoke(newExtractorLink(
                                    "Streamvix", 
                                    "Streamvix", 
                                    streamUrl, 
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.quality = Qualities.P1080.value
                                })
                                found = true
                            }
                        }
                        found
                    } catch(e: Exception) { false }
                },
                async {
                    // API 5: NoTorrent Stremio Addon
                    try {
                        if (imdbId == null) return@async false
                        val url = if (isMovie) "https://notorrent.strem.fun/stream/movie/$imdbId.json" else "https://notorrent.strem.fun/stream/series/$imdbId:$season:$episode.json"
                        val res = app.get(url, timeout = 5).text
                        val streams = JSONObject(res).optJSONArray("streams") ?: return@async false
                        var found = false
                        for(i in 0 until streams.length()) {
                            val streamUrl = streams.getJSONObject(i).optString("url")
                            if (streamUrl.contains(".m3u8")) {
                                callback.invoke(newExtractorLink(
                                    "NoTorrent", 
                                    "NoTorrent", 
                                    streamUrl, 
                                    ExtractorLinkType.M3U8
                                ) {
                                    this.quality = Qualities.P1080.value
                                })
                                found = true
                            }
                        }
                        found
                    } catch(e: Exception) { false }
                }
            )

            // --- 3. NATIVE IFRAME SCRAPERS (Hubs & Embeds) ---
            val embedExtractors = listOf(
                async {
                    // API 6: AutoEmbed.co Hub
                    try {
                        val url = if (isMovie) "https://autoembed.co/movie/tmdb/$tmdbId" else "https://autoembed.co/tv/tmdb/$tmdbId-$season-$episode"
                        val html = app.get(url, timeout = 8).text
                        val iframes = Regex("""<iframe[^>]+src="([^"]+)"""").findAll(html)
                        var found = false
                        iframes.forEach { match ->
                            val iframeUrl = match.groupValues[1]
                            if (iframeUrl.startsWith("http")) {
                                loadExtractor(iframeUrl, url, subtitleCallback, callback)
                                found = true
                            }
                        }
                        found
                    } catch(e: Exception) { false }
                },
                async {
                    // API 7: MultiEmbed.mov Hub
                    try {
                        val url = if (isMovie) "https://multiembed.mov/directstream.php?video_id=$tmdbId&tmdb=1" else "https://multiembed.mov/directstream.php?video_id=$tmdbId&tmdb=1&s=$season&e=$episode"
                        val html = app.get(url, timeout = 8).text
                        val iframes = Regex("""<iframe[^>]+src="([^"]+)"""").findAll(html)
                        var found = false
                        iframes.forEach { match ->
                            val iframeUrl = match.groupValues[1]
                            if (iframeUrl.startsWith("http") && !iframeUrl.contains("youtube")) {
                                loadExtractor(iframeUrl, url, subtitleCallback, callback)
                                found = true
                            }
                        }
                        found
                    } catch(e: Exception) { false }
                },
                async {
                    // API 8: 2Embed.cc Hub
                    try {
                        val url = if (isMovie) "https://www.2embed.cc/embed/$tmdbId" else "https://www.2embed.cc/embedtv/$tmdbId&s=$season&e=$episode"
                        val html = app.get(url, timeout = 8).text
                        val iframes = Regex("""data-src="([^"]+)"""").findAll(html)
                        var found = false
                        iframes.forEach { match ->
                            val iframeUrl = match.groupValues[1]
                            if (iframeUrl.startsWith("http")) {
                                loadExtractor(iframeUrl, url, subtitleCallback, callback)
                                found = true
                            }
                        }
                        found
                    } catch(e: Exception) { false }
                },
                async {
                    // API 9: PlayImdb Server 
                    try {
                        if (imdbId == null) return@async false
                        val url = if (season == null) "https://playimdb.net/embed/$imdbId" else "https://playimdb.net/embed/tv?imdb=$imdbId&season=$season&episode=$episode"
                        val html = app.get(url, timeout = 5).document
                        val iframe = html.selectFirst("#player_iframe")?.attr("src") ?: return@async false
                        val finalIframe = if (!iframe.startsWith("http")) "https:$iframe" else iframe
                        
                        loadExtractor(finalIframe, "https://playimdb.net/", subtitleCallback, callback)
                        true
                    } catch(e: Exception) { false }
                }
            )

            // --- 4. WEBVIEW FALLBACK EXTRACTORS ---
            val webviewExtractors = listOf(
                async { Movies111Extractor.getStream(cleanData, callback) },
                async { VidcoreExtractor.getStream(cleanData, callback) },
                async { VidlinkExtractor.getStream(cleanData, callback) },
                async { 
                    try {
                        val vidsrcUrl = if(isMovie) "https://vidsrc.in/embed/movie/$tmdbId" else "https://vidsrc.in/embed/tv/$tmdbId/$season/$episode"
                        val interceptor = WebViewResolver(Regex("""(?:vidsrc|rcp).*?\.m3u8"""))
                        val response = app.get(vidsrcUrl, interceptor = interceptor)
                        if (response.url.contains(".m3u8")) {
                            callback.invoke(newExtractorLink("Vidsrc.in", "Vidsrc.in", response.url, ExtractorLinkType.M3U8) {
                                this.referer = "https://vidsrc.in/"
                                this.quality = Qualities.P1080.value
                            })
                            true
                        } else false
                    } catch(e: Exception) { false }
                }
            )

            val allResults = (directExtractors + embedExtractors + webviewExtractors).awaitAll()
            
            subJob.await()

            allResults.any { it }
        }
    }
}
