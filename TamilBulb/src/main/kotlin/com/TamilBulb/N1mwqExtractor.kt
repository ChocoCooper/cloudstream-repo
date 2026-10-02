package com.tamilbulb

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import org.json.JSONObject
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class N1mwqExtractor : ExtractorApi() {
    override var name = "N1mwq"
    override var mainUrl = "https://n1mwq.org"
    override val requiresReferer = true

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                     "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

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
        // URL forms: https://byseraguci.com/e/<code>
        //            https://filemoon.to/e/<code>
        //            https://n1mwq.org/<random>/<code>
        val code = url.trimEnd('/').substringAfterLast('/')
        if (code.isBlank()) return

        val headers = mapOf(
            "User-Agent" to ua,
            "Referer"    to (referer ?: "https://tamilbulb.cc/")
        )

        // 1. Ask the SPA's own API for the encrypted playback envelope
        val api = "https://n1mwq.org/api/videos/$code"
        val raw = app.get(api, headers = headers).text
        val root = JSONObject(raw)
        val pb   = root.optJSONObject("playback") ?: root

        val version  = pb.optString("version")
        val ivB64    = pb.optString("iv")
        val ctB64    = pb.optString("payload")
        val kpArr    = pb.optJSONArray("key_parts") ?: return

        // 2. Derive the AES-256 key: key_parts[v-1] || key_parts[(31-v)-1]
        val v = version.toIntOrNull() ?: return
        if (v < 1 || v > 20) return
        val b = 31 - v
        val parts = ArrayList<String>(kpArr.length())
        for (i in 0 until kpArr.length()) parts.add(kpArr.optString(i))
        if (v > parts.size || b > parts.size) return

        val key = b64u(parts[v - 1]) + b64u(parts[b - 1])
        if (key.size != 32) return

        // 3. AES-256-GCM decrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, b64u(ivB64))
        )
        val plain = cipher.doFinal(b64u(ctB64)).toString(Charsets.UTF_8)

        // 4. The plaintext is JSON. Look for the m3u8 in common fields.
        val m3u8 = runCatching {
            val j = JSONObject(plain)
            j.optString("url").takeIf { it.isNotBlank() }
                ?: j.optString("file").takeIf { it.isNotBlank() }
                ?: j.optString("m3u8").takeIf { it.isNotBlank() }
                ?: Regex("""https?://[^\s"']+?\.m3u8[^\s"']*""").find(plain)?.value
        }.getOrNull()
            ?: Regex("""https?://[^\s"']+?\.m3u8[^\s"']*""").find(plain)?.value
            ?: return

        callback.invoke(
            newExtractorLink(
                source = name,
                name   = label,
                url    = m3u8,
                type   = ExtractorLinkType.M3U8
            ) {
                this.referer = referer ?: url
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun b64u(s: String): ByteArray {
        var x = s.replace('-', '+').replace('_', '/')
        while (x.length % 4 != 0) x += "="
        return Base64.getDecoder().decode(x)
    }
}
