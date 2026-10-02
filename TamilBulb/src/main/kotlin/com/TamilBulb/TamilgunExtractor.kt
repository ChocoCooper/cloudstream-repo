package com.tamilbulb

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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, name, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                 "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // 1. Fetch embed page with the referer that was confirmed working
        val html = app.get(
            url,
            headers = mapOf(
                "User-Agent" to ua,
                "Referer"    to (referer ?: "")
            )
        ).text

        // 2. PACKER decode (base-36 simple variant)
        val decoded = PackardDecoder.decode(html) ?: html

        // 3. Extract hls4 → hls2 → generic master.m3u8
        val m3u8 = Regex(""""hls4"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
            .find(decoded)?.groupValues?.get(1)
            ?: Regex(""""hls2"\s*:\s*"([^"]+\.m3u8[^"]*)"""")
                .find(decoded)?.groupValues?.get(1)
            ?: Regex("""(https?://[^\s"']+?master\.m3u8[^\s"']*)""")
                .find(decoded)?.groupValues?.get(1)
            ?: return

        // 4. Prepend origin if the URL is relative (hls4 form)
        val fullUrl = if (m3u8.startsWith("/")) "$mainUrl$m3u8" else m3u8

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

        // 5. Optional subtitles if present in the decoded script
        Regex("""file\s*:\s*"(https?://[^"]+\.vtt)"""")
            .findAll(decoded)
            .forEach { m ->
                subtitleCallback.invoke(SubtitleFile("English", m.groupValues[1]))
            }
    }
}
