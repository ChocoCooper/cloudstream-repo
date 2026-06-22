package com.MissAv

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class MissAVProvider : MainAPI() {
    override var mainUrl              = "https://missav.ws"
    override var name                 = "MissAV"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    val subtitleCatUrl = "https://www.subtitlecat.com"

    override val mainPage = mainPageOf(
        "/dm514/en/new" to "Recent Update",
        "/dm588/en/release" to "New Release",
        "/dm291/en/today-hot" to "Most Viewed Today",
        "/dm169/en/weekly-hot" to "Most Viewed by Week",
        "/dm256/en/monthly-hot" to "Most Viewed by Month"
    )
    
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl${request.data}?page=$page").document
        val responseList  = document.select(".thumbnail").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, responseList, isHorizontalImages = true), hasNext = true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = this.selectFirst(".text-secondary") ?: this.selectFirst("a") ?: return null
        
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        
        val rawTitle = aTag.text().trim()
        if (rawTitle.isEmpty() || href.isEmpty()) return null

        val status = this.select(".bg-blue-800").text().trim()
        val title = if(status.isNotBlank()) "[$status] $rawTitle" else rawTitle
        
        val img = this.selectFirst("img")
        val posterUrl = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src")?.takeIf { it.isNotBlank() }

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/en/search/$encodedQuery").document
        val results = document.select(".thumbnail, .max-w-sm, .w-full.truncate").mapNotNull { it.toSearchResult() }
        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim() ?: "MissAV Video"
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val response = app.get(data)
        val doc = response.document
        
        val unpackedText = getAndUnpack(response.text)
        val finalLink = Regex("""source=['"](.*?)['"]""").find(unpackedText)?.groupValues?.get(1)
        
        if (!finalLink.isNullOrBlank()) {
            // FIX: Using the trailing lambda {} to set referer and quality, which prevents the GitHub Action crash
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    finalLink,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.P1080.value
                }
            )
        }

        try {
            val title = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
            val javCode = "([a-zA-Z]+-\\d+)".toRegex().find(title)?.groups?.get(1)?.value
            if(!javCode.isNullOrEmpty()) {
                val query = "$subtitleCatUrl/index.php?search=$javCode"
                val subDoc = app.get(query, timeout = 15).document
                val subList = subDoc.select("td a")
                for(item in subList) {
                    if(item.text().contains(javCode, ignoreCase = true)) {
                        val fullUrl = "$subtitleCatUrl/${item.attr("href")}"
                        val pDoc = app.get(fullUrl, timeout = 10).document
                        val sList = pDoc.select(".col-md-6.col-lg-4")
                        for(subItem in sList) {
                            try {
                                val language = subItem.select(".sub-single span:nth-child(2)").text()
                                val text = subItem.select(".sub-single span:nth-child(3) a")
                                if(text.isNotEmpty() && text[0].text() == "Download") {
                                    val url = "$subtitleCatUrl${text[0].attr("href")}"
                                    // FIX: Uses newSubtitleFile to fix deprecation warning
                                    subtitleCallback.invoke(
                                        newSubtitleFile(
                                            language.replace("\uD83D\uDC4D \uD83D\uDC4E","").trim(), 
                                            url     
                                        )
                                    )
                                }
                            } catch (e: Exception) { }
                        }
                    }
                }
            }
        } catch (e: Exception) { }

        return true
    }
}
