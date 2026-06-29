package com.StreamHub

import android.util.Base64
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
import org.json.JSONObject
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object KisskhExtractor {
    private const val mainUrl = "https://kisskh.co"
    private const val tmdbKey = "1865f43a0549ca50d341dd9ab8b29f49"

    // --- DECRYPTION KEYS ---
    private const val KEY = "AmSmZVcH93UQUezi"
    private const val KEY2 = "8056483646328763"
    private const val KEY3 = "sWODXX04QRTkHdlZ"
    private val IV = intArrayOf(1382367819, 1465333859, 1902406224, 1164854838)
    private val IV2 = intArrayOf(909653298, 909193779, 925905208, 892483379)
    private val IV3 = intArrayOf(946894696, 1634749029, 1127508082, 1396271183)

    // --- DATA CLASSES ---
    private data class KisskhMedia(@JsonProperty("id") val id: Int?, @JsonProperty("title") val title: String?)
    private data class KisskhDetail(@JsonProperty("episodes") val episodes: List<KisskhEpisode>?)
    private data class KisskhEpisode(@JsonProperty("id") val id: Int?, @JsonProperty("number") val number: Double?)
    private data class KisskhKey(val key: String?)
    private data class KisskhSources(@JsonProperty("Video") val video: String?, @JsonProperty("ThirdParty") val thirdParty: String?)
    private data class KisskhSubtitle(@JsonProperty("src") val src: String?, @JsonProperty("label") val label: String?)

    suspend fun getStream(
        dataUrl: String,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        try {
            val cleanData = dataUrl.substringBefore("?")
            val isMovie = cleanData.contains("/movie/")
            val tmdbId = Regex("""/(?:movie|tv)/(\d+)""").find(cleanData)?.groupValues?.get(1) ?: return false
            val epNum = Regex("""/tv/\d+/\d+/(\d+)""").find(cleanData)?.groupValues?.get(1)?.toIntOrNull() ?: 1

            // 1. Get real title from TMDB
            val tmdbUrl = if (isMovie) "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$tmdbKey" else "https://api.themoviedb.org/3/tv/$tmdbId?api_key=$tmdbKey"
            val tmdbRes = JSONObject(app.get(tmdbUrl).text)
            val title = tmdbRes.optString("name").takeIf { it.isNotBlank() } ?: tmdbRes.optString("title")

            // 2. Search Kisskh for Title
            val encodedQuery = URLEncoder.encode(title, "UTF-8")
            val searchRes = app.get("$mainUrl/api/DramaList/Search?q=$encodedQuery&type=0").text
            val searchArray = tryParseJson<List<KisskhMedia>>(searchRes) ?: return false
            
            // Get the ID of the first matched result
            val kisskhId = searchArray.firstOrNull { it.title?.contains(title, true) == true }?.id ?: searchArray.firstOrNull()?.id ?: return false

            // 3. Get Episode ID
            val detailsRes = app.get("$mainUrl/api/DramaList/Drama/$kisskhId?isq=false").text
            val details = tryParseJson<KisskhDetail>(detailsRes) ?: return false
            val epsId = details.episodes?.find { it.number?.toInt() == epNum }?.id ?: return false

            // 4. Extract Video Links
            val kkey = app.get("$mainUrl/api/Generate?id=$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
            val sourceRes = app.get("$mainUrl/api/DramaList/Episode/$epsId.png?err=false&ts=&time=&kkey=$kkey").text
            val sources = tryParseJson<KisskhSources>(sourceRes) ?: return false

            listOfNotNull(sources.video, sources.thirdParty).forEach { link ->
                if (link.contains(".m3u8")) {
                    M3u8Helper.generateM3u8("Kisskh", link, referer = "$mainUrl/", headers = mapOf("Origin" to mainUrl)).forEach(callback)
                } else if (link.contains("mp4")) {
                    callback.invoke(ExtractorLink("Kisskh", "Kisskh", link, referer = mainUrl, quality = Qualities.Unknown.value, type = ExtractorLinkType.VIDEO))
                } else {
                    loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
                }
            }

            // 5. Extract Subtitles
            val kkey1 = app.get("$mainUrl/api/Generate?sub=1&id=$epsId&version=2.8.10").parsedSafe<KisskhKey>()?.key ?: ""
            val subRes = app.get("$mainUrl/api/Sub/$epsId?kkey=$kkey1").text
            tryParseJson<List<KisskhSubtitle>>(subRes)?.forEach { sub ->
                val lang = if (sub.label == "Indonesia") "Indonesian" else sub.label ?: ""
                if (sub.src != null) {
                    subtitleCallback.invoke(SubtitleFile(lang, sub.src))
                }
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }

    // --- SUBTITLE DECRYPTION INTERCEPTOR ---
    // Cloudstream ExoPlayer will pass downloaded subtitle files through this to decrypt them
    val subtitleInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        if (request.url.toString().contains(".txt")) {
            val mediaType = response.body?.contentType()
            val responseBody = response.body?.string() ?: return@Interceptor response
            val chunkRegex = Regex("^\\d+$", RegexOption.MULTILINE)

            val chunks = responseBody.split(chunkRegex).filter(String::isNotBlank).map(String::trim)

            val decrypted = chunks.mapIndexed { index, chunk ->
                if (chunk.isBlank()) return@mapIndexed ""
                val parts = chunk.split("\n")
                if (parts.isEmpty()) return@mapIndexed ""

                val header = parts.first()
                val text = parts.drop(1)
                val d = text.joinToString("\n") { line ->
                    try {
                        decrypt(line)
                    } catch (e: Exception) {
                        "DECRYPT_ERROR"
                    }
                }
                listOf(index + 1, header, d).joinToString("\n")
            }.filter { it.isNotEmpty() }.joinToString("\n\n")

            return@Interceptor response.newBuilder().body(decrypted.toResponseBody(mediaType)).build()
        }
        response
    }

    private fun decrypt(encryptedB64: String): String {
        val keyIvPairs = listOf(
            Pair(KEY.toByteArray(Charsets.UTF_8), IV.toByteArray()),
            Pair(KEY2.toByteArray(Charsets.UTF_8), IV2.toByteArray()),
            Pair(KEY3.toByteArray(Charsets.UTF_8), IV3.toByteArray())
        )
        val encryptedBytes = Base64.decode(encryptedB64, Base64.DEFAULT)

        for ((keyBytes, ivBytes) in keyIvPairs) {
            try {
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
                return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
            } catch (ex: Exception) { continue }
        }
        return "Decryption failed"
    }

    private fun IntArray.toByteArray(): ByteArray {
        return ByteArray(size * 4).also { bytes ->
            forEachIndexed { index, value ->
                bytes[index * 4] = (value shr 24).toByte()
                bytes[index * 4 + 1] = (value shr 16).toByte()
                bytes[index * 4 + 2] = (value shr 8).toByte()
                bytes[index * 4 + 3] = value.toByte()
            }
        }
    }
}
