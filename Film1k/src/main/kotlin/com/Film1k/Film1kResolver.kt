package com.Film1k

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Full no-browser resolver for film1k's embed player, built from a
 * fully validated reverse-engineering pass:
 *  - PoW hash (custom ChaCha-mixed digest): validated against a real
 *    server-accepted nonce/solution pair (exact match).
 *  - AES-256-GCM key derivation + decrypt: validated against a real
 *    /playback response (decrypts to the exact working .m3u8 URL).
 *  - /attest wire format: known exactly from a captured real request.
 *
 * Flow: details -> settings -> challenge -> attest -> [captcha -> verify
 * if required] -> playback -> decrypt.
 */
object Film1kResolver {

    private fun b64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun fixedLength(n: BigInteger, len: Int): ByteArray {
        val bytes = n.toByteArray()
        val trimmed = if (bytes.size > len) bytes.copyOfRange(bytes.size - len, bytes.size) else bytes
        val out = ByteArray(len)
        System.arraycopy(trimmed, 0, out, len - trimmed.size, trimmed.size)
        return out
    }

    // Minimal DER(SEQUENCE{INTEGER r, INTEGER s}) parser -> raw r||s (WebCrypto format)
    private fun derSignatureToRawRS(der: ByteArray): ByteArray {
        var offset = 0
        require(der[offset] == 0x30.toByte()) { "not a DER sequence" }
        offset++
        var seqLen = der[offset].toInt() and 0xFF
        offset++
        if (seqLen and 0x80 != 0) {
            val numBytes = seqLen and 0x7F
            seqLen = 0
            repeat(numBytes) { seqLen = (seqLen shl 8) or (der[offset].toInt() and 0xFF); offset++ }
        }
        require(der[offset] == 0x02.toByte()) { "expected INTEGER (r)" }
        offset++
        var rLen = der[offset].toInt() and 0xFF
        offset++
        val r = BigInteger(1, der.copyOfRange(offset, offset + rLen))
        offset += rLen
        require(der[offset] == 0x02.toByte()) { "expected INTEGER (s)" }
        offset++
        var sLen = der[offset].toInt() and 0xFF
        offset++
        val s = BigInteger(1, der.copyOfRange(offset, offset + sLen))
        return fixedLength(r, 32) + fixedLength(s, 32)
    }

    private data class Keypair(val privateKey: PrivateKey, val publicJwk: JSONObject, val sign: (String) -> String)

    private fun generateKeypair(): Keypair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val pair = kpg.generateKeyPair()
        val pub = pair.public as ECPublicKey
        val x = fixedLength(pub.w.affineX, 32)
        val y = fixedLength(pub.w.affineY, 32)

        val jwk = JSONObject().apply {
            put("crv", "P-256")
            put("ext", true)
            put("key_ops", JSONArray(listOf("verify")))
            put("kty", "EC")
            put("x", b64UrlEncode(x))
            put("y", b64UrlEncode(y))
        }

        val signFn: (String) -> String = { nonce ->
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initSign(pair.private)
            sig.update(nonce.toByteArray(Charsets.UTF_8))
            val der = sig.sign()
            b64UrlEncode(derSignatureToRawRS(der))
        }

