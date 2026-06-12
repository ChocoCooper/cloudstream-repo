package com.isaidub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLEncoder

object IsaidubSource {
    private data class ResolutionNode(val label: String, val url: String)

    suspend fun getHomePages(provider: IsaidubProvider): List<HomePageList> {
        val lists = mutableListOf<HomePageList>()
        try {
            val doc = app.get("${provider.mainUrl}/tamil-yearly-dubbed-movies/", timeout = 15).document
            var latestYearUrl = ""; var latestYear = ""
            for (a in doc.select("a[href]")) {
                if (a.attr("href").contains(Regex("tamil-\\d{4}-dubbed-movies"))) {
                    latestYearUrl = if (a.attr("href").startsWith("http")) a.attr("href") else "${provider.mainUrl}${a.attr("href")}"
                    latestYear = Regex("\\d{4}").find(a.attr("href"))?.value ?: ""
                    break
                }
            }

            coroutineScope {
                val newMovies = async { if (latestYearUrl.isNotEmpty()) fetchSection(provider, latestYearUrl, latestYear) else emptyList() }
                val action = async { fetchSection(provider, "${provider.mainUrl}/tamil-action-dubbed-movies/") }
                val horror = async { fetchSection(provider, "${provider.mainUrl}/tamil-horror-dubbed-movies/") }

                if (newMovies.await().isNotEmpty()) lists.add(HomePageList("Isaidub - New Releases", newMovies.await(), false))
                if (action.await().isNotEmpty()) lists.add(HomePageList("Isaidub - Action Dubbed", action.await(), false))
                if (horror.await().isNotEmpty()) lists.add(HomePageList("Isaidub - Horror Dubbed", horror.await(), false))
            }
        } catch (e: Exception) { }
        return lists
    }

    private suspend fun fetchSection(provider: IsaidubProvider, targetBaseUrl: String, sectionYear: String = ""): List<SearchResponse> {
        val listItems = mutableListOf<SearchResponse>()
        var currentPage = 1
        while (listItems.size < 6 && currentPage <= 3) {
            val targetUrl = if (currentPage == 1) targetBaseUrl else "$targetBaseUrl?get-page=$currentPage"
            try {
                val doc = app.get(targetUrl, timeout = 15).document
                val validLinks = mutableListOf<Pair<String, String>>()
                for (a in doc.select("div.f a")) {
                    val title = a.text().trim()
                    val link = if (a.attr("href").startsWith("/")) "${provider.mainUrl}${a.attr("href")}" else a.attr("href")
                    if (title.lowercase().contains("web series") || link.lowercase().contains("season")) continue
                    validLinks.add(Pair(title, link))
                }
                if (validLinks.isEmpty()) break

                val responses = coroutineScope {
                    validLinks.map { (title, link) ->
                        async {
                            val cleanTitle = title.replace("isaiDub.me", "").replace("-", "").trim()
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
                if (!doc.text().contains("get-page=") && currentPage > 1) break
            } catch (e: Exception) { break }
            currentPage++
        }
        return listItems
    }

    suspend fun searchMovieLinks(provider: IsaidubProvider, title: String, year: String): List<String> {
        val targets = mutableListOf<String>()
        year.toIntOrNull()?.let { targets.add("${provider.mainUrl}/tamil-$it-dubbed-movies/") }
        title.trim().firstOrNull()?.lowercaseChar()?.let {
            if (it.isLetter()) targets.add("${provider.mainUrl}/tamil-atoz-dubbed-movies/$it/") else if (it.isDigit()) targets.add("${provider.mainUrl}/tamil-atoz-dubbed-movies/0-9/")
        }
        
        val matched = mutableListOf<IsaidubProvider.ScrapedMovie>()
        for (url in targets.distinct()) {
            if (matched.isNotEmpty()) break
            val (movies, max) = scrapePageAndGetTotal(provider, url)
            val hits = provider.searchByTokenAndYear(movies, title, year)
            if (hits.isNotEmpty()) { matched.addAll(hits); break }
            
            if (max > 1) {
                coroutineScope {
                    (2..minOf(max, 6)).map { p -> 
                        async { scrapePageAndGetTotal(provider, "$url?get-page=$p").first }
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
            val doc = app.get(url, timeout = 10).document
            for (div in doc.select("div.f")) {
                div.selectFirst("a")?.let { movies.add(IsaidubProvider.ScrapedMovie(it.text().trim(), if(it.attr("href").startsWith("/")) "${provider.mainUrl}${it.attr("href")}" else it.attr("href"))) }
            }
            doc.selectFirst("span#totalPages")?.text()?.trim()?.toIntOrNull()?.let { maxPage = it }
        } catch (e: Exception) { }
        return Pair(movies, maxPage)
    }

    suspend fun extractLinks(provider: IsaidubProvider, pageUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false
        getResolutions(provider, pageUrl).forEach { res ->
            extractFinalLink(provider, res.url, 0, mutableSetOf())?.let { finalUrl ->
                val label = res.label.lowercase()
                val q = when { label.contains("1080") -> "(1080p)"; label.contains("720") -> "(720p)"; label.contains("640") || label.contains("360") -> "(640x360)"; label.contains("480") || label.contains("320") -> "(480x320)"; else -> "(HD)" }
                callback.invoke(newExtractorLink("Isaidub", "Isaidub $q", finalUrl, finalUrl.contains(".m3u8")) { this.referer = "${provider.mainUrl}/" })
                found = true
            }
        }
        return found
    }

    private suspend fun getResolutions(provider: IsaidubProvider, pageUrl: String, depth: Int = 0): List<ResolutionNode> {
        if (depth > 2) return emptyList()
        val found = mutableListOf<ResolutionNode>(); val folders = mutableListOf<String>()
        try {
            for (a in app.get(pageUrl, timeout = 10).document.select("a[href]")) {
                val href = a.attr("href"); val txt = a.text().trim().lowercase()
                if (!href.contains("/movie/") || txt.contains("sample") || href == pageUrl) continue
                val fullUrl = if (href.startsWith("http")) href else "${provider.mainUrl}$href"
                if (listOf("360","480","640","720","1080","hd","mp4").any { txt.contains(it) }) found.add(ResolutionNode(a.text(), fullUrl))
                else folders.add(fullUrl)
            }
            if (found.isEmpty()) folders.forEach { found.addAll(getResolutions(provider, it, depth + 1)) }
        } catch (e: Exception) { }
        return found
    }

    private suspend fun extractFinalLink(provider: IsaidubProvider, url: String, depth: Int, seen: MutableSet<String>): String? {
        if (depth > 5 || !seen.add(url)) return null
        try {
            val res = app.get(url, timeout = 10)
            if (res.headers["content-type"]?.contains("video/") == true) return res.url
            val txt = res.text
            Regex("""https?://[^\s"'<>]*download\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(txt)?.value?.let { return it }
            Regex("""https?://[^\s"'<>]*\.(?:mp4|m3u8)[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(txt)?.value?.let { return it }
            for (a in Jsoup.parse(txt).select("a[href]")) {
                val href = a.attr("href")
                if (a.text().lowercase().contains("sample") || href.contains("sample", true)) continue
                val full = when { href.startsWith("http") -> href; href.startsWith("//") -> "https:$href"; else -> "https://${URI(url).host}$href" }
                if (listOf("/download/", "/view/", "/file/", "download.php").any { full.lowercase().contains(it) }) extractFinalLink(provider, full, depth + 1, seen)?.let { return it }
            }
        } catch (e: Exception) { }
        return null
    }
}
