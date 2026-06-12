package com.isaidub

import com.lagradost.cloudstream3.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.net.URLEncoder

object KuttyMoviesSource {
    private data class ResolutionNode(val label: String, val url: String)

    suspend fun getHomePages(provider: IsaidubProvider): List<HomePageList> {
        val lists = mutableListOf<HomePageList>()
        try {
            coroutineScope {
                listOf(2026, 2025).map { year ->
                    async { Pair(year, fetchSection(provider, "${provider.kuttyUrl}/$year-tamil-dubbed-movies.html", year.toString())) }
                }.awaitAll().forEach { (year, listData) ->
                    if (listData.isNotEmpty()) lists.add(HomePageList("KuttyMovies - $year Collection", listData, false))
                }
            }
        } catch (e: Exception) { }
        return lists
    }

    private suspend fun fetchSection(provider: IsaidubProvider, targetBaseUrl: String, sectionYear: String = ""): List<SearchResponse> {
        val listItems = mutableListOf<SearchResponse>()
        var currentPage = 1
        while (listItems.size < 6 && currentPage <= 3) {
            val targetUrl = if (currentPage == 1) targetBaseUrl else "$targetBaseUrl?page=$currentPage"
            try {
                val doc = provider.app.get(targetUrl, timeout = 15).document
                val validLinks = mutableListOf<Pair<String, String>>()
                for (a in doc.select("a[href*=\"/kuttymovies/\"]")) {
                    val title = a.text().trim(); val link = if (a.attr("href").startsWith("/")) "${provider.kuttyUrl}${a.attr("href")}" else a.attr("href")
                    if (title.lowercase().contains("page ") || title.lowercase().contains("home") || title.lowercase().contains("web series")) continue
                    validLinks.add(Pair(title, link))
                }
                if (validLinks.isEmpty()) break

                val responses = coroutineScope {
                    validLinks.map { (title, link) ->
                        async {
                            val cleanTitle = title.replace("KuttyMovies", "", true).replace("-", " ").trim()
                            val (omdb, resYear) = provider.fetchOmdbMetadata(cleanTitle, sectionYear)
                            val poster = omdb?.Poster?.takeIf { it != "N/A" }
                            val overview = omdb?.Plot?.takeIf { it != "N/A" } ?: ""
                            
                            if (poster == null) null else {
                                val t = URLEncoder.encode(cleanTitle, "UTF-8"); val y = URLEncoder.encode(resYear, "UTF-8")
                                val p = URLEncoder.encode(poster, "UTF-8"); val u = URLEncoder.encode(link, "UTF-8")
                                val s = URLEncoder.encode(overview, "UTF-8")
                                val data = "${provider.mainUrl}/synthetic_meta?t=$t&y=$y&p=$p&url=$u&s=$s"
                                provider.newMovieSearchResponse(cleanTitle, data) { this.posterUrl = poster; this.year = resYear.toIntOrNull() }
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                responses.forEach { if (listItems.size < 6 && listItems.none { existing -> existing.name == it.name }) listItems.add(it) }
                if (!doc.text().contains("Page 2") && currentPage > 1) break
            } catch (e: Exception) { break }
            currentPage++
        }
        return listItems
    }

    suspend fun searchMovieLinks(provider: IsaidubProvider, title: String, year: String): List<String> {
        val targets = mutableListOf<String>()
        year.toIntOrNull()?.let { targets.add("${provider.kuttyUrl}/$it-tamil-dubbed-movies.html"); targets.add("${provider.kuttyUrl}/${it - 1}-tamil-dubbed-movies.html") }
        
        val matched = mutableListOf<IsaidubProvider.ScrapedMovie>()
        for (url in targets.distinct()) {
            if (matched.isNotEmpty()) break
            val (movies, max) = scrapePageAndGetTotal(provider, url)
            val hits = provider.searchByTokenAndYear(movies, title, year)
            if (hits.isNotEmpty()) { matched.addAll(hits); break }
            
            if (max > 1) {
                coroutineScope {
                    (2..minOf(max, 6)).map { p -> 
                        async { scrapePageAndGetTotal(provider, "$url?page=$p").first }
                    }.awaitAll().forEach { pMovies ->
                        val pHits = provider.searchByTokenAndYear(pMovies, title, year)
                        if (pHits.isNotEmpty()) matched.addAll(pHits)
                    }
                }
            }
        }
        return matched.map { it.link }.distinct()
    }

    private suspend fun scrapePageAndGetTotal(provider: IsaidubProvider, url: String): Pair<List<IsaidubProvider.ScrapedMovie>, Int> {
        val movies = mutableListOf<IsaidubProvider.ScrapedMovie>()
        var maxPage = 1
        try {
            val doc = provider.app.get(url, timeout = 10).document
            for (a in doc.select("a[href*=\"/kuttymovies/\"]")) {
                val t = a.text().trim()
                if (!t.lowercase().contains("page ") && !t.lowercase().contains("home")) movies.add(IsaidubProvider.ScrapedMovie(t, if(a.attr("href").startsWith("/")) "${provider.kuttyUrl}${a.attr("href")}" else a.attr("href")))
            }
            doc.select("a[href]").forEach { a ->
                Regex("""\?page=(\d+)""").find(a.attr("href"))?.groupValues?.get(1)?.toIntOrNull()?.let { if (it > maxPage && it <= 15) maxPage = it }
            }
        } catch (e: Exception) { }
        return Pair(movies, maxPage)
    }

    suspend fun extractLinks(provider: IsaidubProvider, pageUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        getResolutions(provider, pageUrl).forEach { res ->
            extractFinalLink(provider, res.url, 0, mutableSetOf())?.let { finalUrl ->
                val label = res.label.lowercase()
                val q = when { label.contains("1080") -> "(1080p)"; label.contains("720") -> "(720p)"; label.contains("640") || label.contains("360") -> "(640x360)"; label.contains("480") || label.contains("320") -> "(480x320)"; else -> "(HD)" }
                callback.invoke(provider.newExtractorLink("KuttyMovies", "KuttyMovies $q", finalUrl, finalUrl.contains(".m3u8")) { this.referer = "${provider.kuttyUrl}/" })
                found = true
            }
        }
        return found
    }

    private suspend fun getResolutions(provider: IsaidubProvider, pageUrl: String, depth: Int = 0): List<ResolutionNode> {
        if (depth > 4) return emptyList()
        val found = mutableListOf<ResolutionNode>(); val folders = mutableListOf<String>()
        try {
            for (a in provider.app.get(pageUrl, timeout = 10).document.select("a[href]")) {
                val href = a.attr("href"); val txt = a.text().trim().lowercase()
                if (txt.contains("sample") || href.contains("sample", true) || href.startsWith("#") || href == pageUrl) continue
                val fullUrl = if (href.startsWith("http")) href else "${provider.kuttyUrl}$href"
                if (fullUrl.contains("/movies/download/")) found.add(ResolutionNode(a.text(), fullUrl))
                else if (fullUrl.contains("/kuttymovies/") && !fullUrl.endsWith("index.html") && listOf("rip", "hd", "hq", "dvd", "720p", "1080p", "640x", "part").any { txt.contains(it) }) folders.add(fullUrl)
            }
            if (found.isEmpty()) folders.forEach { found.addAll(getResolutions(provider, it, depth + 1)) }
        } catch (e: Exception) { }
        return found
    }

    private suspend fun extractFinalLink(provider: IsaidubProvider, url: String, depth: Int, seen: MutableSet<String>): String? {
        if (depth > 6 || !seen.add(url)) return null
        try {
            val res = provider.app.get(url, timeout = 10)
            if (res.headers["content-type"]?.contains("video/") == true || res.headers["content-type"]?.contains("octet-stream") == true) return res.url
            val txt = res.text
            Regex("""https?://[^\s"'<>]*dl\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(txt)?.value?.let { return it }
            Regex("""https?://[^\s"'<>]*\.mp4[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(txt)?.value?.let { return it }
            val id = url.substringAfterLast("/").substringBefore("?")
            for (a in Jsoup.parse(txt).select("a[href]")) {
                val href = a.attr("href")
                if (href.contains("sample", true)) continue
                if (href.contains(id) || href.contains("downloadkutty") || href.contains("dl.php")) {
                    extractFinalLink(provider, if (href.startsWith("http")) href else "${provider.kuttyUrl}$href", depth + 1, seen)?.let { return it }
                }
            }
        } catch (e: Exception) { }
        return null
    }
}
