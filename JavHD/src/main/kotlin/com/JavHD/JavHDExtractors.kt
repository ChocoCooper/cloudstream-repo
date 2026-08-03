package com.JavHD

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.extractors.StreamWishExtractor
import com.lagradost.cloudstream3.extractors.VidhideExtractor
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.*

open class Stbturbo : ExtractorApi() {
    override var name = "Stbturbo"
    override var mainUrl = "https://stbturbo.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val document = app.get(url, referer = referer).document

        var finalLink = document.select("#video_player").attr("data-hash")

        // Fallback: some pages only expose the source via `var urlPlay = '...'`
        // instead of the data-hash attribute. Previously this value was
        // extracted but never assigned back to finalLink, so it was silently
        // discarded and the extractor returned a broken/empty URL.
        if (finalLink.isEmpty()) {
            val regex = Regex("""var urlPlay\s*=\s*['"]([^'"]+)['"]""")
            finalLink = regex.find(document.toString())?.groupValues?.get(1) ?: ""
        }

        // If neither method found a source, return null so Cloudstream can
        // fall through to the next server instead of trying to play a dead link.
        if (finalLink.isBlank()) return null

        return listOf(
            newExtractorLink(
                source = name,
                name = name,
                url = httpsify(finalLink),
                ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}


class Turbovid : Stbturbo() {
    override var name = "Turbovid"
    override var mainUrl = "https://turbovid.xyz"
    override val requiresReferer = false
}

class TurbovidVip : Stbturbo() {
    override var name = "TurbovidVip"
    override var mainUrl = "https://turbovid.vip"
}

class MyCloudZ : VidhideExtractor() {
    override var name = "MyCloudZ"
    override var mainUrl = "https://mycloudz.cc"
    override val requiresReferer = false
}

class Cloudwish : StreamWishExtractor() {
    override var name = "Cloudwish"
    override var mainUrl = "https://cloudwish.xyz"
    override val requiresReferer = false
}

class Streambeast : VidStack() {
    override var mainUrl = "https://streambeast.upn.one"
}
