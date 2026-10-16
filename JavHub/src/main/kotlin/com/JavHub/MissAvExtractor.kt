package com.JavHub

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

class MissAvExtractor : JavExtractor {
    override val name = "MissAV"

    override suspend fun extract(code: String, callback: (ExtractorLink) -> Unit): Boolean {
        val variants = listOf(
            code to "MissAV",
            "$code-uncensored-leak" to "MissAV [Uncensored]",
            "$code-english-subtitle" to "MissAV [English Subtitle]"
        )

        var found = false
        for ((vCode, sourceName) in variants) {
            val ok = try {
                extractVariant(vCode, sourceName, callback)
            } catch (e: Exception) {
                false
            }
            if (ok) found = true
        }
        return found
    }

    private suspend fun extractVariant(
        code: String,
        sourceName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(
            "https://missav.ws/en/$code",
            timeout = 15,
            headers = BROWSER_HEADERS
        )
        if (response.code != 200) return false

        val unpackedText = getAndUnpack(response.text)

        var finalLink = Regex("""source\s*[:=]\s*['"](.*?)['"]""")
            .find(unpackedText)?.groupValues?.get(1)

        if (finalLink?.startsWith("aHR0c") == true) {
            finalLink = String(Base64.decode(finalLink, Base64.DEFAULT))
        }

        if (finalLink.isNullOrBlank()) {
            val b64Match = Regex("""['"](aHR0c[a-zA-Z0-9+/=]+)['"]""")
                .find(unpackedText)?.groupValues?.get(1)
            if (b64Match != null) {
                val decoded = String(Base64.decode(b64Match, Base64.DEFAULT))
                if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                    finalLink = decoded
                }
            }
        }

        if (finalLink.isNullOrBlank()) {
            finalLink = Regex("""https?://[^"'\s]+?\.m3u8[^"'\s]*""")
                .find(unpackedText.replace("\\/", "/"))?.value
        }

        if (finalLink.isNullOrBlank()) return false

        callback.invoke(
            newExtractorLink(
                sourceName,
                sourceName,
                finalLink.toPlaylistM3u8(),
                ExtractorLinkType.M3U8
            ) {
                this.referer = "https://missav.ws/"
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
