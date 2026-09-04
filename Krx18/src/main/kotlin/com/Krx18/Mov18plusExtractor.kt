package com.KRX18

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.*
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Mov18plusExtractor : ExtractorApi() {
    override val name = "Mov18plus"
    override val mainUrl = "https://mov18plus.cloud"
    override val requiresReferer = true

    data class Mov18Payload(
        @JsonProperty("slug") val slug: String,
        @JsonProperty("md5_id") val md5Id: Any,
        @JsonProperty("user_id") val userId: Any,
        @JsonProperty("media") val media: String? = null
    )

    data class Mov18Source(
        @JsonProperty("label") val label: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("path") val path: String?,
        @JsonProperty("status") val status: Boolean? = true,
        @JsonProperty("sub") val sub: String? = null
    )

    data class Mov18MediaContainer(
        @JsonProperty("sources") val sources: List<Mov18Source>? = emptyList()
    )

    data class Mov18DecryptedResponse(
        @JsonProperty("mp4") val mp4: Mov18MediaContainer? = null
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer ?: "$mainUrl/").text

        // 1. Extract Base64 `datas` token
        val datasRegex = Regex("""(?:const|var)\s+datas\s*=\s*["']([^"']+)["']""")
        val rawBase64 = datasRegex.find(document)?.groupValues?.get(1) ?: return

        // 2. Decode using Latin-1 (ISO-8859-1) to preserve ciphertext bytes!
        val jsonPayloadString = String(Base64.decode(rawBase64, Base64.DEFAULT), StandardCharsets.ISO_8859_1)
        val payload = parseJson<Mov18Payload>(jsonPayloadString)
        val mediaStr = payload.media ?: return

        // 3. Build the exact key pattern we brute-forced
        val keyPattern = "${payload.userId}:${payload.slug}:${payload.md5Id}"

        // 4. Generate MD5 Hex digest of the key pattern
        val md5Digest = MessageDigest.getInstance("MD5").digest(keyPattern.toByteArray(StandardCharsets.UTF_8))
        val md5Hex = md5Digest.joinToString("") { "%02x".format(it) }

        // 5. Initialize AES-CTR (NoPadding)
        val keyBytes = md5Hex.toByteArray(StandardCharsets.UTF_8)
        val ivBytes = keyBytes.copyOfRange(0, 16)

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))

        // 6. Convert media string back to unsigned byte array safely
        val mediaBytes = ByteArray(mediaStr.length) { i -> (mediaStr[i].code and 0xFF).toByte() }

        // 7. Decrypt ciphertext into UTF-8 JSON
        val decryptedJson = String(cipher.doFinal(mediaBytes), StandardCharsets.UTF_8)
        val mediaData = parseJson<Mov18DecryptedResponse>(decryptedJson)

        val headers = mapOf("Referer" to url)

        // 8. Yield the stream links
        mediaData.mp4?.sources?.forEach { source ->
            if (source.status == true && !source.path.isNullOrBlank()) {
                val streamUrl = if (!source.url.isNullOrBlank()) {
                    "${source.url.trimEnd('/')}/${source.path.trimStart('/')}"
                } else if (!source.sub.isNullOrBlank()) {
                    "https://${source.sub}.sssrr.org/${source.path.trimStart('/')}"
                } else null

                if (streamUrl != null) {
                    val qualityInt = when (source.label?.lowercase()) {
                        "480p" -> Qualities.P480.value
                        "720p" -> Qualities.P720.value
                        "1080p" -> Qualities.P1080.value
                        else -> Qualities.Unknown.value
                    }

                    // Fixed: Explicitly declare the enum type based on the file extension
                    val linkType = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "$name ${source.label ?: "MP4"}",
                            url = streamUrl,
                            referer = url,
                            quality = qualityInt,
                            type = linkType,
                            headers = headers
                        )
                    )
                }
            }
        }
    }
}
