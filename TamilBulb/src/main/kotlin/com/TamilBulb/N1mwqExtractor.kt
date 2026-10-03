package com.tamilbulb

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
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

    private fun log(msg: String) = Log.d("TamilBulb", "[n1mwq] $msg")

    private fun browserHeaders(referer: String?): Map<String, String> = mapOf(
        "User-Agent"      to ua,
        "Accept"          to "application/json, text/plain, */*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Referer"         to (referer ?: "https://tamilbulb.cc/"),
        "Origin"          to "https://n1mwq.org",
        "Sec-Fetch-Dest"  to "empty",
        "Sec-Fetch-Mode"  to "cors",
        "Sec-Fetch-Site"  to "cross-site"
    )

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = extract(url, referer, name, subtitleCallback, callback)

    suspend fun extract(
        url: String, referer: String?, label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        log("extract url=$url")
        val code = url.trimEnd('/').substringAfterLast('/')
        if (code.isBlank()) { log("✗ empty code"); return }
        log("code=$code")

        val api = "https://n1mwq.org/api/videos/$code"
        val raw = try {
            val resp = app.get(api, headers = browserHeaders(referer), timeout = 30L)
            log("API HTTP ${resp.code} (${resp.text.length} bytes)")
            resp.text
        } catch (e: Exception) {
            Log.e("TamilBulb", "[n1mwq] API fetch failed", e)
            return
        }

        val root = JSONObject(raw)
        val pb = root.optJSONObject("playback") ?: root

        val version = pb.optString("version")
        val ivB64   = pb.optString("iv")
        val ctB64   = pb.optString("payload")
        val kpArr   = pb.optJSONArray("key_parts")
        log("version=$version iv=${ivB64.take(12)}… ct=${ctB64.length}B kp=${kpArr?.length()}")
        if (kpArr == null) { log("✗ no key_parts"); return }

        val v = version.toIntOrNull() ?: return
        if (v < 1 || v > 20) return
        val b = 31 - v
        val parts = ArrayList<String>(kpArr.length())
        for (i in 0 until kpArr.length()) parts.add(kpArr.optString(i))
        if (v > parts.size || b > parts.size) { log("✗ index out of range"); return }

        val key = b64u(parts[v - 1]) + b64u(parts[b - 1])
        log("key len=${key.size}")
        if (key.size != 32) return

        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, b64u(ivB64))
            )
            cipher.doFinal(b64u(ctB64)).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("TamilBulb", "[n1mwq] decrypt failed", e); return
        }
        log("plaintext=${plain.take(200)}…")

        val m3u8 = runCatching {
            val j = JSONObject(plain)
            j.optString("url").takeIf { it.isNotBlank() }
                ?: j.optString("file").takeIf { it.isNotBlank() }
                ?: j.optString("m3u8").takeIf { it.isNotBlank() }
        }.getOrNull()
            ?: Regex("""https?://[^\s"']+?\.m3u8[^\s"']*""").find(plain)?.value
        if (m3u8.isNullOrBlank()) { log("✗ no m3u8 in plaintext"); return }
        log("→ emitting $m3u8")

        callback.invoke(
            newExtractorLink(
                source = name, name = label, url = m3u8, type = ExtractorLinkType.M3U8
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
