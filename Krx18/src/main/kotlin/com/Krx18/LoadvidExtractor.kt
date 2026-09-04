package com.KRX18

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class LoadvidExtractor : ExtractorApi() {
    override val name = "Loadvid"
    override val mainUrl = "https://cdn.loadvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. Establish session and fetch live HTML
        val document = app.get(url, referer = referer).text
        
        // 2. Extract Config and Tokens
        val videoHash = Regex("""videoHash:\s*'([^']+)'""").find(document)?.groupValues?.get(1) ?: return
        val videoToken = Regex("""videoToken:\s*'([^']+)'""").find(document)?.groupValues?.get(1) ?: return
        
        val parsedDoc = Jsoup.parse(document)
        val csrfToken = parsedDoc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return

        // 3. Execute the Server-Side Resolution POST Request instantly
        val resolveUrl = "$mainUrl/videos/resolve-token"
        val payload = mapOf(
            "token" to videoToken,
            "hash" to videoHash
        )

        val m3u8Response = app.post(
            resolveUrl,
            headers = mapOf(
                "Content-Type" to "application/json",
                "X-CSRF-TOKEN" to csrfToken,
                "Accept" to "application/vnd.apple.mpegurl,*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to mainUrl
            ),
            json = payload
        ).text

        // 4. Process the raw M3U8 Text
        if (m3u8Response.contains("#EXTM3U")) {
            // Because the segments use absolute URLs (https://...), we can feed the raw text 
            // directly into ExoPlayer as a Base64 Data URI without needing a local proxy server.
            val base64M3u8 = Base64.encodeToString(m3u8Response.toByteArray(), Base64.NO_WRAP)
            val dataUri = "data:application/vnd.apple.mpegurl;base64,$base64M3u8"

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = dataUri,
                    referer = url,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true // Signals ExoPlayer to parse it as HLS
                )
            )
        }
    }
}
