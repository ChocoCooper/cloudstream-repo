package com.JavHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

data class JavtifulSource(
    @JsonProperty("src") val src: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("size") val size: Int? = null
)

data class JavtifulWatchConfig(
    @JsonProperty("playerSources") val playerSources: List<JavtifulSource>? = null
)

class JavtifulExtractor : JavExtractor {
    override val name = "Javtiful"

    override suspend fun extract(code: String, callback: (ExtractorLink) -> Unit): Boolean {
        return try {
            val searchDoc = app.get(
                "https://javtiful.com/search?q=$code",
                headers = BROWSER_HEADERS
            ).document

            val path = searchDoc.selectFirst(
                "body > main > section.front-section > div > div.front-video-grid > article > a"
            )?.attr("href")
                ?: searchDoc.selectFirst("article a")?.attr("href")
                ?: return false

            if (path.isBlank()) return false

            val fullUrl = if (path.startsWith("http")) path
                          else "https://javtiful.com$path"

            val document = app.get(fullUrl, headers = BROWSER_HEADERS).document
            val scriptData = document.selectFirst("script#frontWatchConfig")?.data()
            if (scriptData.isNullOrBlank()) return false

            val config = parseJson<JavtifulWatchConfig>(scriptData)
            var found = false

            config.playerSources?.forEach { source ->
                val src = source.src ?: return@forEach
                if (src.isBlank()) return@forEach

                callback.invoke(
                    newExtractorLink(
                        "Javtiful",
                        "Javtiful",
                        src,
                        if (src.contains(".m3u8")) ExtractorLinkType.M3U8
                        else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://javtiful.com/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                found = true
            }
            found
        } catch (e: Exception) {
            false
        }
    }
}
