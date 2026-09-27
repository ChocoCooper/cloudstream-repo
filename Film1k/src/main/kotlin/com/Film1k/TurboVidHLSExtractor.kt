package com.Film1k

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * TurboVidHLS (Option 3) extractor.
 *
 * Confirmed from live testing against https://turbovidhls.com/t/<code>:
 *  - The page uses JW Player 7 (cdn4.turboviplay.com/jwplayer/js/jwplayer1.js)
 *  - The direct MP4 URL is present in the raw HTML:
 *      https://e08.etvp.cc/uploads/<code>.mp4
 *  - No API call, no encryption, no JS execution required.
 *
 * Extraction is a single regex for .mp4 / .m3u8 URLs.
 */
class TurboVidHLSExtractor : ExtractorApi() {
    override var mainUrl = "https://turbovidhls.com"
    override var name = "TurboVidHLS"
    override val requiresReferer = true

    private val mp4Regex = Regex(
        """https?://[^\s"'<>]+\.mp4[^\s"'<>]*""",
        RegexOption.IGNORE_CASE
    )
    private val m3u8Regex = Regex(
        """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""",
        RegexOption.IGNORE_CASE
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = app.get(
                url,
                referer = referer ?: mainUrl,
                verify = false
            ).text

            // Prefer direct MP4 (this is what the site actually serves)
            val mp4 = mp4Regex.find(html)?.value
            val m3u8 = m3u8Regex.find(html)?.value
            val streamUrl = mp4 ?: m3u8 ?: return

            val isM3u8 = streamUrl.contains(".m3u8")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            // Fail silently
        }
    }
}
