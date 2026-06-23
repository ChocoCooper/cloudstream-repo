package com.NoTorrent

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class NoTorrentProvider : MainAPI() {
    override var mainUrl = "https://api.xyra.stream"
    override var name = "NoTorrent"
    override val hasMainPage = true 
    override var lang = "en"
    override val supportedTypes = setOf(TvType.Movie)

    // API Configurations
    private val tmdbBaseUrl = "https://api.tmdb.org/3"
    private val xyraApiKey = "freekey"
    private val wyzieApiKey = "wyzie-qadj2lucrwvfqglskqdy67jy7zkaptgo"

    // Master Key List for Failover and Rotation
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

    // --- Core Safe TMDB Fetcher ---
    private suspend fun fetchTmdb(path: String): JSONObject? {
        val keys = tmdbApiKeys.shuffled().take(3)
        for (key in keys) {
            try {
                val url = "$tmdbBaseUrl/$path".replace("{API_KEY}", key)
                val res = app.get(url, timeout = 5)
                if (res.isSuccessful) {
                    return JSONObject(res.text)
                }
            } catch (e: Exception) { continue }
        }
        return null
    }

    override val mainPage = mainPageOf(
        "trending/movie/week?api_key={API_KEY}" to "Trending Movies",
        "movie/popular?api_key={API_KEY}" to "Popular Movies",
        "movie/top_rated?api_key={API_KEY}" to "Top Rated Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val url = request.data + "&page=$page"
        val json = fetchTmdb(url) ?: return null
        
        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()
        
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val id = item.optInt("id", 0)
            val title = item.optString("title", "")
            val posterPath = item.optString("poster_path", "")
            
            if (id == 0 || title.isEmpty() || posterPath.isEmpty()) continue
            
            items.add(
                newMovieSearchResponse(title, id.toString(), TvType.Movie) {
                    this.posterUrl = "https://image.tmdb.org/t/p/w500$posterPath"
                }
            )
        }
        
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "search/movie?api_key={API_KEY}&query=$encodedQuery"
        val json = fetchTmdb(url) ?: return emptyList()
        
        val results = json.optJSONArray("results") ?: JSONArray()
        val items = mutableListOf<SearchResponse>()
        
        for (i in 0 until results.length()) {
            val item = results.getJSONObject(i)
            val id = item.optInt("id", 0)
            val title = item.optString("title", "")
            val posterPath = item.optString("poster_path", "")
            
            if (id == 0 || title.isEmpty() || posterPath.isEmpty()) continue
            
            items.add(
                newMovieSearchResponse(title, id.toString(), TvType.Movie) {
                    this.posterUrl = "https://image.tmdb.org/t/p/w500$posterPath"
                }
            )
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse? {
        val tmdbId = url
        val detailsUrl = "movie/$tmdbId?api_key={API_KEY}"
        val json = fetchTmdb(detailsUrl) ?: return null
        
        val title = json.optString("title", "")
        val overview = json.optString("overview", "")
        val posterPath = json.optString("poster_path", "")
        val backdropPath = json.optString("backdrop_path", "")
        val releaseDate = json.optString("release_date", "")
        
        val year = if (releaseDate.isNotBlank()) releaseDate.split("-")[0].toIntOrNull() else null
        val poster = if (posterPath.isNotBlank()) "https://image.tmdb.org/t/p/w500$posterPath" else null
        val backdrop = if (backdropPath.isNotBlank()) "https://image.tmdb.org/t/p/original$backdropPath" else null
        
        return newMovieLoadResponse(title, tmdbId, TvType.Movie, tmdbId) {
            this.plot = overview
            this.posterUrl = poster
            this.backgroundPosterUrl = backdrop
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val tmdbId = data

        return coroutineScope {
            
            // --- ISOLATED SUBTITLE FETCHING ---
            val subJob = async {
                try {
                    val wyzieUrl = "https://sub.wyzie.io/search?id=$tmdbId&source=all&key=$wyzieApiKey"
                    val wyzieResponse = app.get(wyzieUrl, timeout = 5).text
                    
                    val array = if (wyzieResponse.trim().startsWith("{")) {
                        JSONObject(wyzieResponse).optJSONArray("subtitles") ?: JSONArray()
                    } else {
                        JSONArray(wyzieResponse)
                    }

                    for (i in 0 until array.length()) {
                        val sub = array.getJSONObject(i)
                        val subUrl = sub.optString("url", "")
                        val lang = sub.optString("display", "").takeIf { it.isNotBlank() } ?: sub.optString("language", "English")
                        
                        // FIX: Uses raw SubtitleFile constructor to bypass deprecation wrapper bugs
                        if (subUrl.isNotBlank()) {
                            subtitleCallback.invoke(SubtitleFile(lang, subUrl))
                        }
                    }
                } catch (e: Exception) {}
            }

            // --- ISOLATED STREAM FETCHING (XYRA API) ---
            val streamJob = async {
                try {
                    val xyraUrl = "$mainUrl/movie/$tmdbId?api_key=$xyraApiKey"
                    val xyraResponse = app.get(xyraUrl, timeout = 5).text
                    val json = JSONObject(xyraResponse)
                    
                    if (json.optBoolean("success", false)) {
                        val streams = json.optJSONArray("streams") ?: JSONArray()
                        var found = false
                        for (i in 0 until streams.length()) {
                            val stream = streams.getJSONObject(i)
                            val name = stream.optString("name", "Xyra")
                            val streamUrl = stream.optString("url", "")
                            
                            if (streamUrl.isNotBlank()) {
                                val isM3u8 = streamUrl.contains(".m3u8")
                                
                                // FIX: Uses pure ExtractorLink class to bypass lambda mismatch crashes
                                callback.invoke(ExtractorLink(
                                    source = "NoTorrent",
                                    name = "NoTorrent ($name)",
                                    url = streamUrl,
                                    referer = mainUrl,
                                    quality = Qualities.P1080.value,
                                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ))
                                found = true
                            }
                        }
                        found
                    } else false
                } catch (e: Exception) { false }
            }

            val result = streamJob.await()
            subJob.await()
            
            result
        }
    }
}
