package com.KRX18

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities

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
        val doc = app.get(url, referer = referer ?: "https://krx18.com/").document

        val videoUrl = extractVideoUrl(doc)
        if (videoUrl != null) {
            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = "$name MP4",
                    url = videoUrl,
                    type = INFER_TYPE,
                    quality = Qualities.Unknown.value,
                    referer = mainUrl
                )
            )
        }
    }

    private suspend fun extractVideoUrl(doc: org.jsoup.nodes.Document): String? {
        // Scan inline scripts
        for (script in doc.select("script:not([src])")) {
            val data = script.data()
            val patterns = listOf(
                Regex(""""file"\s*:\s*"([^"]+)""""),
                Regex(""""src"\s*:\s*"([^"]+)""""),
                Regex(""""video"\s*:\s*"([^"]+)""""),
                Regex("""https?://[^"'\s]+\.(?:mp4|m3u8|mkv|webm)""")
            )
            for (pat in patterns) {
                pat.find(data)?.let {
                    val url = if (pat.pattern.contains("https?")) it.value else it.groupValues[1]
                    return url
                }
            }
        }

        // Load main JS
        val jsUrl = doc.select("script[src*='video-player-main']").firstOrNull()?.attr("abs:src")
        if (jsUrl != null) {
            try {
                val jsContent = app.get(jsUrl, referer = mainUrl).text
                val pattern = Regex("""https?://[^"'\s]+\.(?:mp4|m3u8|mkv|webm)""")
                pattern.find(jsContent)?.value?.let { return it }
            } catch (_: Exception) { }
        }

        // Fallback: video/source tags
        doc.select("source[src], video[src]").firstOrNull()?.attr("abs:src")?.let { return it }

        return null
    }
}
