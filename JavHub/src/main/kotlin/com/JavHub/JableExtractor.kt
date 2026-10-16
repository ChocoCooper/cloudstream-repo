package com.JavHub

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class JableExtractor : JavExtractor {
    override val name = "Jable"

    override suspend fun extract(code: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val responseText = app.get(
                "https://jable.tv/videos/$code/?lang=en",
                timeout = 15,
                headers = BROWSER_HEADERS
            ).text

            val hlsUrl = Regex("""hlsUrl\s*=\s*['"](https?://[^'"]+\.m3u8)['"]""")
                .find(responseText)?.groupValues?.get(1)
                ?: Regex("""(https?://[^\s'"<>]+?\.m3u8[^\s'"<>]*)""")
                    .find(responseText.replace("\\/", "/"))?.groupValues?.get(1)

            if (hlsUrl.isNullOrBlank()) return false

            callback.invoke(
                newExtractorLink(
                    "Jable",
                    "Jable",
                    hlsUrl.toPlaylistM3u8(),
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://jable.tv/"
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }
}
