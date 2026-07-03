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
import java.net.URLEncoder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object KisskhExtractor {
    // Made a var so it can be updated easily if the domain changes
    var mainUrl = "https://kisskh.nl"
    private const val encDecApi = "https://enc-dec.app/api/enc-kisskh"
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
        @param:JsonProperty("number") val number: Double?, 
    )
    private data class EncDecResponse(
        @param:JsonProperty("result") val result: String?,
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
            val encodedTitle = URLEncoder.encode(title, "UTF-8")

            // 1. Search Kisskh
            val searchResText = app.get(
                "$mainUrl/api/DramaList/Search?q=$encodedTitle&type=$type",
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$mainUrl/",
                timeout = 15L
            ).text

            val searchRes = tryParseJson<List<KisskhResults>>(searchResText) ?: return false
            if (searchRes.isEmpty()) return false

            // 2. Match result (Hardened prioritizing year match over title short-circuiting)
            var matchedId: Int? = null
            var matchedTitle: String? = null

            if (searchRes.size == 1) {
                matchedId = searchRes.first().id
                matchedTitle = searchRes.first().title
            } else {
                var exactYearMatch: KisskhResults? = null
                var fallbackTitleMatch: KisskhResults? = null

                for (item in searchRes) {
                    val actualTitle = item.title ?: continue
                    val tSlug = actualTitle.createSlug() ?: continue
                    
                    // Check if the base titles align
                    val isBaseTitleMatch = tSlug == slug || tSlug.contains(slug) || slug.contains(tSlug)

                    if (isBaseTitleMatch) {
                        // 1. Highest Priority: If we have a year and this entry contains it, lock it immediately
                        if (year != null && actualTitle.contains(year)) {
                            exactYearMatch = item
                            break
                        }

                        // 2. Secondary Priority: Season specific structural checks
                        if (season == null) {
                            if (fallbackTitleMatch == null) fallbackTitleMatch = item
                        } else if (season == 1) {
                            if (actualTitle.contains("season 1", ignoreCase = true) || fallbackTitleMatch == null) {
                                fallbackTitleMatch = item
                            }
                        } else {
                            if (actualTitle.contains("season $season", ignoreCase = true)) {
                                fallbackTitleMatch = item
                            }
                        }
                    }
                }

                // Lock the strict year match first, otherwise drop back to title matching
                val match = exactYearMatch ?: fallbackMatch ?: searchRes.find { it.title.equals(title, ignoreCase = true) }
                matchedId = match?.id
                matchedTitle = match?.title
            }

            if (matchedId == null) return false

            // 3. Detail fetch
            val detailResText = app.get(
                "$mainUrl/api/DramaList/Drama/$matchedId",
                params = mapOf("isq" to "false"),
                headers = mapOf("User-Agent" to USER_AGENT),
                referer = "$mainUrl/Drama/${getKisskhTitle(matchedTitle)}?id=$matchedId",
                timeout = 15L
            ).text
            
            val detailRes = tryParseJson<KisskhDetail>(detailResText) ?: return false

            val epsId  = if (season == null) {
                detailRes.episodes?.firstOrNull()?.id
            } else {
                detailRes.episodes?.find { it.number?.toInt() == episode }?.id
            } ?: return false

            // 4. Fetch kkeys in parallel using Python decryption API solution
            val (kkeyVid, kkeySub) = coroutineScope {
                val videoKey = async {
                    try {
                        app.get(
                            encDecApi,
                            params = mapOf("text" to epsId.toString(), "type" to "vid"),
                            headers = mapOf("User-Agent" to USER_AGENT),
                            referer = "$mainUrl/",
                            timeout = 15L
                        ).parsedSafe<EncDecResponse>()?.result
                    } catch (_: Exception) { null }
                }
                val subKey = async {
                    try {
                        app.get(
                            encDecApi,
                            params = mapOf("text" to epsId.toString(), "type" to "sub"),
                            headers = mapOf("User-Agent" to USER_AGENT),
                            referer = "$mainUrl/",
                            timeout = 15L
                        ).parsedSafe<EncDecResponse>()?.result
                    } catch (_: Exception) { null }
                }
                videoKey.await() to subKey.await()
            }

            if (kkeyVid == null) return false

            // 5. Fetch sources and subtitles in parallel
            val encodedKkeyVid = URLEncoder.encode(kkeyVid, "UTF-8")
            val (sourcesData, subResponse) = coroutineScope {
                val sources = async {
                    try {
                        app.get(
                            "$mainUrl/api/DramaList/Episode/$epsId.png?err=false&ts=&time=&kkey=$encodedKkeyVid",
                            headers = mapOf("User-Agent" to USER_AGENT),
                            referer = "$mainUrl/",
                            timeout = 15L
                        ).parsedSafe<KisskhSources>()
                    } catch (_: Exception) { null }
                }
                val subs = async {
                    if (kkeySub != null) {
                        try {
                            val encodedKkeySub = URLEncoder.encode(kkeySub, "UTF-8")
                            app.get(
                                "$mainUrl/api/Sub/$epsId?kkey=$encodedKkeySub",
                                headers = mapOf("User-Agent" to USER_AGENT),
                                referer = "$mainUrl/",
                                timeout = 15L
                            ).parsedSafe<List<KisskhSubtitle>>()
                        } catch (_: Exception) { null }
                    } else null
                }
                sources.await() to subs.await()
            }

            // 6. Deliver video links
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

            // 7. Deliver subtitles
            subResponse?.forEach { sub ->
                val lang = getLanguage(sub.label ?: "Unknown")
                val src = sub.src ?: return@forEach
                subtitleCallback.invoke(SubtitleFile(lang, src))
            }

            return true
        } catch (e: Exception) {
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
        val domainName = mainUrl.substringAfter("://").substringBefore("/")

        // Ensures the interceptor targets the dynamic mainUrl host
        if (url.contains(domainName) && url.contains(".txt")) {
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
