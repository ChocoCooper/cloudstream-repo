package com.reanime

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Chicory WebAssembly runtime
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Module
import com.dylibso.chicory.runtime.Memory
import com.dylibso.chicory.wasm.types.Value

class ReanimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "Reanime"
    override var lang = "en"
    override val hasMainPage = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)

    // ... search, load, and other functions remain unchanged ...

    // --------------------------------------------------------------
    // LOAD LINKS – actual WASM decryption with Chicory
    // --------------------------------------------------------------
    private data class FlixApiResponse(
        @JsonProperty("servers") val servers: List<FlixServer> = emptyList()
    )

    private data class FlixServer(
        @JsonProperty("dataLink") val dataLink: String? = null,
        @JsonProperty("dataType") val dataType: String? = null,
    )

    private data class ResolvedFields(
        val containerName: String,
        val arrayName: String,
        val objectName: String,
        val keyField: String,
        val ivField: String,
        val tokenField: String,
        val keyFrag2Field: String
    )

    private fun sha256Hex(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
    }

    private fun resolveFields(seed: String): ResolvedFields {
        var e = seed
        for (o in 0 until 3) e = sha256Hex(e + o)
        var a = e
        for (o in 0 until 3) a = sha256Hex(a + o)
        return ResolvedFields(
            containerName = "cd_${e.substring(24, 32)}",
            arrayName = "ad_${e.substring(32, 40)}",
            objectName = "od_${e.substring(40, 48)}",
            keyField = "kf_${e.substring(8, 16)}",
            ivField = "ivf_${e.substring(16, 24)}",
            tokenField = "${e.substring(48, 64)}_${e.substring(56, 64)}",
            keyFrag2Field = "${a.substring(0, 16)}_${a.substring(16, 24)}"
        )
    }

    private fun wasmMix(wasmB64: String, frag1: ByteArray, keyFrag2: ByteArray, tokenU: ByteArray, v: Int): ByteArray {
        val wasmBin = Base64.decode(wasmB64, Base64.DEFAULT)
        val module = Module.builder(wasmBin).build()
        val instance = module.instantiate()
        val memory: Memory = instance.memory()

        val k = frag1.size
        val base = 1000
        memory.write(base, frag1)
        memory.write(base + k, keyFrag2)
        memory.write(base + 2 * k, tokenU)

        instance.export("_s").apply(Value.i32(v.toLong()))
        instance.export("_r").apply(
            Value.i32(base.toLong()),
            Value.i32((base + k).toLong()),
            Value.i32((base + 2 * k).toLong()),
            Value.i32((base + 3 * k).toLong()),
            Value.i32(k.toLong())
        )

        val output = ByteArray(k)
        for (i in 0 until k) {
            output[i] = memory.read(base + 3 * k + i)
        }
        return output
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val spec = PBEKeySpec(
            password.toString(Charsets.ISO_8859_1).toCharArray(),
            salt,
            iterations,
            keyLength * 8
        )
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("ReanimeProvider", "AES decrypt failed", e)
            null
        }
    }

    private fun extractDataObjectFromEmbedHtml(html: String): String? {
        val marker = "data:{"
        var idx = html.indexOf(marker)
        if (idx == -1) {
            idx = html.indexOf("data: {")
            if (idx == -1) return null
            return extractObjectFrom(html, idx + "data: {".length - 1)
        }
        return extractObjectFrom(html, idx + marker.length - 1)
    }

    private fun extractObjectFrom(text: String, startIdx: Int): String {
        var depth = 0
        var inString = false
        var escape = false
        var i = startIdx
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(startIdx, i + 1)
                    }
                }
            }
            i++
        }
        return text.substring(startIdx)
    }

    private fun extractFirstObjectFromArray(arrayText: String): String? {
        val start = arrayText.indexOf('{')
        if (start == -1) return null
        return extractObjectFrom(arrayText, start)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ReanimeProvider", "loadLinks($data) called")
        val parts = data.split("|")
        if (parts.size < 2) return false
        val anilistPart = parts[0].substringAfterLast('/')
        val anilistId = anilistPart.toIntOrNull() ?: return false
        val ep = parts[1].toIntOrNull() ?: return false
        val lang = parts.getOrNull(2)?.lowercase() ?: "sub"
        Log.d("ReanimeProvider", "anilistId=$anilistId ep=$ep lang=$lang")

        // 1. Get flixcloud embed URL
        val flixApiUrl = "$mainUrl/api/flix/$anilistId/$ep"
        val flixResp = app.get(flixApiUrl).parsedSafe<FlixApiResponse>() ?: return false
        val server = flixResp.servers.firstOrNull {
            it.dataLink?.contains("flixcloud") == true &&
            (it.dataType?.lowercase() == lang || it.dataType == null)
        } ?: flixResp.servers.firstOrNull { it.dataLink?.contains("flixcloud") == true } ?: return false
        val embedUrl = server.dataLink ?: return false
        Log.d("ReanimeProvider", "embedUrl=$embedUrl")

        // 2. Fetch embed page and extract data object
        val html = app.get(embedUrl).text
        val dataObjStr = extractDataObjectFromEmbedHtml(html) ?: return false
        val seed = extractStringField(dataObjStr, "obfuscation_seed") ?: return false
        val obfCryptoData = extractObjectField(dataObjStr, "obfuscated_crypto_data") ?: return false
        val fields = resolveFields(seed)

        val containerText = extractObjectField(obfCryptoData, fields.containerName) ?: return false
        val arrayText = extractArrayField(containerText, fields.arrayName) ?: return false
        val firstObj = extractFirstObjectFromArray(arrayText) ?: return false
        val frag1B64 = extractStringField(firstObj, fields.keyField) ?: return false
        val ivB64 = extractStringField(firstObj, fields.ivField) ?: return false

        Log.d("ReanimeProvider", "Looking for keyFrag2Field: ${fields.keyFrag2Field}")
        val keyFrag2B64 = extractStringField(dataObjStr, fields.keyFrag2Field)
        if (keyFrag2B64 == null) {
            Log.e("ReanimeProvider", "keyFrag2B64 missing. Field=${fields.keyFrag2Field}. Data snippet: ${dataObjStr.take(500)}")
            return false
        }

        val L = extractStringField(dataObjStr, fields.tokenField) ?: return false
        val wasmB64 = extractStringField(dataObjStr, "w_payload") ?: return false

        // 3. Fetch token
        val tokenUrl = "https://flixcloud.cc/api/m3u8/$L"
        val tokenResp = app.get(tokenUrl).parsedSafe<Map<String, String>>() ?: return false
        val k = sha256Hex(L + "vid").take(10)
        val i = sha256Hex(L + "key").take(10)
        val P = tokenResp[k] ?: return false
        val U = tokenResp[i] ?: return false

        // 4. Decode
        val frag1 = Base64.decode(frag1B64, Base64.NO_WRAP)
        val keyFrag2 = Base64.decode(keyFrag2B64, Base64.NO_WRAP)
        val tokenU = Base64.decode(U, Base64.NO_WRAP)
        val encrypted = Base64.decode(P, Base64.NO_WRAP)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val v = seed.take(8).toInt(16)

        // 5. WASM mix
        val password = wasmMix(wasmB64, frag1, keyFrag2, tokenU, v)

        // 6. PBKDF2
        val derived = pbkdf2Sha256(password, seed.toByteArray(Charsets.UTF_8), 1000, 32)

        // 7. XOR
        val seedBytes = seed.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(32)
        for (j in 0 until 32) xored[j] = (derived[j].toInt() xor seedBytes[j % seedBytes.size].toInt()).toByte()

        // 8. AES key
        val aesKey = sha256(xored)

        // 9. Decrypt URL
        val decryptedUrl = aesCbcDecrypt(encrypted, aesKey, iv) ?: return false

        // 10. Return stream
        callback(
            newExtractorLink(
                source = name,
                name = "Reanime HD-2",
                url = decryptedUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
            }
        )
        Log.d("ReanimeProvider", "Successfully provided stream: ${decryptedUrl.take(60)}...")
        return true
    }
}
