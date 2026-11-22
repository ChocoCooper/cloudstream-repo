package com.Film1k

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.net.URI

class Film1kExtractor : ExtractorApi() {
    override var mainUrl = "https://film1k.xyz"
    override var name = "Film1k"
    override val requiresReferer = false

    private val TAG = "Film1kDebug"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            android.util.Log.e(TAG, "Film1k getUrl: $url")
            val code = Regex("""/e/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: run {
                android.util.Log.w(TAG, "Film1k: no code in URL")
                return
            }
            val embedParent = "https://film1k.xyz/e/$code"

            // 1.2 — read from cache if provider pre-warmed it, else fetch with retry
            val detailsText = Film1kResolver.getCachedDetails(code) ?: run {
                val resp = Film1kResolver.retry(times = 3, initialDelayMs = 500) {
                    app.get(
                        "https://film1k.xyz/api/videos/$code/embed/details",
                        referer = embedParent,
                        verify = false
                    )
                } ?: run {
                    android.util.Log.e(TAG, "Film1k: details fetch failed after retries")
                    return
                }
                Film1kResolver.putCachedDetails(code, resp.text)
                resp.text
            }
            android.util.Log.e(TAG, "Film1k: details resolved (${detailsText.length} chars)")

            val details = JSONObject(detailsText)
            val embedFrameUrl = details.getString("embed_frame_url")
            val uri = URI(embedFrameUrl)
            val apiBase = "${uri.scheme}://${uri.host}"
            android.util.Log.e(TAG, "Film1k: apiBase=$apiBase")

            val decrypted = Film1kResolver.resolvePlayback(apiBase, embedParent, code) ?: run {
                android.util.Log.w(TAG, "Film1k: resolvePlayback returned null")
                return
            }
            val sources = decrypted.optJSONArray("sources") ?: run {
                android.util.Log.w(TAG, "Film1k: no sources in decrypted payload")
                return
            }

            for (i in 0 until sources.length()) {
                val src = sources.getJSONObject(i)
                val streamUrl = src.optString("url").takeIf { it.isNotBlank() } ?: continue
                val mimeType = src.optString("mime_type")
                val isM3u8 = streamUrl.contains(".m3u8") || mimeType.contains("mpegurl")
                android.util.Log.e(TAG, "Film1k: emitting $streamUrl (m3u8=$isM3u8)")

                callback.invoke(
                    newExtractorLink(
                        name = "Film1k",
                        source = "Film1k",
                        url = streamUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = apiBase
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            val tracks = decrypted.optJSONArray("tracks")
            if (tracks != null) {
                for (i in 0 until tracks.length()) {
                    val track = tracks.getJSONObject(i)
                    val trackUrl = track.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val lang = track.optString("label", track.optString("language", "Unknown"))
                    subtitleCallback.invoke(SubtitleFile(lang, trackUrl))
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Film1k extractor failed", e)
        }
    }
}
