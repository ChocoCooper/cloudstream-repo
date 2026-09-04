package com.KRX18

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
        // 1. Fetch page and extract tokens
        val document = app.get(url, referer = referer).text

        val videoHash = Regex("""videoHash:\s*'([^']+)'""").find(document)?.groupValues?.get(1) ?: return
        val videoToken = Regex("""videoToken:\s*'([^']+)'""").find(document)?.groupValues?.get(1) ?: return

        val parsedDoc = Jsoup.parse(document)
        val csrfToken = parsedDoc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return

        // 2. POST to get the M3U8 manifest
        val resolveUrl = "$mainUrl/videos/resolve-token"
        val payload = mapOf(
            "token" to videoToken,
            "hash" to videoHash
        )

        val m3u8Content = app.post(
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

        if (!m3u8Content.contains("#EXTM3U")) {
            println("Loadvid: Response is not a valid M3U8 manifest.")
            return
        }

        // 3. Serve the manifest via local HTTP server (to avoid Cronet file:// issues)
        val localServer = LocalHttpServer()
        val manifestPath = "/loadvid_${System.currentTimeMillis()}.m3u8"
        localServer.serve(manifestPath, m3u8Content)

        val manifestUrl = "http://localhost:${localServer.port}$manifestPath"

        val headers = mapOf(
            "Referer" to mainUrl,
            "Origin" to mainUrl,
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        )

        // 4. Use M3u8Helper to parse and yield the streams
        M3u8Helper.generateM3u8(
            source = name,
            streamUrl = manifestUrl,
            referer = mainUrl,
            headers = headers
        ).forEach(callback)

        // The local server will be cleaned up automatically when the app stops.
        println("Loadvid: Successfully served manifest via local server.")
    }
}
