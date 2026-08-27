package com.Eporner

import android.util.Base64
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.math.BigInteger
import java.net.URI

class Eporner : MainAPI() {
    override var mainUrl              = "https://www.eporner.com"
    override var name                 = "Eporner"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
            "" to "Recent Videos",
            "best-videos" to "Best Videos",
            "top-rated" to "Top Rated",
            "most-viewed" to "Most Viewed",
            "recommendations" to "Recommendation Videos",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/$page/").document
        val home = document.select("#div-search-results div.mb").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list    = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = fixTitle(this.select("div.mbunder p.mbtit a").text() ?: "No Title").trim()
        val href = fixUrl(this.select("div.mbcontent a").attr("href"))
        var posterUrl = this.selectFirst("img")?.attr("data-src")
        if (posterUrl.isNullOrBlank())
        {
            posterUrl=this.selectFirst("img")?.attr("src")
        }
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val subquery = query.replace(" ","-")
        val document = app.get("${mainUrl}/search/$subquery/$page").document
        val results = document.select("div.mb").mapNotNull { it.toSearchResult() }
        val hasNext = if(results.isEmpty()) false else true
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    private suspend fun resolveDoH(host: String): String? {
        return try {
            val response = app.get("https://dns.google/resolve?name=$host").text
            val json = JSONObject(response)
            val answers = json.optJSONArray("Answer") ?: return null
            for (i in 0 until answers.length()) {
                val answer = answers.getJSONObject(i)
                val type = answer.getInt("type")
                if (type == 1 || type == 28) {
                    val ip = answer.getString("data")
                    return if (ip.contains(":")) "[$ip]" else ip
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = app.get(data).toString()
        val vid = Regex("EP.video.player.vid = '([^']+)'").find(doc)?.groupValues?.get(1).toString()
        val hash = Regex("EP.video.player.hash = '([^']+)'").find(doc)?.groupValues?.get(1).toString()
        val url = "https://www.eporner.com/xhr/video/$vid?hash=${base36(hash)}"
        
        val json = app.get(url).text
        val jsonObject = JSONObject(json)
        val mp4Sources = jsonObject.getJSONObject("sources").getJSONObject("mp4")
        val qualities = mp4Sources.keys()
        
        var m3u8Content = "#EXTM3U\n"
        var commonHost = ""
        
        while (qualities.hasNext()) {
            val quality = qualities.next() as String
            val sourceObject = mp4Sources.getJSONObject(quality)
            val src = sourceObject.getString("src")
            val labelShort = sourceObject.getString("labelShort") ?: quality
            
            var finalUrl = src
            try {
                val uri = URI(src)
                commonHost = uri.host
                val ip = resolveDoH(commonHost)
                
                if (ip != null) {
                    finalUrl = src.replace("https://", "http://").replace(commonHost, ip)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Map Eporner qualities to HLS tags so ExoPlayer can render track resolutions natively
            val (bw, res) = when {
                labelShort.contains("1080") -> "5000000" to "1920x1080"
                labelShort.contains("720") -> "2500000" to "1280x720"
                labelShort.contains("480") -> "1000000" to "854x480"
                labelShort.contains("360") -> "750000" to "640x360"
                else -> "500000" to "426x240"
            }
            
            m3u8Content += "#EXT-X-STREAM-INF:BANDWIDTH=$bw,RESOLUTION=$res,NAME=\"$labelShort\"\n$finalUrl\n"
        }

        // Compile raw MP4s into a dynamic local HLS manifest via Data URI
        val encodedM3u8 = Base64.encodeToString(m3u8Content.toByteArray(), Base64.NO_WRAP)
        val dataUri = "data:application/x-mpegURL;base64,$encodedM3u8"

        val finalHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to data,
            "Host" to commonHost
        )

        // Return a single master playlist link named "Eporner"
        callback.invoke(
            ExtractorLink(
                source = name,
                name = "Eporner",
                url = dataUri,
                referer = data,
                quality = Qualities.Unknown.value,
                isM3u8 = true,
                headers = finalHeaders
            )
        )
        return true
    }

    fun base36(hash: String): String {
        return if (hash.length >= 32) {
            val part1 = BigInteger(hash.substring(0, 8), 16).toString(36)
            val part2 = BigInteger(hash.substring(8, 16), 16).toString(36)
            val part3 = BigInteger(hash.substring(16, 24), 16).toString(36)
            val part4 = BigInteger(hash.substring(24, 32), 16).toString(36)
            part1 + part2 + part3 + part4
        } else {
            throw IllegalArgumentException("Hash length is invalid")
        }
    }
    
    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]").find(str ?: "") ?. groupValues ?. getOrNull(1) ?. toIntOrNull()
            ?: Qualities.Unknown.value
    }
}
