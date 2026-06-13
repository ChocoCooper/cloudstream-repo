package com.megastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import java.net.URI
import java.net.URLEncoder

class MegaStreamProvider : MainAPI() {
    override var mainUrl = "https://www.omdbapi.com"
    override var name = "MegaStream"
    override val hasMainPage = true 
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "en"

    private val omdbKeys = listOf(
        "4b447405", "eb0c0475", "7776cbde", "ff28f90b",
        "6c3a2d45", "b07b58c8", "ad04b643", "a95b5205",
        "777d9323", "2c2c3314", "b5cff164", "89a9f57d",
        "73a9858a", "efbd8357"
    )

    // OMDb JSON Data Models
    data class OmdbSearchResponse(val Search: List<OmdbSearchResult>?, val Response: String?)
    data class OmdbSearchResult(val Title: String?, val Year: String?, val imdbID: String?, val Type: String?, val Poster: String?)
    data class OmdbTitleResponse(val Title: String?, val Year: String?, val Plot: String?, val Poster: String?, val imdbID: String?)

    private fun getRandomApiKey(): String {
        return omdbKeys.random()
    }

    // --- PHASE 0: HOMEPAGE ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        val queries = listOf(
            Pair("Trending Action 2024", "Action"),
            Pair("Sci-Fi Thrills", "Sci-Fi"),
            Pair("Comedy Hits", "Comedy"),
            Pair("Latest Horror", "Horror")
        )

        coroutineScope {
            queries.forEach { (title, query) ->
                launch {
                    try {
                        val apiKey = getRandomApiKey()
                        val encodedQuery = URLEncoder.encode(query, "UTF-8")
                        val url = "$mainUrl/?apikey=$apiKey&s=$encodedQuery&type=movie&y=2024"
                        
                        val res = app.get(url, timeout = 5).text
                        val parsed = AppUtils.tryParseJson<OmdbSearchResponse>(res)
                        
                        val items = parsed?.Search?.filter { it.Poster != "N/A" }?.mapNotNull { item ->
                            val imdbId = item.imdbID ?: return@mapNotNull null
                            val payload = "$mainUrl/megastream_omdb?id=$imdbId"
                            
                            newMovieSearchResponse(item.Title ?: "Unknown", payload, TvType.Movie) {
                                this.posterUrl = item.Poster
                                this.year = item.Year?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                            }
                        } ?: emptyList()

                        if (items.isNotEmpty()) {
                            lists.add(HomePageList(title, items, isHorizontalImages = false))
                        }
                    } catch (e: Exception) { }
                }
            }
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    // --- PHASE 1: SEARCH ---
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val apiKey = getRandomApiKey()
        val url = "$mainUrl/?apikey=$apiKey&s=$encodedQuery&type=movie"
        
        return try {
            val res = app.get(url, timeout = 5).text
            val parsed = AppUtils.tryParseJson<OmdbSearchResponse>(res)
            
            parsed?.Search?.filter { it.Poster != "N/A" }?.mapNotNull { item ->
                val title = item.Title ?: return@mapNotNull null
                val imdbId = item.imdbID ?: return@mapNotNull null
                val payload = "$mainUrl/megastream_omdb?id=$imdbId"
                
                newMovieSearchResponse(title, payload, TvType.Movie) {
                    this.posterUrl = item.Poster
                    this.year = item.Year?.replace(Regex("[^0-9]"), "")?.toIntOrNull()
                }
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    // --- PHASE 2: LOAD METADATA ---
    override suspend fun load(url: String): LoadResponse? {
        val imdbId = if (url.contains("/megastream_omdb")) {
            val uri = URI(url)
            val queryParams = uri.query?.split("&")?.associate {
                val parts = it.split("=")
                parts[0] to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
            }
            queryParams?.get("id") ?: return null
        } else {
            Regex("""(tt\d+)""").find(url)?.groupValues?.get(1) ?: return null
        }
        
        val apiKey = getRandomApiKey()
        val metaUrl = "$mainUrl/?apikey=$apiKey&i=$imdbId&plot=full"
        
        val metaRes = AppUtils.tryParseJson<OmdbTitleResponse>(app.get(metaUrl).text) ?: return null
        
        val resolvedTitle = metaRes.Title ?: "Unknown"
        val resolvedPoster = metaRes.Poster?.takeIf { it != "N/A" } ?: ""
        val resolvedPlot = metaRes.Plot?.takeIf { it != "N/A" } ?: ""
        val resolvedYear = metaRes.Year?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        // Pack the raw IMDb ID so the custom extractors can use it directly
        return newMovieLoadResponse(resolvedTitle, url, TvType.Movie, imdbId) {
            this.posterUrl = resolvedPoster
            this.plot = resolvedPlot
            this.year = resolvedYear
        }
    }

    // --- PHASE 3: DIRECT EXTRACTOR ENGINE ---
    // Bypasses Cloudstream's native extractors entirely. We scrape the .m3u8 natively here.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val imdbId = data // Data payload is the raw ttXXXXXXX ID
        var foundAny = false

        coroutineScope {
            launch { if (invokeMultiEmbed(imdbId, callback)) foundAny = true }
            launch { if (invokeVidLink(imdbId, callback)) foundAny = true }
            launch { if (invokeSuperEmbed(imdbId, callback)) foundAny = true }
            launch { if (invokeVidSrcNet(imdbId, callback)) foundAny = true }
            launch { if (invokeDahmer(imdbId, callback)) foundAny = true }
        }

        return foundAny
    }

    // 1. MultiEmbed Direct API (Highly Reliable)
    private suspend fun invokeMultiEmbed(imdbId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = "https://multiembed.mov/directstream.php?video_id=$imdbId"
            val res = app.get(url, timeout = 10).text
            var found = false
            
            Regex("""(https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*)""").findAll(res).forEach { match ->
                val streamUrl = match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = "MegaStream",
                        name = "MultiEmbed Server",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://multiembed.mov/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
            found
        } catch (e: Exception) { false }
    }

    // 2. VidLink Direct Extractor
    private suspend fun invokeVidLink(imdbId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = "https://vidlink.pro/movie/$imdbId"
            val res = app.get(url, timeout = 10).text
            
            val streamUrl = Regex("""source\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(res)?.groupValues?.get(1)
            
            if (streamUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "MegaStream",
                        name = "VidLink Server",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                true
            } else false
        } catch (e: Exception) { false }
    }

    // 3. SuperEmbed Direct API
    private suspend fun invokeSuperEmbed(imdbId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = "https://superembed.stream/movie/$imdbId"
            val res = app.get(url, timeout = 10).text
            
            val streamUrl = Regex("""play_url"\s*:\s*"([^"]+)"""").find(res)?.groupValues?.get(1)?.replace("\\/", "/")
            
            if (streamUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "MegaStream",
                        name = "SuperEmbed Server",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                true
            } else false
        } catch (e: Exception) { false }
    }

    // 4. VidSrc.net Deep Decryption (Replaces the dead rgshows.ru proxy)
    private suspend fun invokeVidSrcNet(imdbId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = "https://vidsrc.net/embed/movie/$imdbId"
            val req = app.get(url, headers = mapOf("Referer" to url)).document
            val rcpSource = req.selectFirst("iframe#player_iframe")?.attr("src") ?: return false
            
            val rcpUrl = if (rcpSource.startsWith("//")) "https:$rcpSource" else rcpSource
            val rcpDoc = app.get(rcpUrl, headers = mapOf("Referer" to url)).text
            
            val hashMatch = Regex("""hash:\s*'([^']+)'""").find(rcpDoc)?.groupValues?.get(1) ?: return false
            val apiRes = app.get("https://vidsrc.net/api/source/$hashMatch").text
            
            val streamUrl = Regex("""file":"([^"]+)"""").find(apiRes)?.groupValues?.get(1)?.replace("\\/", "/")
            
            if (streamUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source = "MegaStream",
                        name = "VidSrc Server",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                true
            } else false
        } catch (e: Exception) { false }
    }

    // 5. Dahmer Direct Extractor (From your ApiConstants.kt)
    private suspend fun invokeDahmer(imdbId: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = "https://a.111477.xyz/movie/$imdbId"
            val res = app.get(url, timeout = 10).text
            var found = false
            
            Regex("""(https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*)""").findAll(res).forEach { match ->
                val streamUrl = match.groupValues[1]
                callback.invoke(
                    newExtractorLink(
                        source = "MegaStream",
                        name = "Dahmer Server",
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
            found
        } catch (e: Exception) { false }
    }
}
