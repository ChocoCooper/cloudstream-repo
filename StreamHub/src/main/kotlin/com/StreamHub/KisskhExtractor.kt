package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object KisskhExtractor {
    private const val mainUrl = "https://kisskh.nl"

    // ── Decryption keys ───────────────────────────────────────────────────────
    private const val KEY  = "AmSmZVcH93UQUezi"
    private const val KEY2 = "8056483646328763"
    private const val KEY3 = "sWODXX04QRTkHdlZ"

    private val IV  = intArrayOf(1382367819, 1465333859, 1902406224, 1164854838)
    private val IV2 = intArrayOf(909653298,  909193779,  925905208,  892483379)
    private val IV3 = intArrayOf(946894696,  1634749029, 1127508082, 1396271183)

    // ── Data classes ──────────────────────────────────────────────────────────
    private data class KisskhMedia(
        @JsonProperty("id")    val id: Int?,
        @JsonProperty("title") val title: String?
    )
    private data class KisskhDetail(
        @JsonProperty("episodes") val episodes: List<KisskhEpisode>?
    )
    private data class KisskhEpisode(
        @JsonProperty("id")     val id: Int?,
        @JsonProperty("number") val number: Double?
    )
    private data class KisskhKey(val key: String?)
    private data class KisskhSources(
        @JsonProperty("Video")      val video: String?,
        @JsonProperty("ThirdParty") val thirdParty: String?
    )
    private data class KisskhSubtitle(
        @JsonProperty("src")   val src: String?,
        @JsonProperty("label") val label: String?
    )

    // ── Main entry point ──────────────────────────────────────────────────────
    suspend fun getStream(
        title: String,
        seasonNum: Int,
        epNum: Int,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        try {
            // 1. Search — try "Title Season X" first, fall back to bare title
            val searchTitle = if (isMovie || seasonNum == 1) title else "$title Season $seasonNum"
            var searchArray = searchKisskh(searchTitle)

            if (searchArray.isNullOrEmpty() && seasonNum > 1)
                searchArray = searchKisskh(title)

            val kisskhId = searchArray
                ?.firstOrNull { it.title?.contains(searchTitle, ignoreCase = true) == true }?.id
                ?: searchArray?.firstOrNull { it.title?.contains(title, ignoreCase = true) == true }?.id
                ?: searchArray?.firstOrNull()?.id
                ?: return false

            // 2. Get episode ID
            val detailsRes = app.get("$mainUrl/api/DramaList/Drama/$kisskhId?isq=false").text
            val details    = tryParseJson<KisskhDetail>(detailsRes) ?: return false
            val epsId      = details.episodes?.find { it.number?.toInt() == epNum }?.id ?: return false

            // 3. Fetch video sources
            //    kkey is optional; an empty string is fine if the API doesn't return one
            val kkey = fetchKey(epsId)
            val sourceRes = app.get(
                "$mainUrl/api/DramaList/Episode/$epsId.png?err=false&ts=&time=&kkey=$kkey"
            ).text
            val sources = tryParseJson<KisskhSources>(sourceRes) ?: return false

            listOfNotNull(sources.video, sources.thirdParty).forEach { link ->
                when {
                    link.contains(".m3u8") ->
                        M3u8Helper.generateM3u8(
                            "Kisskh", link,
                            referer = "$mainUrl/",
                            headers = mapOf("Origin" to mainUrl)
                        ).forEach(callback)
                    link.contains("mp4") ->
                        callback.invoke(
                            ExtractorLink(
                                "Kisskh", "Kisskh", link,
                                referer = mainUrl,
                                quality = Qualities.Unknown.value,
                                type    = ExtractorLinkType.VIDEO
                            )
                        )
                    else ->
                        loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
                }
            }

            // 4. Fetch subtitles
            val kkey1    = fetchSubKey(epsId)
            val subRes   = app.get("$mainUrl/api/Sub/$epsId?kkey=$kkey1").text
            tryParseJson<List<KisskhSubtitle>>(subRes)?.forEach { sub ->
                val lang = when (sub.label) {
                    "Indonesia"  -> "Indonesian"
                    null, ""     -> "English"
                    else         -> sub.label
                }
                if (!sub.src.isNullOrBlank())
                    subtitleCallback.invoke(SubtitleFile(lang, sub.src))
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private suspend fun searchKisskh(query: String): List<KisskhMedia>? {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val res     = app.get("$mainUrl/api/DramaList/Search?q=$encoded&type=0").text
        return tryParseJson<List<KisskhMedia>>(res)
    }

    /** Fetch the video kkey; returns empty string on failure (API still works). */
    private suspend fun fetchKey(epsId: Int): String = try {
        tryParseJson<KisskhKey>(
            app.get("$mainUrl/api/Generate?id=$epsId&version=2.8.10", timeout = 12000).text
        )?.key ?: ""
    } catch (e: Exception) { "" }

    /** Fetch the subtitle kkey; returns empty string on failure. */
    private suspend fun fetchSubKey(epsId: Int): String = try {
        tryParseJson<KisskhKey>(
            app.get("$mainUrl/api/Generate?sub=1&id=$epsId&version=2.8.10", timeout = 12000).text
        )?.key ?: ""
    } catch (e: Exception) { "" }

    // ── Subtitle interceptor (decrypts encrypted .txt subtitle lines) ─────────
    private val CHUNK_REGEX by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }

    val subtitleInterceptor = Interceptor { chain ->
        val request  = chain.request()
        val response = chain.proceed(request)
        val url      = response.request.url.toString()

        if (url.contains("kisskh") && url.contains(".txt")) {
            val mediaType    = response.body?.contentType()
            val responseBody = response.body?.string() ?: return@Interceptor response

            val chunks    = responseBody.split(CHUNK_REGEX).filter(String::isNotBlank).map(String::trim)
            val decrypted = chunks.mapIndexed { index, chunk ->
                if (chunk.isBlank()) return@mapIndexed ""
                val parts  = chunk.split("\n")
                if (parts.isEmpty()) return@mapIndexed ""
                val header = parts.first()
                val lines  = parts.drop(1)
                val body   = lines.joinToString("\n") { line ->
                    try { decrypt(line) } catch (e: Exception) { "" }
                }
                listOf(index + 1, header, body).joinToString("\n")
            }.filter { it.isNotEmpty() }.joinToString("\n\n")

            return@Interceptor response.newBuilder()
                .body(decrypted.toResponseBody(mediaType))
                .build()
        }
        response
    }

    // ── AES/CBC decryption ────────────────────────────────────────────────────

    private fun decrypt(encryptedB64: String): String {
        // Use java.util.Base64 — available on all Android API 26+ and on JVM
        val encryptedBytes = Base64.getDecoder().decode(encryptedB64)

        val keyIvPairs = listOf(
            KEY.toByteArray(Charsets.UTF_8)  to IV.toKisskhBytes(),
            KEY2.toByteArray(Charsets.UTF_8) to IV2.toKisskhBytes(),
            KEY3.toByteArray(Charsets.UTF_8) to IV3.toKisskhBytes()
        )

        for ((keyBytes, ivBytes) in keyIvPairs) {
            try {
                return decryptAesCbc(keyBytes, ivBytes, encryptedBytes)
            } catch (_: Exception) { continue }
        }
        return ""
    }

    private fun decryptAesCbc(key: ByteArray, iv: ByteArray, data: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }

    /**
     * Convert an IntArray of big-endian 32-bit integers into a raw ByteArray.
     * Named [toKisskhBytes] to avoid any ambiguity with Kotlin stdlib extensions.
     */
    private fun IntArray.toKisskhBytes(): ByteArray =
        ByteArray(size * 4).also { bytes ->
            forEachIndexed { i, v ->
                bytes[i * 4 + 0] = (v shr 24).toByte()
                bytes[i * 4 + 1] = (v shr 16).toByte()
                bytes[i * 4 + 2] = (v shr 8).toByte()
                bytes[i * 4 + 3] = v.toByte()
            }
        }
}
