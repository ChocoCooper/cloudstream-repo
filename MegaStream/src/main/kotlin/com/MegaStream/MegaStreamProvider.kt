package com.megastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.json.JSONObject
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

    // Helper to silently bypass ISP blocks / SocketTimeouts without crashing
    private suspend fun safe(block: suspend () -> Unit) {
        try { block() } catch (e: Exception) {}
    }

    // --- PHASE 0: HOMEPAGE (OMDb Native Parsing - No Jackson) ---
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
                    safe {
                        val apiKey = omdbKeys.random()
                        val encodedQuery = URLEncoder.encode(query, "UTF-8")
                        val url = "$mainUrl/?apikey=$apiKey&s=$encodedQuery&type=movie&y=2024"
                        
                        val res = app.get(url, timeout = 5).text
                        val json = JSONObject(res)
                        val searchArr = json.optJSONArray("Search")
                        
                        val items = mutableListOf<SearchResponse>()
                        if (searchArr != null) {
                            for (i in 0 until searchArr.length()) {
                                val item = searchArr.getJSONObject(i)
                                val poster = item.optString("Poster")
                                if (poster.isNotBlank() && poster != "N/A") {
                                    items.add(
                                        newMovieSearchResponse(
                                            item.optString("Title", "Unknown"),
                                            "omdb://${item.optString("imdbID")}",
                                            TvType.Movie
                                        ) {
                                            this.posterUrl = poster
                                            this.year = item.optString("Year").replace(Regex("[^0-9]"), "").toIntOrNull()
                                        }
                                    )
                                }
                            }
                        }

                        if (items.isNotEmpty()) {
                            lists.add(HomePageList(title, items, isHorizontalImages = false))
                        }
                    }
                }
            }
        }
        return newHomePageResponse(lists, hasNext = false)
    }

    // --- PHASE 1: SEARCH (OMDb Native Parsing) ---
    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val apiKey = omdbKeys.random()
        val url = "$mainUrl/?apikey=$apiKey&s=$encodedQuery&type=movie"
        
        val items = mutableListOf<SearchResponse>()
        safe {
            val res = app.get(url, timeout = 5).text
            val json = JSONObject(res)
            val searchArr = json.optJSONArray("Search")
            
            if (searchArr != null) {
                for (i in 0 until searchArr.length()) {
                    val item = searchArr.getJSONObject(i)
                    val poster = item.optString("Poster")
                    if (poster.isNotBlank() && poster != "N/A") {
                        items.add(
                            newMovieSearchResponse(
                                item.optString("Title", "Unknown"),
                                "omdb://${item.optString("imdbID")}",
                                TvType.Movie
                            ) {
                                this.posterUrl = poster
                                this.year = item.optString("Year").replace(Regex("[^0-9]"), "").toIntOrNull()
                            }
                        )
                    }
                }
            }
        }
        return items
    }

    // --- PHASE 2: LOAD METADATA ---
    override suspend fun load(url: String): LoadResponse? {
        val imdbId = url.removePrefix("omdb://")
        val apiKey = omdbKeys.random()
        val metaUrl = "$mainUrl/?apikey=$apiKey&i=$imdbId&plot=full"
        
        var response: LoadResponse? = null
        safe {
            val json = JSONObject(app.get(metaUrl, timeout = 5).text)
            val resolvedTitle = json.optString("Title", "Unknown")
            val resolvedPoster = json.optString("Poster", "").takeIf { it != "N/A" }
            val resolvedPlot = json.optString("Plot", "").takeIf { it != "N/A" }
            val resolvedYear = json.optString("Year", "").replace(Regex("[^0-9]"), "").toIntOrNull()

            response = newMovieLoadResponse(resolvedTitle, url, TvType.Movie, imdbId) {
                this.posterUrl = resolvedPoster
                this.plot = resolvedPlot
                this.year = resolvedYear
            }
        }
        return response
    }

    // --- PHASE 3: DISTRIBUTED CUSTOM EXTRACTOR ENGINE ---
    // Uses your uploaded anti-block proxies natively
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val imdbId = data
        var foundAny = false
        
        val cb: (ExtractorLink) -> Unit = {
            foundAny = true
            callback(it)
        }

        coroutineScope {
            // 1. Hexa Proxy
            launch { safe {
                val url = "https://theemoviedb.hexa.su/movie/$imdbId"
                val doc = app.get(url, timeout = 8).text
                Regex("""file\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").findAll(doc).forEach {
                    cb(newExtractorLink("MegaStream", "Hexa", it.groupValues[1], ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to url)
                    })
                }
                Jsoup.parse(doc).select("iframe").forEach {
                    val src = it.attr("src").let { s -> if(s.startsWith("//")) "https:$s" else s }
                    if(src.startsWith("http")) loadExtractor(src, url, subtitleCallback, cb)
                }
            }}
            
            // 2. Dahmer Movies Proxy
            launch { safe {
                val url = "https://a.111477.xyz/movie/$imdbId"
                val doc = app.get(url, timeout = 8).text
                Regex("""file\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").findAll(doc).forEach {
                    cb(newExtractorLink("MegaStream", "Dahmer", it.groupValues[1], ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to url)
                    })
                }
                Jsoup.parse(doc).select("iframe").forEach {
                    val src = it.attr("src").let { s -> if(s.startsWith("//")) "https:$s" else s }
                    if(src.startsWith("http")) loadExtractor(src, url, subtitleCallback, cb)
                }
            }}
            
            // 3. VidLink Proxy
            launch { safe {
                val url = "https://vidlink.pro/movie/$imdbId"
                val doc = app.get(url, timeout = 8).text
                Regex("""source\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").findAll(doc).forEach {
                    cb(newExtractorLink("MegaStream", "VidLink", it.groupValues[1], ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to url)
                    })
                }
                Jsoup.parse(doc).select("iframe").forEach {
                    val src = it.attr("src").let { s -> if(s.startsWith("//")) "https:$s" else s }
                    if(src.startsWith("http")) loadExtractor(src, url, subtitleCallback, cb)
                }
            }}
            
            // 4. MultiEmbed Mov
            launch { safe {
                val url = "https://multiembed.mov/directstream.php?video_id=$imdbId"
                val doc = app.get(url, timeout = 8).text
                Regex("""(https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*)""").findAll(doc).forEach {
                    cb(newExtractorLink("MegaStream", "MultiEmbed", it.groupValues[1].replace("\\/", "/"), ExtractorLinkType.M3U8) {
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Referer" to url)
                    })
                }
                Jsoup.parse(doc).select("iframe").forEach {
                    val src = it.attr("src").let { s -> if(s.startsWith("//")) "https:$s" else s }
                    if(src.startsWith("http")) loadExtractor(src, url, subtitleCallback, cb)
                }
            }}
            
            // 5. PrimeSrc Proxy
            launch { safe {
                val url = "https://primesrc.me/embed/movie?imdb=$imdbId"
                val doc = app.get(url, timeout = 8).document
                doc.select("iframe").forEach {
                    val src = it.attr("src").let { s -> if(s.startsWith("//")) "https:$s" else s }
                    if(src.startsWith("http")) loadExtractor(src, url, subtitleCallback, cb)
                }
            }}
            
            // 6. Videasy Proxy
            launch { safe {
                val url = "https://api.videasy.to/embed/movie/$imdbId"
                val doc = app.get(url, timeout = 8).document
                doc.select("iframe").forEach {
                    val src = it.attr("src").let { s -> if(s.startsWith("//")) "https:$s" else s }
                    if(src.startsWith("http")) loadExtractor(src, url, subtitleCallback, cb)
                }
            }}
            
            // 7. 2Embed Proxy
            launch { safe {
                val url = "https://2embed.cc/embed/$imdbId"
                val doc = app.get(url, timeout = 8).document
                doc.select("iframe").forEach {
                    val src = it.attr("src").let { s -> if(s.startsWith("//")) "https:$s" else s }
                    if(src.startsWith("http")) loadExtractor(src, url, subtitleCallback, cb)
                }
            }}
        }

        return foundAny
    }
}
