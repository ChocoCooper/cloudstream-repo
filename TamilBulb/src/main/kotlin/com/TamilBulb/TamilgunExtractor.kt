package com.tamilbulb

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class TamilgunExtractor : ExtractorApi() {
    override var name = "Tamilgun"
    override var mainUrl = "https://tamilgun.space"
    override val requiresReferer = true

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                     "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private fun log(msg: String) = Log.d("TamilBulb", "[tamilgun] $msg")

    /**
     * Full browser-iframe headers. The `Sec-Fetch-*` trio is what makes
     * Cloudflare treat this as a real iframe navigation instead of a bot.
     */
    private fun browserHeaders(referer: String?): Map<String, String> = mapOf(
        "User-Agent"                to ua,
        "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language"           to "en-US,en;q=0.9",
        "Referer"                   to (referer ?: "$mainUrl/"),
        "Sec-Fetch-Dest"            to "iframe",
        "Sec-Fetch-Mode"            to "navigate",
        "Sec-Fetch-Site"            to "cross-site",
        "Upgrade-Insecure-Requests" to "1",
        "Cache-Control"             to "no-cache",
        "Pragma"                    to "no-cache"
    )

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = extract(url, referer, name, subtitleCallback, callback)

    suspend fun extract(
        url: String, referer: String?, label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        log("extract url=$url referer=$referer")

        val html = try {
            val resp = app.get(
                url,
                headers = browserHeaders(referer),
                timeout = 30L
            )
            log("HTTP ${resp.code} (${resp.text.length} bytes)")
            resp.text
        } catch (e: Exception) {
            Log.e("TamilBulb", "[tamilgun] fetch failed with ${e.javaClass.simpleName}", e)
            // Retry once — sometimes Cloudflare's edge is just slow
            try {
                log("retrying after 2s delay...")
                kotlinx.coroutines.delay(2000)
                val resp = app.get(url, headers = browserHeaders(referer), timeout = 45L)
                log("retry HTTP ${resp.code} (${resp.text.length} bytes)")
                resp.text
            } catch (e2: Exception) {
                Log.e("TamilBulb", "[tamilgun] retry failed too", e2)
                return
            }
        }

        // Bail if we got a Cloudflare challenge page instead of the player
        if (html.contains("Just a moment", true) ||
            html.contains("cf-challenge", true) ||
            html.contains("Attention Required", true)) {
            log("⚠️ Cloudflare challenge page detected — cannot extract")
            return
        }

        val decoded = PackardDecoder.decode(html).also {
            if (it == null) log("⚠️ PACKER decode failed, using raw HTML")
        } ?: html
        log("decoded length=${decoded.length}")

        // Priority: hls4 → hls2 → any master.m3u8
        val hls4    = Regex(""""hls4"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(decoded)?.groupValues?.get(1)
        val hls2    = Regex(""""hls2"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(decoded)?.groupValues?.get(1)
        val generic = Regex("""(https?://[^\s"']+?master\.m3u8[^\s"']*)""").find(decoded)?.groupValues?.get(1)
        log("hls4=$hls4")
        log("hls2=$hls2")
        log("generic=$generic")

        val m3u8 = hls4 ?: hls2 ?: generic
        if (m3u8.isNullOrBlank()) {
            log("✗ no m3u8 found in decoded JS — dumping first 500 chars of decoded:")
            log("decoded preview: ${decoded.take(500).replace("\n", " ")}")
            return
        }

        val fullUrl = if (m3u8.startsWith("/")) "$mainUrl$m3u8" else m3u8
        log("→ emitting $fullUrl")

        callback.invoke(
            newExtractorLink(
                source = name,
                name   = label,
                url    = fullUrl,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = referer ?: url
                this.quality = Qualities.Unknown.value
            }
        )

        // Subtitles if present in the decoded script
        Regex("""file\s*:\s*"(https?://[^"]+\.vtt)"""")
            .findAll(decoded)
            .forEach { m -> subtitleCallback.invoke(SubtitleFile("English", m.groupValues[1])) }
    }
}
