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
import java.net.URLDecoder
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

    private fun browserHeaders(referer: String?) = mapOf(
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

        val code = extractCode(url)
        if (code.isNullOrBlank()) { log("✗ empty code from url=$url"); return }
        log("code=$code")

        val api = "https://n1mwq.org/api/videos/$code"
        val raw = try {
            val resp = app.get(api, headers = browserHeaders(referer), timeout = 30L)
            log("API HTTP ${resp.code} (${resp.text.length} bytes)")
            if (resp.code != 200) return
            resp.text
        } catch (e: Exception) {
            Log.e("TamilBulb", "[n1mwq] API fetch failed", e); return
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
        if (v < 1 || v > 20) { log("✗ version out of range"); return }
        val b = 31 - v
        val parts = ArrayList<String>(kpArr.length())
        for (i in 0 until kpArr.length()) parts.add(kpArr.optString(i))
        if (v > parts.size || b > parts.size) { log("✗ key_parts index out of range"); return }

        val key = b64u(parts[v - 1]) + b64u(parts[b - 1])
        log("key len=${key.size}")
        if (key.size != 32) { log("✗ key not 32 bytes"); return }

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

        val m3u8: String? = try {
            val j = JSONObject(plain)

            val simple = sequenceOf("url", "file", "m3u8")
                .map { j.optString(it) }
                .firstOrNull { it.startsWith("http") }

            val fromSources: String? = run {
                val sources = j.optJSONArray("sources") ?: return@run null
                var bestUrl: String? = null
                var bestScore = -1
                for (i in 0 until sources.length()) {
                    val src = sources.optJSONObject(i) ?: continue
                    val u = src.optString("url").takeIf { it.startsWith("http") } ?: continue
                    val q = src.optString("label").filter { it.isDigit() }.toIntOrNull() ?: 0
                    if (q > bestScore) { bestScore = q; bestUrl = u }
                }
                if (bestUrl != null) log("picked source label=${bestScore}p")
                bestUrl
            }

            simple ?: fromSources
        } catch (e: Exception) {
            log("JSON parse failed: ${e.message}"); null
        }

        val fallback = Regex("""https?://[^\s"'\\]+?\.m3u8[^\s"'\\]*""").find(plain)?.value
        val chosen = m3u8 ?: fallback
        if (chosen.isNullOrBlank()) { log("✗ no m3u8 found"); return }

        val clean = chosen.replace("\\u0026", "&").replace("\\/", "/")
        log("→ emitting ${clean.take(140)}…")

        callback.invoke(
            newExtractorLink(
                source = name, name = label, url = clean, type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer ?: url
                this.quality = Qualities.Unknown.value
            }
        )
    }

    /**
     * Extracts the video code from all known URL shapes:
     *
     *   https://filemoon.to/e/y687uhvor9zx
     *   https://byseraguci.com/e/j540ey878j2u/the-end-of-oak-street-2026-hq-...
     *   https://bulbmoviehd.online/r/?id=https://filemoon.to/d/y687uhvor9zx
     *   https://filemoon.to/d/y687uhvor9zx
     */
    private fun extractCode(url: String): String? {
        // Shape A: ?id=<embedded URL>  → recurse on the decoded value
        val idParam = Regex("""[?&]id=([^&]+)""").find(url)?.groupValues?.get(1)
        if (idParam != null) {
            val decoded = try { URLDecoder.decode(idParam, "UTF-8") } catch (_: Exception) { idParam }
            if (decoded != idParam || decoded.contains("/e/") || decoded.contains("/d/")) {
                extractCode(decoded)?.let { return it }
            }
        }

        // Shape B: /e/<code> or /d/<code> — code is right after the marker
        Regex("""/(?:e|d|v)/([A-Za-z0-9]{6,})""").find(url)?.groupValues?.get(1)?.let {
            return it
        }

        // Shape C: last segment (fallback)
        val last = url.trimEnd('/').substringAfterLast('/')
        return last.takeIf { it.length in 6..24 && it.all { c -> c.isLetterOrDigit() } }
    }

    private fun b64u(s: String): ByteArray {
        var x = s.replace('-', '+').replace('_', '/')
        while (x.length % 4 != 0) x += "="
        return Base64.getDecoder().decode(x)
    }
}
