package com.megastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
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

    // --- PHASE 0: HOMEPAGE (Pure OMDb Generation to bypass ISP blocks) ---
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val lists = mutableListOf<HomePageList>()

        val queries = listOf(
            Pair("Trending Action 2024", "Action"),
            Pair("Popular Sci-Fi", "Sci-Fi"),
            Pair("Comedy Hits", "Comedy"),
            Pair("Latest Thrillers", "Thriller")
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
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    // --- PHASE 1: SEARCH (100% OMDb Powered) ---
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

    // --- PHASE 2: LOAD METADATA AND GENERATE EMBED LINKS ---
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

        // Generate massive list of anti-block hoster aggregators
        val embedUrls = listOf(
            "https://autoembed.co/movie/imdb/$imdbId",
            "https://multiembed.mov/directstream.php?video_id=$imdbId",
            "https://vidsrc.net/embed/movie/$imdbId",
            "https://vidsrc.me/embed/movie/$imdbId",
            "https://vidsrc.cc/v2/embed/movie/$imdbId",
            "https://2embed.cc/embed/$imdbId",
            "https://moviesapi.club/movie/$imdbId"
        )

        val dataPayload = embedUrls.joinToString(",")

        return newMovieLoadResponse(resolvedTitle, url, TvType.Movie, dataPayload) {
            this.posterUrl = resolvedPoster
            this.plot = resolvedPlot
            this.year = resolvedYear
        }
    }

    // --- PHASE 3: DEEP EXTRACTION ENGINE ---
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val urls = data.split(",")
        var foundAny = false

        coroutineScope {
            urls.forEach { embedUrl ->
                launch {
                    try {
                        // 1. If it's ALREADY a direct hoster URL, let Cloudstream process it instantly
                        if (loadExtractor(embedUrl, embedUrl, subtitleCallback, callback)) {
                            foundAny = true
                            return@launch
                        }

                        // 2. If not, it's an Aggregator. Download the page and rip the iframes.
                        val html = app.get(embedUrl, timeout = 10).text
                        val doc = Jsoup.parse(html)
                        
                        doc.select("iframe").forEach { iframe ->
                            val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
                            
                            if (src.isNotBlank() && src.startsWith("http")) {
                                // Feed the hidden iframes (Voe, Filemoon, Dood, etc.) back into the engine
                                if (loadExtractor(src, embedUrl, subtitleCallback, callback)) {
                                    foundAny = true
                                }
                            }
                        }

                        // 3. Bruteforce Fallback: Scan the raw HTML for hidden HLS (.m3u8) links
                        Regex("""(https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*)""").findAll(html).forEach { match ->
                            val hlsUrl = match.groupValues[1]
                            callback.invoke(
                                newExtractorLink(
                                    source = "MegaStream",
                                    name = "Auto HLS",
                                    url = hlsUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = embedUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            foundAny = true
                        }

                        // 4. Bruteforce Fallback: Scan the raw HTML for hidden MP4 links
                        Regex("""(https?://[^"'\s<>]+?\.mp4[^"'\s<>]*)""").findAll(html).forEach { match ->
                            val mp4Url = match.groupValues[1]
                            callback.invoke(
                                newExtractorLink(
                                    source = "MegaStream",
                                    name = "Auto MP4",
                                    url = mp4Url,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = embedUrl
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                            foundAny = true
                        }

                    } catch (e: Exception) {
                        // Silently skip if a specific provider like Vidsrc is blocked by the ISP
                    }
                }
            }
        }

        return foundAny
    }
}
