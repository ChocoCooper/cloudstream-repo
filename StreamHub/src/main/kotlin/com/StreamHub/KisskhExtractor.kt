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
import com.lagradost.cloudstream3.utils.newSubtitleFile
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

    // ── Decryption keys ───────────────────────────────────────────────────────
    private const val KEY  = "AmSmZVcH93UQUezi"
    private const val KEY2 = "8056483646328763"
    private const val KEY3 = "sWODXX04QRTkHdlZ"

    private val IV  = intArrayOf(1382367819, 1465333859, 1902406224, 1164854838)
    private val IV2 = intArrayOf(909653298,  909193779,  925905208,  892483379)
    private val IV3 = intArrayOf(946894696,  1634749029, 1127508082, 1396271183)

    // ── Data classes (using @param:JsonProperty per reference style) ──────────
    private data class KisskhResults(
        @param:JsonProperty("id")    val id: Int?,
        @param:JsonProperty("title") val title: String?,
    )
    private data class KisskhDetail(
        @param:JsonProperty("episodes") val episodes: ArrayList<KisskhEpisodes>? = arrayListOf(),
    )
    private data class KisskhEpisodes(
        @param:JsonProperty("id")     val id: Int?,
        @param:JsonProperty("number") val number: Int?,   // Int, not Double
    )
    private data class KisskhKey(
        val id: String? = null,
        val version: String? = null,
        val key: String? = null,
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
    /**
     * @param title      Show/movie title from TMDB
     * @param season     Season number (null for movies)
     * @param episode    Episode number (null for movies)
     * @param lastSeason Total season count — used to decide whether to match
     *                   by "Title Season N" or just by slug alone (single-season shows)
     */
    suspend fun getStream(
        title: String,
        season: Int?,
        episode: Int?,
        lastSeason: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val slug = title.createSlug() ?: return false
            // type=2 → movie, type=1 → series (reference uses this distinction)
            val type = if (season == null) "2" else "1"

            // 1. Search ────────────────────────────────────────────────────────
            val searchRes = app.get(
                "$mainUrl/api/DramaList/Search?q=${title}&type=$type",
                referer = "$mainUrl/"
            )
            if (searchRes.code != 200) return false

            val results = tryParseJson<ArrayList<KisskhResults>>(searchRes.text) ?: return false
            if (results.isEmpty()) return false

            // Smart match — mirrors reference logic exactly
            val (id, contentTitle) = if (results.size == 1) {
                results.first().id to results.first().title
            } else {
                val match = results.find {
                    val slugTitle = it.title.createSlug() ?: return@find false
                    when {
                        season == null  -> slugTitle == slug
                        lastSeason == 1 -> slugTitle.contains(slug)
                        else            -> slugTitle.contains(slug) &&
                                           it.title?.contains("Season $season", ignoreCase = true) == true
                    }
                } ?: results.find { it.title.equals(title, ignoreCase = true) }
                match?.id to match?.title
            }

            if (id == null) return false

            // 2. Detail fetch (with proper Kisskh referer) ────────────────────
            val detailRes = app.get(
                "$mainUrl/api/DramaList/Drama/$id?isq=false",
                referer = "$mainUrl/Drama/${getKisskhTitle(contentTitle)}?id=$id"
            )
            if (detailRes.code != 200) return false

            val detail = detailRes.parsedSafe<KisskhDetail>() ?: return false
            val epsId  = if (season == null) {
                detail.episodes?.firstOrNull()?.id
            } else {
                detail.episodes?.find { it.number == episode }?.id
            } ?: return false

            // 3. Fetch kkeys in parallel (null kkey = fatal, per reference) ───
            val (kkey, kkey1) = coroutineScope {
                val videoKey = async {
                    try {
                        app.get(
                            "$mainUrl/api/Generate?id=$epsId&version=2.8.10",
                            timeout = 10000
                        ).parsedSafe<KisskhKey>()?.key
                    } catch (_: Exception) { null }
                }
                val subKey = async {
                    try {
                        app.get(
                            "$mainUrl/api/Generate?sub=1&id=$epsId&version=2.8.10",
                            timeout = 10000
                        ).parsedSafe<KisskhKey>()?.key
                    } catch (_: Exception) { null }
                }
                videoKey.await() to subKey.await()
            }

            if (kkey == null || kkey1 == null) return false

            // 4. Fetch sources and subtitles in parallel ───────────────────────
            val (sourcesData, subResponse) = coroutineScope {
                val sources = async {
                    try {
                        app.get(
                            "$mainUrl/api/DramaList/Episode/$epsId.png?err=false&ts=&time=&kkey=$kkey",
                            referer = "$mainUrl/Drama/${getKisskhTitle(contentTitle)}/Episode-${episode ?: 0}?id=$id&ep=$epsId&page=0&pageSize=100"
                        ).parsedSafe<KisskhSources>()
                    } catch (_: Exception) { null }
                }
                // Note: reference uses & not ? before kkey in the sub URL
                val subs = async {
                    try {
                        tryParseJson<List<KisskhSubtitle>>(
                            app.get("$mainUrl/api/Sub/$epsId&kkey=$kkey1").text
                        )
                    } catch (_: Exception) { null }
                }
                sources.await() to subs.await()
            }

            // 5. Deliver video links ───────────────────────────────────────────
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
                                    quality = Qualities.P720.value
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

            // 6. Deliver subtitles ─────────────────────────────────────────────
            subResponse?.forEach { sub ->
                val lang = getLanguage(sub.label ?: "Unknown")
                subtitleCallback.invoke(newSubtitleFile(lang, sub.src ?: return@forEach))
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }

    // ── Helpers (mirror reference) ────────────────────────────────────────────

    /** Converts a drama title to its URL-slug form used in Kisskh referer paths. */
    private fun getKisskhTitle(str: String?): String? =
        str?.replace(Regex("[^a-zA-Z\\d]"), "-")

    /**
     * Produces a lowercase hyphenated slug from a title for fuzzy matching.
     * e.g. "Twinkling Watermelon" → "twinkling-watermelon"
     */
    private fun String?.createSlug(): String? {
        return this?.lowercase()
            ?.replace(Regex("[^a-z0-9\\s]"), "")
            ?.trim()
            ?.replace(Regex("\\s+"), "-")
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Expands common subtitle label strings to a clean display language name.
     * Mirrors the reference's getLanguage() behaviour.
     */
    private fun getLanguage(label: String): String {
        return when (label.lowercase().trim()) {
            "indonesia", "indonesian" -> "Indonesian"
            "english", "en"           -> "English"
            "korean", "ko"            -> "Korean"
            "chinese", "zh", "chi"    -> "Chinese"
            "japanese", "ja", "jpn"   -> "Japanese"
            "thai", "th"              -> "Thai"
            "vietnamese", "vi"        -> "Vietnamese"
            "arabic", "ar"            -> "Arabic"
            "spanish", "es"           -> "Spanish"
            "french", "fr"            -> "French"
            "german", "de"            -> "German"
            "portuguese", "pt"        -> "Portuguese"
            "russian", "ru"           -> "Russian"
            "hindi", "hi"             -> "Hindi"
            "tamil", "ta"             -> "Tamil"
            "malay", "ms"             -> "Malay"
            "unknown", ""             -> "Unknown"
            else                      -> label.replaceFirstChar { it.uppercase() }
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

    /**
     * Convert an IntArray of big-endian 32-bit integers into a raw ByteArray.
     * Named toKisskhBytes to avoid ambiguity with Kotlin stdlib extensions.
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
