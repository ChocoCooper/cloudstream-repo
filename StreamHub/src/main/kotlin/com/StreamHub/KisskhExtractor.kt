package com.StreamHub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64DecodeArray
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import okhttp3.Interceptor
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URLEncoder
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object KisskhExtractor {
    private const val mainUrl = "https://kisskh.nl"

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
        title: String,
        seasonNum: Int,
        epNum: Int,
        isMovie: Boolean,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        try {
            // 1. Search Kisskh intelligently handling split seasons
            val searchTitle = if (isMovie || seasonNum == 1) title else "$title Season $seasonNum"
            
            var searchRes = app.get("$mainUrl/api/DramaList/Search?q=${URLEncoder.encode(searchTitle, "UTF-8")}&type=0").text
            var searchArray = tryParseJson<List<KisskhMedia>>(searchRes)
            
            // Fallback: If "Title Season X" fails, just search the root title
            if (searchArray.isNullOrEmpty() && seasonNum > 1) {
                searchRes = app.get("$mainUrl/api/DramaList/Search?q=${URLEncoder.encode(title, "UTF-8")}&type=0").text
                searchArray = tryParseJson<List<KisskhMedia>>(searchRes)
            }
            
            val kisskhId = searchArray?.firstOrNull { it.title?.contains(searchTitle, true) == true }?.id 
                ?: searchArray?.firstOrNull { it.title?.contains(title, true) == true }?.id 
                ?: searchArray?.firstOrNull()?.id ?: return false

            // 2. Get Episode ID
            val detailsRes = app.get("$mainUrl/api/DramaList/Drama/$kisskhId?isq=false").text
            val details = tryParseJson<KisskhDetail>(detailsRes) ?: return false
            val epsId = details.episodes?.find { it.number?.toInt() == epNum }?.id ?: return false

            // 3. Extract Video Links
            val kkeyJson = app.get("$mainUrl/api/Generate?id=$epsId&version=2.8.10", timeout = 10000).text
            val kkey = tryParseJson<KisskhKey>(kkeyJson)?.key ?: ""
            
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

            // 4. Extract Subtitles
            val kkey1Json = app.get("$mainUrl/api/Generate?sub=1&id=$epsId&version=2.8.10", timeout = 10000).text
            val kkey1 = tryParseJson<KisskhKey>(kkey1Json)?.key ?: ""
            
            val subRes = app.get("$mainUrl/api/Sub/$epsId?kkey=$kkey1").text
            tryParseJson<List<KisskhSubtitle>>(subRes)?.forEach { sub ->
                val lang = if (sub.label == "Indonesia") "Indonesian" else sub.label ?: "English"
                if (!sub.src.isNullOrBlank()) {
                    subtitleCallback.invoke(SubtitleFile(lang, sub.src))
                }
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }

    private val CHUNK_REGEX1 by lazy { Regex("^\\d+$", RegexOption.MULTILINE) }
    
    val subtitleInterceptor = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.request.url.toString().contains("kisskh") && response.request.url.toString().contains(".txt")) {
            val mediaType = response.body?.contentType()
            val responseBody = response.body?.string() ?: return@Interceptor response
            
            val chunks = responseBody.split(CHUNK_REGEX1).filter(String::isNotBlank).map(String::trim)
            val decrypted = chunks.mapIndexed { index, chunk ->
                if (chunk.isBlank()) return@mapIndexed ""
                val parts = chunk.split("\n")
                if (parts.isEmpty()) return@mapIndexed ""

                val header = parts.first()
                val text = parts.drop(1)
                val d = text.joinToString("\n") { line ->
                    try { decrypt(line) } catch (e: Exception) { "DECRYPT_ERROR" }
                }
                listOf(index + 1, header, d).joinToString("\n")
            }.filter { it.isNotEmpty() }.joinToString("\n\n")
            
            val newBody = decrypted.toResponseBody(mediaType)
            return@Interceptor response.newBuilder().body(newBody).build()
        }
        response
    }

    private fun decrypt(encryptedB64: String): String {
        val keyIvPairs = listOf(
            Pair(KEY.toByteArray(Charsets.UTF_8), IV.toByteArray()),
            Pair(KEY2.toByteArray(Charsets.UTF_8), IV2.toByteArray()),
            Pair(KEY3.toByteArray(Charsets.UTF_8), IV3.toByteArray())
        )
        
        // SAFE: Uses Cloudstream's native utility instead of Android SDK
        val encryptedBytes = base64DecodeArray(encryptedB64)

        for ((keyBytes, ivBytes) in keyIvPairs) {
            try {
                return decryptWithKeyIv(keyBytes, ivBytes, encryptedBytes)
            } catch (ex: Exception) { continue }
        }
        return "Decryption failed"
    }

    private fun decryptWithKeyIv(keyBytes: ByteArray, ivBytes: ByteArray, encryptedBytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
        return String(cipher.doFinal(encryptedBytes), Charsets.UTF_8)
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
