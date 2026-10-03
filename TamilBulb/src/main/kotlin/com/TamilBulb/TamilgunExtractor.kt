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

    private fun browserHeaders(referer: String?) = mapOf(
        "User-Agent"                to ua,
        "Accept"                    to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language"           to "en-US,en;q=0.9",
        "Referer"                   to (referer ?: "$mainUrl/"),
        "Sec-Fetch-Dest"            to "iframe",
        "Sec-Fetch-Mode"            to "navigate",
        "Sec-Fetch-Site"            to "cross-site",
        "Upgrade-Insecure-Requests" to "1"
    )

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = extract(url, referer, name, subtitleCallback, callback)

    suspend fun extract(
        url: String, referer: String?, label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        mainUrlOverride: String? = null          // ← NEW: reuse on cdnbulb, cdntamil, etc.
    ) {
        val effectiveMain = mainUrlOverride ?: mainUrl
        log("extract url=$url  mainUrl=$effectiveMain")

        val html = try {
            val resp = app.get(url, headers = browserHeaders(referer), timeout = 30L)
            log("HTTP ${resp.code} (${resp.text.length} bytes)")
            resp.text
        } catch (e: Exception) {
            Log.e("TamilBulb", "[tamilgun] fetch failed", e); return
        }

        if (html.contains("Just a moment", true) || html.contains("cf-challenge", true)) {
            log("⚠️ Cloudflare challenge page"); return
        }

        val decoded = PackardDecoder.decode(html) ?: html.also { log("PACKER decode failed") }
        log("decoded length=${decoded.length}")

        val hls4    = Regex(""""hls4"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(decoded)?.groupValues?.get(1)
        val hls2    = Regex(""""hls2"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(decoded)?.groupValues?.get(1)
        val generic = Regex("""(https?://[^\s"']+?\.m3u8[^\s"']*)""").find(decoded)?.groupValues?.get(1)
        log("hls4=$hls4")
        log("hls2=${hls2?.take(80)}…")
        log("generic=${generic?.take(80)}…")

        val m3u8 = hls4 ?: hls2 ?: generic
        if (m3u8.isNullOrBlank()) {
            log("✗ no m3u8 — dumping 300 chars: ${decoded.take(300)}")
            return
        }

        // Use the OVERRIDDEN origin for relative URLs (important for cdnbulb reuse)
        val fullUrl = if (m3u8.startsWith("/")) "$effectiveMain$m3u8" else m3u8
        log("→ emitting $fullUrl")

        callback.invoke(newExtractorLink(name, label, fullUrl, ExtractorLinkType.M3U8) {
            this.referer = referer ?: url
            this.quality = Qualities.Unknown.value
        })

        Regex("""file\s*:\s*"([^"]+\.vtt)"""")
            .findAll(decoded)
            .forEach { m -> subtitleCallback.invoke(SubtitleFile("English", m.groupValues[1])) }
    }
}
