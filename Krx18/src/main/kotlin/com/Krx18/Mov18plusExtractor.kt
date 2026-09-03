package com.KRX18

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

class Mov18plusExtractor : ExtractorApi() {
    override val name = "Mov18plus"
    override val mainUrl = "https://mov18plus.cloud"
    override val requiresReferer = true

    @Suppress("DEPRECATION")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer ?: "https://krx18.com/").document
        val iframeSrc = doc.selectFirst("iframe")?.attr("abs:src")
            ?: run {
                val script = doc.select("script").find { it.data().contains("abyssplayer.com") }
                val extracted = script?.data()?.let {
                    Regex("https?://player\\.abyssplayer\\.com/[A-Za-z0-9]+").find(it)?.value
                }
                extracted ?: return
            }

        val iframeDoc = app.get(iframeSrc, referer = mainUrl).document
        val videoUrl = extractVideo(iframeDoc)
        if (videoUrl != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name MP4",
                    url = videoUrl,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    type = INFER_TYPE,
                    headers = mapOf("Referer" to mainUrl)
                )
            )
        }
    }

    private fun extractVideo(doc: org.jsoup.nodes.Document): String? {
        doc.select("video[src], source[src]").firstOrNull()?.attr("abs:src")?.let { return it }
        for (script in doc.select("script")) {
            val data = script.data()
            val pattern = Regex("""https?://storage\.googleapis\.com/[^"'\s]+\.(?:mp4|m3u8)""")
            pattern.find(data)?.value?.let { return it }
            val jsonPattern = Regex("""\{[^{}]*"(?:src|file)"\s*:\s*"([^"]+)"[^{}]*\}""")
            jsonPattern.find(data)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }
}
