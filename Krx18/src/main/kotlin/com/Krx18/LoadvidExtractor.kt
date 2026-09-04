package com.KRX18

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.io.File

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
        
        // 2. Extract Laravel configuration and CSRF tokens
        val videoHash = Regex("""videoHash:\s*'([^']+)'""").find(document)?.groupValues?.get(1) ?: return
        val videoToken = Regex("""videoToken:\s*'([^']+)'""").find(document)?.groupValues?.get(1) ?: return
        
        val parsedDoc = Jsoup.parse(document)
        val csrfToken = parsedDoc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return

        // 3. Execute the Server-Side Resolution POST Request
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
            
            // THE FIX: Bypassing Cronet's ERR_UNKNOWN_URL_SCHEME crash.
            // Cronet blocks data: URIs, but Cloudstream natively allows file:// URIs for local playback.
            // We write the manifest to the Android cache directory; ExoPlayer will read it and 
            // extract the absolute HTTPS chunk URLs automatically.
            
            val cacheDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
            val tempM3u8File = File.createTempFile("loadvid_manifest_", ".m3u8", cacheDir)
            tempM3u8File.writeText(m3u8Response)
            tempM3u8File.deleteOnExit() // Android will auto-cleanup this file
            
            val localFileUri = "file://${tempM3u8File.absolutePath}"

            val exoHeaders = mapOf(
                "Referer" to mainUrl,
                "Origin" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )

            // Using the non-deprecated primary ExtractorLink constructor
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name Auto",
                    url = localFileUri,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                    headers = exoHeaders
                )
            )
        }
    }
}
