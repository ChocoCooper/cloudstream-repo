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
            val resp = app.get(url, headers = mapOf(
                "User-Agent" to ua,
                "Referer"    to (referer ?: "")
            ))
            log("HTTP ${resp.code} (${resp.text.length} bytes)")
            resp.text
        } catch (e: Exception) {
            Log.e("TamilBulb", "[tamilgun] fetch failed", e); return
        }

        val decoded = PackardDecoder.decode(html) ?: html.also { log("⚠️ PACKER decode failed, using raw") }
        log("decoded length=${decoded.length}")

        val hls4 = Regex(""""hls4"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(decoded)?.groupValues?.get(1)
        val hls2 = Regex(""""hls2"\s*:\s*"([^"]+\.m3u8[^"]*)"""").find(decoded)?.groupValues?.get(1)
        val generic = Regex("""(https?://[^\s"']+?master\.m3u8[^\s"']*)""").find(decoded)?.groupValues?.get(1)
        log("hls4=$hls4")
        log("hls2=$hls2")
        log("generic=$generic")

        val m3u8 = hls4 ?: hls2 ?: generic ?: run {
            log("✗ no m3u8 in decoded JS")
            return
        }
        val fullUrl = if (m3u8.startsWith("/")) "$mainUrl$m3u8" else m3u8
        log("→ emitting $fullUrl")

        callback.invoke(newExtractorLink(
            source = name, name = label, url = fullUrl, type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer ?: url
            this.quality = Qualities.Unknown.value
        })

        Regex("""file\s*:\s*"(https?://[^"]+\.vtt)"""")
            .findAll(decoded)
            .forEach { m -> subtitleCallback.invoke(SubtitleFile("English", m.groupValues[1])) }
    }
}