        return Keypair(pair.private, jwk, signFn)
    }

    // Plausible, internally-consistent client telemetry. The real captured
    // example got confidence=0.75 (accepted) despite being an automated
    // Chromium under SwiftShader software rendering, so exact realism isn't
    // required — just a well-formed, consistent-looking payload.
    private fun buildClientInfo(): JSONObject = JSONObject().apply {
        put("user_agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36")
        put("architecture", "x86")
        put("bitness", "64")
        put("platform", "Windows")
        put("platform_version", "10.0")
        put("model", "")
        put("ua_full_version", "127.0.6533.100")
        put("brand_full_versions", JSONArray().apply {
            put(JSONObject().apply { put("brand", "Chromium"); put("version", "127.0.6533.100") })
            put(JSONObject().apply { put("brand", "Not=A?Brand"); put("version", "99.0.0.0") })
        })
        put("pixel_ratio", 1)
        put("screen_width", 1280)
        put("screen_height", 800)
        put("color_depth", 24)
        put("languages", JSONArray(listOf("en-US", "en")))
        put("timezone", "UTC")
        put("hardware_concurrency", 4)
        put("device_memory", 8)
        put("touch_points", 0)
        put("webgl_vendor", "Google Inc. (Google)")
        put("webgl_renderer", "ANGLE (Google, Vulkan 1.3.0 (SwiftShader Device (Subzero) (0x0000C0DE)), SwiftShader driver)")
        put("canvas_hash", "SdmNHvRtqeBV4yUS4HZF2VDeQRdR7waHtOmtSaagp7Y")
        put("audio_hash", "RyBmlOc4cA7XhqmvkyO40eo8sOa5q-CFlrTnf70qADY")
        put("webgl_params_hash", "W5M0nWhl6d8DuBEhxYLkPbt5GpFbRb7pBxV78OZJpXQ")
        put("fonts_hash", "RwD-Ua92gFQvLV5693YTkL4Goe0KeVrLUY4baoTj2Kk")
        put("codecs_hash", "qJye5DfMLC0co_nw835Vyx_VcUOEnA01Coov9OtwHZs")
        put("media_devices", "ai0ao0vi0")
        put("pointer_type", "fine,hover")
        put("extra", JSONObject().apply {
            put("vendor", "Google Inc.")
            put("appVersion", "5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36")
        })
    }

    private val httpClient = OkHttpClient()
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private suspend fun postJson(url: String, body: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val requestBody = body.toString().toRequestBody(jsonMediaType)
            val requestBuilder = Request.Builder().url(url).post(requestBody)
            headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            httpClient.newCall(requestBuilder.build()).execute().use { resp ->
                val text = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) throw Exception("POST $url failed: ${resp.code} $text")
                JSONObject(text)
            }
        }

    // Matches the real /challenge request exactly: zero-length body, no
    // Content-Type header at all (confirmed from a real captured request).
    private suspend fun postEmpty(url: String, headers: Map<String, String> = emptyMap()): JSONObject =
        withContext(Dispatchers.IO) {
            val requestBody = ByteArray(0).toRequestBody(null)
            val requestBuilder = Request.Builder().url(url).post(requestBody)
            headers.forEach { (k, v) -> requestBuilder.addHeader(k, v) }
            httpClient.newCall(requestBuilder.build()).execute().use { resp ->
                val text = resp.body?.string() ?: "{}"
                if (!resp.isSuccessful) throw Exception("POST $url failed: ${resp.code} $text")
                JSONObject(text)
            }
        }

    /**
     * Runs the full flow for one embed code and returns the decrypted
     * playback JSON (containing "sources": [{url, ...}]) as a JSONObject,
     * or null if anything along the way fails.
     */
    suspend fun resolvePlayback(apiBaseUrl: String, embedParent: String, code: String, mode: String = "embed"): JSONObject? {
        return try {
            val base = apiBaseUrl.trimEnd('/')
            val commonHeaders = mapOf("X-Embed-Parent" to embedParent)

            // 1. settings (mainly to check captcha_required; safe to ignore failures here)
            val captchaRequired = try {
                val settingsResp = app.get("$base/api/videos/$code/$mode/settings", headers = commonHeaders, verify = false)
                JSONObject(settingsResp.text).optBoolean("captcha_required", false)
            } catch (e: Exception) {
                true // assume worst case if we can't tell
            }

            // 2. fingerprint attestation (always required)
            val keypair = generateKeypair()
            val challenge = postEmpty("$base/api/videos/access/challenge", commonHeaders)
            val nonce = challenge.getString("nonce")
            val challengeId = challenge.getString("challenge_id")
            val signature = keypair.sign(nonce)

            val attestBody = JSONObject().apply {
                put("viewer_id", "")
                put("device_id", "")
                put("challenge_id", challengeId)
                put("nonce", nonce)
                put("signature", signature)
                put("public_key", keypair.publicJwk)
                put("client", buildClientInfo())
                put("storage", JSONObject())
                put("attributes", JSONObject().apply { put("entropy", "high") })
            }
            val attestResp = postJson("$base/api/videos/access/attest", attestBody, commonHeaders)

            val fingerprint = JSONObject().apply {
                put("token", attestResp.getString("token"))
                put("viewer_id", attestResp.getString("viewer_id"))
                put("device_id", attestResp.getString("device_id"))
                put("confidence", attestResp.getDouble("confidence"))
            }

            // 3. captcha (PoW), only if required
            var captchaToken: String? = null
            if (captchaRequired) {
                val captchaStart = postJson(
                    "$base/api/videos/$code/$mode/captcha",
                    JSONObject().apply { put("fingerprint", fingerprint) },
                    commonHeaders
                )
                val powNonce = captchaStart.getString("pow_nonce")
                val powDifficulty = captchaStart.getInt("pow_difficulty")
                val powToken = captchaStart.getString("pow_token")

                val solution = Film1kCrypto.solvePow(powNonce, powDifficulty, timeoutMs = 30_000L)
                    ?: return null // couldn't solve in time

                val verifyResp = postJson(
                    "$base/api/videos/$code/$mode/captcha/verify",
                    JSONObject().apply {
                        put("pow_token", powToken)
                        put("solution", solution)
                        put("fingerprint", fingerprint)
                    },
                    commonHeaders
                )
                if (verifyResp.optString("status") != "ok") return null
                captchaToken = verifyResp.getString("token")
            }

            // 4. playback
            val playbackHeaders = commonHeaders + if (captchaToken != null) {
                mapOf("X-Captcha-Token" to captchaToken)
            } else emptyMap()
            val playbackResp = postJson(
                "$base/api/videos/$code/$mode/playback",
                JSONObject().apply { put("fingerprint", fingerprint) },
                playbackHeaders
            )

            val pb = playbackResp.getJSONObject("playback")
            val version = pb.getString("version")
            val keyParts = pb.getJSONArray("key_parts").let { arr -> (0 until arr.length()).map { arr.getString(it) } }
            val iv = pb.getString("iv")
            val payload = pb.getString("payload")

            val decryptedJson = Film1kCrypto.decryptPlayback(version, keyParts, iv, payload)
            JSONObject(decryptedJson)
        } catch (e: Exception) {
            null
        }
    }
}
