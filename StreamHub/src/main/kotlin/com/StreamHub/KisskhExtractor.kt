package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object KisskhExtractor {
    private const val mainUrl = "https://kisskh.nl"
    private const val encDecApi = "https://enc-dec.app/api/enc-kisskh"
    // Anti-timeout user agent exactly mimicking Python's requests session
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"

    // ── Decryption keys (Used to decrypt internal subtitle txt content) ───────
    private const val KEY  = "AmSmZVcH93UQUezi"
    private const val KEY2 = "8056483646328763"
    private const val KEY3 = "sWODXX04QRTkHdlZ"

    private val IV  = intArrayOf(1382367819, 1465333859, 1902406224, 1164854838)
    private val IV2 = intArrayOf(909653298,  909193779,  925905208,  892483379)
    private val IV3 = intArrayOf(946894696,  1634749029, 1127508082, 1396271183)

    // ── Data classes ──────────
    private data class KisskhResults(
        @param:JsonProperty("id")    val id: Int?,
        @param:JsonProperty("title") val title: String?,
    )
    private data class KisskhDetail(
        @param:JsonProperty("episodes") val episodes: ArrayList<KisskhEpisodes>? = arrayListOf(),
    )
    private data class KisskhEpisodes(
        @param:JsonProperty("id")     val id: Int?,
        @param:JsonProperty("number") val number: Double?, // Changed to Double to safely parse "1.5" formats
    )
    private data class KisskhSources(
        @param:JsonProperty("Video")      val video: String?,
        @param:JsonProperty("ThirdParty") val thirdParty: String?,
    )
    private data class KisskhSubtitle(
        @param:JsonProperty("src")   val src: String?,
        @param:JsonProperty("label") val label: String?,
    )

    // ── Main entry point ──────────────────────────────────────────────────────
    suspend fun getStream(
        title: String,
        year: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val slug = title.createSlug() ?: return false
            val type = if (season == null) "2" else "1"

            // 1. Search Kisskh (Using safe params map to properly format URL natively in OkHttp)
            val searchResText = app.get(
                "$mainUrl/api/DramaList/Search",
                params = mapOf("q" to title, "type" to type),
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$mainUrl/"
            ).text

            val searchRes = tryParseJson<List<KisskhResults>>(searchResText) ?: return false
            if (searchRes.isEmpty()) return false

            // 2. Match result (Ported exactly from python s.py match_result) ───
            var matchedId: Int? = null
            var matchedTitle: String? = null

            if (searchRes.size == 1) {
                matchedId = searchRes.first().id
                matchedTitle = searchRes.first().title
            } else {
                val match = searchRes.find { item ->
                    val actualTitle = item.title ?: return@find false
                    val tSlug = actualTitle.createSlug() ?: return@find false
                    
                    if (season == null) {
                        tSlug == slug
                    } else if (season == 1) {
                        tSlug == slug || (tSlug.contains(slug) && (year != null && actualTitle.contains(year) || actualTitle.contains("season 1", ignoreCase = true)))
                    } else {
                        tSlug.contains(slug) && actualTitle.contains("season $season", ignoreCase = true)
                    }
                } ?: searchRes.find { it.title.equals(title, ignoreCase = true) }

                matchedId = match?.id
                matchedTitle = match?.title
            }

            if (matchedId == null) return false

            // 3. Detail fetch ──────────────────────────────────────────────────
            val detailResText = app.get(
                "$mainUrl/api/DramaList/Drama/$matchedId",
                params = mapOf("isq" to "false"),
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$mainUrl/Drama/${getKisskhTitle(matchedTitle)}?id=$matchedId"
            ).text

            val detailRes = tryParseJson<KisskhDetail>(detailResText) ?: return false

            val epsId  = if (season == null) {
                detailRes.episodes?.firstOrNull()?.id
            } else {
                detailRes.episodes?.find { it.number?.toInt() == episode }?.id
            } ?: return false

            // 4. Fetch kkeys in parallel using Python decryption API solution ──
            val (kkeyVid, kkeySub) = coroutineScope {
                val videoKeyJob = async {
                    try {
                        val res = app.get(
                            encDecApi,
                            params = mapOf("text" to epsId.toString(), "type" to "vid"),
                            headers = mapOf("User-Agent" to USER_AGENT),
                            referer = "https://kisskh.nl"
                        ).text
                        tryParseJson<Map<String, String>>(res)?.get("result")
                    } catch (e: Exception) { null }
                }
                val subKeyJob = async {
                    try {
                        val res = app.get(
                            encDecApi,
                            params = mapOf("text" to epsId.toString(), "type" to "sub"),
                            headers = mapOf("User-Agent" to USER_AGENT),
                            referer = "https://kisskh.nl"
                        ).text
                        tryParseJson<Map<String, String>>(res)?.get("result")
                    } catch (e: Exception) { null }
                }
                videoKeyJob.await() to subKeyJob.await()
            }

            if (kkeyVid == null) return false

            // 5. Fetch sources and subtitles in parallel ───────────────────────
            val (sourcesData, subResponse) = coroutineScope {
                val sourcesJob = async {
                    try {
                        val res = app.get(
                            "$mainUrl/api/DramaList/Episode/$epsId.png",
                            params = mapOf("err" to "false", "ts" to "", "time" to "", "kkey" to kkeyVid),
                            headers = mapOf("User-Agent" to USER_AGENT),
                            referer = mainUrl
                        ).text
                        tryParseJson<KisskhSources>(res)
                    } catch (e: Exception) { null }
                }
                val subsJob = async {
                    if (kkeySub != null) {
                        try {
                            val res = app.get(
                                "$mainUrl/api/Sub/$epsId",
                                params = mapOf("kkey" to kkeySub),
                                headers = mapOf("User-Agent" to USER_AGENT),
                                referer = mainUrl
                            ).text
                            tryParseJson<List<KisskhSubtitle>>(res)
                        } catch (e: Exception) { null }
                    } else null
                }
                sourcesJob.await() to subsJob.await()
            }

            // 6. Deliver video links ───────────────────────────────────────────
            sourcesData?.let { src ->
                listOf(src.video, src.thirdParty).forEach { link ->
                    val safeLink = link ?: return@forEach
                    when {
                        safeLink.contains(".m3u8") || safeLink.contains(".mp4") -> {
                            val safe = safeLink.takeIf { it.startsWith("http") } ?: return@forEach
                            callback.invoke(
                                newExtractorLink(
                                    "Kisskh",
                                    "Kisskh",
                                    safe,
                                    INFER_TYPE
                                ) {
                                    referer = mainUrl
                                    quality = Qualities.Unknown.value
                                    headers = mapOf("Origin" to mainUrl)
                                }
                            )
                        }
                        else -> {
                            val cleanedLink = safeLink.substringBefore("?")
                                .takeIf { it.isNotBlank() } ?: return@forEach
                            loadExtractor(
                                cleanedLink,
                                "$mainUrl/",
                                subtitleCallback,
                                callback
                            )
                        }
                    }
                }
            }

            // 7. Deliver subtitles ─────────────────────────────────────────────
            subResponse?.forEach { sub ->
                val lang = getLanguage(sub.label ?: "Unknown")
                val src = sub.src ?: return@forEach
                // Mapped subtitle callback in Provider will filter for "English" automatically
                subtitleCallback.invoke(SubtitleFile(lang, src))
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // ── Helpers ────────────────────────────────────────────

    private fun getKisskhTitle(str: String?): String? =
        str?.replace(Regex("[^a-zA-Z\\d]"), "-")

    private fun String?.createSlug(): String? {
        return this?.lowercase()
            ?.replace(Regex("[^a-z0-9\\s]"), "")
            ?.trim()
            ?.replace(Regex("\\s+"), "-")
            ?.takeIf { it.isNotBlank() }
    }

    private fun getLanguage(label: String): String {
        return when (label.lowercase().trim()) {
            "english", "en" -> "English"
            else -> label.replaceFirstChar { it.uppercase() }
        }
    }

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
                    try { decrypt(line) } catch (_: Exception) { "" }
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
