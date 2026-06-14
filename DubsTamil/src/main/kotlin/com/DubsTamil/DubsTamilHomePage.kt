package com.dubstamil

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.withPermit
import java.net.URLEncoder

// By making this an extension on MainAPI, any provider can call it!
suspend fun MainAPI.getSharedHomePageData(page: Int, request: MainPageRequest): HomePageResponse? {
    val homePageLists = mutableListOf<HomePageList>()
    
    try {
        val yearlyDoc = scrapeSemaphore.withPermit { app.get("$mainUrl/tamil-yearly-dubbed-movies/", timeout = 15).document }
        var latestYearUrl = ""
        var latestYear = ""
        
        for (a in yearlyDoc.select("a[href]")) {
            val href = a.attr("href")
            if (href.contains(Regex("tamil-\\d{4}-dubbed-movies"))) {
                latestYearUrl = if (href.startsWith("http")) href else "$mainUrl$href"
                latestYear = Regex("\\d{4}").find(href)?.value ?: ""
                break
            }
        }

        coroutineScope {
            val newMoviesDeferred = async { if (latestYearUrl.isNotEmpty()) fetchSectionItems(latestYearUrl, latestYear) else emptyList() }
            val actionDeferred = async { fetchSectionItems("$mainUrl/tamil-action-dubbed-movies/") }
            val comedyDeferred = async { fetchSectionItems("$mainUrl/tamil-comedy-dubbed-movies/") }
            val horrorDeferred = async { fetchSectionItems("$mainUrl/tamil-horror-dubbed-movies/") }
            val familyDeferred = async { fetchSectionItems("$mainUrl/tamil-family-dubbed-movies/") }

            val newMoviesList = newMoviesDeferred.await()
            if (newMoviesList.isNotEmpty()) {
                homePageLists.add(HomePageList("New Tamil Dubbed Movies", newMoviesList, isHorizontalImages = false))
            }
            
            val sections = listOf(
                Pair("Tamil Dubbed Action Movies", actionDeferred.await()),
                Pair("Tamil Dubbed Comedy Movies", comedyDeferred.await()),
                Pair("Tamil Dubbed Horror Movies", horrorDeferred.await()),
                Pair("Tamil Dubbed Family Movies", familyDeferred.await())
            )

            for ((title, listData) in sections) {
                if (listData.isNotEmpty()) {
                    homePageLists.add(HomePageList(title, listData, isHorizontalImages = false))
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    return newHomePageResponse(homePageLists, hasNext = false)
}

internal suspend fun MainAPI.fetchSectionItems(targetBaseUrl: String, sectionYear: String = ""): List<SearchResponse> {
    val listItems = mutableListOf<SearchResponse>()
    var currentPage = 1
    val maxPagesToScrape = 3

    while (listItems.size < 6 && currentPage <= maxPagesToScrape) {
        val targetUrl = if (currentPage == 1) targetBaseUrl else "$targetBaseUrl?get-page=$currentPage"
        try {
            val doc = scrapeSemaphore.withPermit { app.get(targetUrl, timeout = 15).document }
            val validMovieLinks = mutableListOf<Pair<String, String>>()
            
            for (a in doc.select("div.f a")) {
                val title = a.text().trim()
                var link = a.attr("href")
                if (link.startsWith("/")) link = "$mainUrl$link"
                
                val lowerTitle = title.lowercase()
                val lowerLink = link.lowercase()
                
                if (lowerTitle.contains("web series") || lowerLink.contains("web-series") ||
                    lowerTitle.contains("season") || lowerTitle.contains("episode")) continue
                
                validMovieLinks.add(Pair(title, link))
            }

            if (validMovieLinks.isEmpty()) break

            val responses = coroutineScope {
                validMovieLinks.map { (title, link) ->
                    async {
                        val cleanTitle = title.replace("isaiDub.me", "").replace("-", " ").trim()
                        val (omdbMatch, resolvedYear) = fetchOmdbMetadata(cleanTitle, sectionYear)
                        
                        val omdbPoster = omdbMatch?.Poster?.takeIf { it != "N/A" }
                        val plotSynopsis = omdbMatch?.Plot?.takeIf { it != "N/A" } ?: "No synopsis available."
                        
                        if (omdbPoster == null) null else {
                            val t = URLEncoder.encode(cleanTitle, "UTF-8")
                            val y = URLEncoder.encode(resolvedYear, "UTF-8")
                            val p = URLEncoder.encode(omdbPoster, "UTF-8")
                            val u = URLEncoder.encode(link, "UTF-8")
                            val s = URLEncoder.encode(plotSynopsis, "UTF-8")
                            
                            // Creates a synthetic URL tied to the provider that made the call
                            val targetData = "$mainUrl/synthetic_meta?t=$t&y=$y&p=$p&url=$u&s=$s"

                            newMovieSearchResponse(cleanTitle, targetData) {
                                this.posterUrl = omdbPoster
                                this.year = resolvedYear.toIntOrNull()
                            }
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            for (res in responses) {
                if (listItems.size < 6 && listItems.none { it.name == res.name }) {
                    listItems.add(res)
                }
            }

            val totalPagesSpan = doc.selectFirst("span#totalPages")
            val maxPageStr = totalPagesSpan?.text()?.trim()?.toIntOrNull()
            if (maxPageStr != null && currentPage >= maxPageStr) break
            
        } catch (e: Exception) { break }
        currentPage++
    }
    return listItems
}
