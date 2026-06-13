package com.megastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper

class MegaStreamProvider : MainAPI() {
    override var mainUrl = "https://www.omdbapi.com"
    override var name = "MegaStream"
    override val hasMainPage = true 
    override var supportedTypes = setOf(TvType.Movie)
    override var lang = "en"

    private val mapper = jacksonObjectMapper()

    private val omdbKeys = listOf(
        "4b447405", "eb0c0475", "7776cbde", "ff28f90b",
        "6c3a2d45", "b07b58c8", "ad04b643", "a95b5205",
        "777d9323", "2c2c3314", "b5cff164", "89a9f57d",
        "73a9858a", "efbd8357"
    )

    // Safe OMDb Models
    data class OmdbSearchResponse(
        @JsonProperty("Search") val Search: List<OmdbSearchResult>? = null, 
        @JsonProperty("Response") val Response: String? = null
    )
    
    data class OmdbSearchResult(
        @JsonProperty("Title") val Title: String? = null, 
        @JsonProperty("Year") val Year: String? = null, 
        @JsonProperty("imdbID") val imdbID: String? = null, 
        @JsonProperty("Type") val Type: String? = null, 
        @JsonProperty("Poster") val Poster: String? = null
    )
    
    data class OmdbTitleResponse(
        @JsonProperty("Title") val Title: String? = null, 
        @JsonProperty("Year") val Year: String? = null, 
        @JsonProperty("Plot") val Plot: String? = null, 
        @JsonProperty("Poster") val Poster: String? = null, 
        @JsonProperty("imdbID") val imdbID: String? = null
    )

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
                        val parsed = mapper.readValue(res, OmdbSearchResponse::class.java)
                        
                        val items = parsed.Search?.filter { it.Poster != "N/A" }?.mapNotNull { item ->
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
            val parsed = mapper.readValue(res, OmdbSearchResponse::class.java)
            
            parsed.Search?.filter { it.Poster != "N/A" }?.mapNotNull { item ->
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
        
        val metaRes = try {
            mapper.readValue(app.get(metaUrl).text, OmdbTitleResponse::class.java)
        } catch (e: Exception) {
            return null
        }
        
        val resolvedTitle = metaRes.Title ?: "Unknown"
        val resolvedPoster = metaRes.Poster?.takeIf { it != "N/A" } ?: ""
        val resolvedPlot = metaRes.Plot?.takeIf { it != "N/A" } ?: ""
        val resolvedYear = metaRes.Year?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        return newMovieLoadResponse(resolvedTitle, url, TvType.Movie, imdbId) {
            this.posterUrl = resolvedPoster
            this.plot = resolvedPlot
            this.year = resolvedYear
        }
    }

    // --- PHASE 3: DISTRIBUTED CUSTOM EXTRACTOR ENGINE ---
    // Restored directly from your ApiConstants.kt and StreamPlay logic!
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val imdbId = data // Directly passes 'ttXXXXXXX'
        var foundAny = false

        coroutineScope {
            val extractors = listOf(
                async { if (invokeVidLink(imdbId, subtitleCallback, callback)) foundAny = true },
                async { if (invokePrimeSrc(imdbId, subtitleCallback, callback)) foundAny = true },
                async { if (invokeMultiEmbed(imdbId, subtitleCallback, callback)) foundAny = true },
                async { if (invokeVideasy(imdbId, subtitleCallback, callback)) foundAny = true },
                async { if (invokeDahmer(imdbId, subtitleCallback, callback)) foundAny = true },
                async { if (invokeHexa(imdbId, subtitleCallback, callback)) foundAny = true }
            )
            extractors.awaitAll()
        }

        return foundAny
    }

    // 1. VidLink API
    private suspend fun invokeVidLink(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = "https://vidlink.pro/movie/$imdbId"
            val res = app.get(url, timeout = 10).text
            var found = false
            
            Regex("""source\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").findAll(res).forEach { match ->
                callback.invoke(
                    newExtractorLink(
                        source = "MegaStream",
                        name = "VidLink API",
                        url = match.groupValues[1],
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Referer" to url)
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }

            Jsoup.parse(res).select("iframe").forEach { iframe ->
                val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
                if (src.startsWith("http")) {
                    if (loadExtractor(src, url, subtitleCallback, callback)) found = true
                }
            }
            found
        } catch (e: Exception) { false }
    }

    // 2. PrimeSrc API
    private suspend fun invokePrimeSrc(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = "https://primesrc.me/embed/movie?imdb=$imdbId"
            val doc = app.get(url, timeout = 10).document
            var found = false

            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
                if (src.startsWith("http")) {
                    if (loadExtractor(src, url, subtitleCallback, callback)) found = true
                }
            }
            found
        } catch (e: Exception) { false }
    }

    // 3. MultiEmbed API
    private suspend fun invokeMultiEmbed(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = "https://multiembed.mov/directstream.php?video_id=$imdbId"
            val res = app.get(url, timeout = 10).text
            var found = false
            
            Regex("""(https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*)""").findAll(res).forEach { match ->
                callback.invoke(
                    newExtractorLink(
                        source = "MegaStream",
                        name = "MultiEmbed Direct",
                        url = match.groupValues[1].replace("\\/", "/"),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Referer" to url)
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }

            Jsoup.parse(res).select("iframe").forEach { iframe ->
                val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
                if (src.startsWith("http")) {
                    if (loadExtractor(src, url, subtitleCallback, callback)) found = true
                }
            }
            found
        } catch (e: Exception) { false }
    }

    // 4. Videasy API
    private suspend fun invokeVideasy(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = "https://api.videasy.to/embed/movie/$imdbId"
            val doc = app.get(url, timeout = 10).document
            var found = false

            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
                if (src.startsWith("http")) {
                    if (loadExtractor(src, url, subtitleCallback, callback)) found = true
                }
            }
            found
        } catch (e: Exception) { false }
    }

    // 5. Dahmer Movies (From your ApiConstants.kt)
    private suspend fun invokeDahmer(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = "https://a.111477.xyz/movie/$imdbId"
            val res = app.get(url, timeout = 10).text
            var found = false
            
            Regex("""(https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*)""").findAll(res).forEach { match ->
                callback.invoke(
                    newExtractorLink(
                        source = "MegaStream",
                        name = "Dahmer API",
                        url = match.groupValues[1],
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Referer" to url)
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }

            Jsoup.parse(res).select("iframe").forEach { iframe ->
                val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
                if (src.startsWith("http")) {
                    if (loadExtractor(src, url, subtitleCallback, callback)) found = true
                }
            }
            found
        } catch (e: Exception) { false }
    }

    // 6. Hexa API (From your ApiConstants.kt)
    private suspend fun invokeHexa(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val url = "https://theemoviedb.hexa.su/movie/$imdbId"
            val doc = app.get(url, timeout = 10).document
            var found = false

            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
                if (src.startsWith("http")) {
                    if (loadExtractor(src, url, subtitleCallback, callback)) found = true
                }
            }
            found
        } catch (e: Exception) { false }
    }
}
