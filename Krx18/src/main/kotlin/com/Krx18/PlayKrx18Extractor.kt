package com.KRX18

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import okhttp3.FormBody
import okhttp3.RequestBody
import org.json.JSONObject
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PlayKrx18Extractor : ExtractorApi() {
    override val name = "PlayKrx18"
    override val mainUrl = "https://play.playkrx18.site"
    override val requiresReferer = true
    private val domainApi = "https://api-play-240924.playkrx18.site/api/tp1rd"

    @Suppress("DEPRECATION")
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url.ifBlank { mainUrl }, referer = referer)
            val html = res.document.data()

            val regex = Regex("const\\s*id(?:User|file)_enc\\s*=\\s*\"(.*?)\"")
            val matches = regex.findAll(html).map { it.groupValues[1] }.toList()
            if (matches.size < 2) return

            val encryptedFileId = matches[0]
            val encryptedUserId = matches[1]

            val decryptedFileId = decryptHexAes(encryptedFileId, "jcLycoRJT6OWjoWspgLMOZwS3aSS0lEn")
            val decryptedUserId = decryptHexAes(encryptedUserId, "PZZ3J3LDbLT0GY7qSA5wW5vchqgpO36O")

            val postContent = JSONObject().apply {
                put("idfile", decryptedFileId)
                put("iduser", decryptedUserId)
                put("domain_play", "https://my.9stream.net")
                put("platform", "Linux armv81")
                put("hlsSupport", true)

                val jw = JSONObject()
                val browser = JSONObject()
                browser.put("androidNative", false)
                browser.put("chrome", true)
                browser.put("edge", false)
                browser.put("facebook", false)
                browser.put("firefox", false)
                browser.put("ie", false)
                browser.put("msie", false)
                browser.put("safari", false)
                val ver = JSONObject()
                ver.put("version", "137.0.0.0")
                ver.put("major", 137)
                ver.put("minor", 0)
                browser.put("version", ver)
                jw.put("Browser", browser)

                val os = JSONObject()
                os.put("android", true)
                os.put("iOS", false)
                os.put("mobile", true)
                os.put("mac", false)
                os.put("iPad", false)
                os.put("iPhone", false)
                os.put("windows", false)
                os.put("tizen", false)
                os.put("tizenApp", false)
                val osver = JSONObject()
                osver.put("version", "10")
                osver.put("major", 10)
                os.put("version", osver)
                jw.put("OS", os)

                val feats = JSONObject()
                feats.put("iframe", false)
                feats.put("passiveEvents", true)
                feats.put("backgroundLoading", true)
                jw.put("Features", feats)

                put("jwplayer", jw)
            }

            val encryptedPayloadHex = encryptHexAes(postContent.toString(), "vlVbUQhkOhoSfyteyzGeeDzU0BHoeTyZ")
            val signature = md5Hex(encryptedPayloadHex + "KRWN3AdgmxEMcd2vLN1ju9qKe8Feco5h")

            val form = FormBody.Builder()
                .add("data", "$encryptedPayloadHex|$signature")
                .build() as RequestBody

            val postRes = app.post("$domainApi/playiframe", requestBody = form, referer = mainUrl).textLarge
            val json = JSONObject(postRes)
            val encryptedData = json.getString("data")

            val videoUrl = decryptHexAes(encryptedData, "oJwmvmVBajMaRCTklxbfjavpQO7SZpsL")

            if (videoUrl.contains("m3u8")) {
                M3u8Helper.generateM3u8(
                    name,
                    videoUrl,
                    "$mainUrl/",
                    headers = mapOf("Origin" to "$mainUrl/")
                ).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = "$name MP4",
                        name = "$name MP4",
                        url = videoUrl,
                        referer = url,
                        quality = Qualities.Unknown.value,
                        type = INFER_TYPE,
                        headers = mapOf("Referer" to mainUrl)
                    )
                )
            }

        } catch (_: Exception) {
            // silently fail
        }
    }

    // ----- AES helpers (unchanged) -----
    private fun md5(data: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(data)
    }

    private fun md5Hex(input: String): String {
        val digest = md5(input.toByteArray(StandardCharsets.UTF_8))
        return BigInteger(1, digest).toString(16).padStart(32, '0')
    }

    private fun decryptHexAes(hexData: String, secret: String): String {
        val bytes = hexStringToByteArray(hexData)
        val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        return aesDecryptBase64(base64, secret)
    }

    private fun encryptHexAes(plaintext: String, secret: String): String {
        val base64 = aesEncryptBase64(plaintext, secret)
        val bytes = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
        return bytes.joinToString("") { String.format("%02x", it) }
    }

    private fun aesDecryptBase64(b64: String, password: String): String {
        val encrypted = android.util.Base64.decode(b64, android.util.Base64.NO_WRAP)
        val salted = "Salted__".toByteArray(StandardCharsets.UTF_8)
        if (!encrypted.copyOfRange(0, 8).contentEquals(salted)) {
            throw IllegalArgumentException("Invalid encrypted data format")
        }
        val salt = encrypted.copyOfRange(8, 16)
        val ciphertext = encrypted.copyOfRange(16, encrypted.size)

        var derived = ByteArray(0)
        while (derived.size < 48) {
            val last = if (derived.isEmpty()) ByteArray(0) else derived.copyOfRange(derived.size - 16, derived.size)
            val md = MessageDigest.getInstance("MD5")
            md.update(last)
            md.update(password.toByteArray(StandardCharsets.UTF_8))
            md.update(salt)
            derived += md.digest()
        }
        val key = derived.copyOfRange(0, 32)
        val iv = derived.copyOfRange(32, 48)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plain = cipher.doFinal(ciphertext)
        return String(plain, StandardCharsets.UTF_8)
    }

    private fun aesEncryptBase64(plaintext: String, password: String): String {
        val salt = ByteArray(8)
        java.security.SecureRandom().nextBytes(salt)

        var derived = ByteArray(0)
        while (derived.size < 48) {
            val last = if (derived.isEmpty()) ByteArray(0) else derived.copyOfRange(derived.size - 16, derived.size)
            val md = MessageDigest.getInstance("MD5")
            md.update(last)
            md.update(password.toByteArray(StandardCharsets.UTF_8))
            md.update(salt)
            derived += md.digest()
        }
        val key = derived.copyOfRange(0, 32)
        val iv = derived.copyOfRange(32, 48)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

        val out = ByteArray(8 + salt.size + ciphertext.size)
        System.arraycopy("Salted__".toByteArray(StandardCharsets.UTF_8), 0, out, 0, 8)
        System.arraycopy(salt, 0, out, 8, salt.size)
        System.arraycopy(ciphertext, 0, out, 8 + salt.size, ciphertext.size)

        return android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
    }

    private fun hexStringToByteArray(s: String): ByteArray {
        val len = s.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(s[i], 16) shl 4) + Character.digit(s[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
