package com.Film1k

import com.lagradost.cloudstream3.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
import java.util.concurrent.ConcurrentHashMap

object Film1kResolver {

    private const val TAG = "Film1kDebug"

    // ==================================================================
    // 2.1 — Exponential-backoff retry helper
    // ==================================================================
    /**
     * Retries the given suspend block up to [times] with exponential backoff.
     *
     * Returns the block's result, or null if every attempt threw.
     * CancellationException is re-thrown immediately so coroutine cancellation
     * (e.g. from a supervisor scope shutting down) is not swallowed.
     */
    suspend fun <T> retry(
        times: Int = 3,
        initialDelayMs: Long = 300,
        block: suspend () -> T
    ): T? {
        var lastException: Throwable? = null
        for (attempt in 0 until times) {
            try {
                return block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastException = e
                if (attempt < times - 1) {
                    delay(initialDelayMs * (1L shl attempt))
                }
            }
        }
        if (lastException != null) {
            android.util.Log.e(TAG, "retry: all $times attempts failed", lastException)
        }
        return null
    }

    // ==================================================================
    // 1.2 — Details cache (pre-warmed by Film1kProvider.load())
    // ==================================================================
    private val detailsCache = ConcurrentHashMap<String, Pair<Long, String>>()
    private const val DETAILS_TTL_MS = 60_000L

    fun getCachedDetails(code: String): String? {
        val entry = detailsCache[code] ?: return null
        if (System.currentTimeMillis() - entry.first > DETAILS_TTL_MS) {
            detailsCache.remove(code)
            return null
        }
        return entry.second
    }

    fun putCachedDetails(code: String, text: String) {
        detailsCache[code] = System.currentTimeMillis() to text
    }

    suspend fun prewarmDetails(code: String): String? {
        getCachedDetails(code)?.let { return it }
        return retry(times = 2, initialDelayMs = 400) {
            val resp = app.get(
                "https://film1k.xyz/api/videos/$code/embed/details",
                verify = false
            )
            putCachedDetails(code, resp.text)
            resp.text
        }
    }

    // ---------------------------------------------------------------
    // HTTP helpers
    // ---------------------------------------------------------------

    private fun b64UrlEncode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun fixedLength(n: BigInteger, len: Int): ByteArray {
        val bytes = n.toByteArray()
        val trimmed = if (bytes.size > len) bytes.copyOfRange(bytes.size - len, bytes.size) else bytes
        val out = ByteArray(len)
        System.arraycopy(trimmed, 0, out, len - trimmed.size, trimmed.size)
        return out
    }

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
        val rLen = der[offset].toInt() and 0xFF
        offset++
        val r = BigInteger(1, der.copyOfRange(offset, offset + rLen))
        offset += rLen
        require(der[offset] == 0x02.toByte()) { "expected INTEGER (s)" }
        offset++
        val sLen = der[offset].toInt() and 0xFF
        offset++
        val s = BigInteger(1, der.copyOfRange(offset, offset + sLen))
        return fixedLength(r, 32) + fixedLength(s, 32)
    }

    private data class Keypair(
        val privateKey: PrivateKey,
        val publicJwk: JSONObject,
        val sign: (String) -> String
    )

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

    // ---------------------------------------------------------------
    // Main flow
    // ---------------------------------------------------------------

    suspend fun resolvePlayback(
        apiBaseUrl: String,
        embedParent: String,
        code: String,
        mode: String = "embed"
    ): JSONObject? {
        return try {
            val base = apiBaseUrl.trimEnd('/')
            val commonHeaders = mapOf("X-Embed-Parent" to embedParent)

            android.util.Log.e(TAG, "Resolver[1/4] settings GET $base/api/videos/$code/$mode/settings")

            // 2.1 — retry the settings call (this is the one that timed out in your log)
            val captchaRequired = retry(times = 3, initialDelayMs = 500) {
                val settingsResp = app.get(
                    "$base/api/videos/$code/$mode/settings",
                    headers = commonHeaders,
                    verify = false
                )
                android.util.Log.e(TAG, "Resolver[1/4] settings status=${settingsResp.code}")
                JSONObject(settingsResp.text).optBoolean("captcha_required", false)
            } ?: true

            android.util.Log.e(TAG, "Resolver[1/4] captchaRequired=$captchaRequired")

            val keypair = generateKeypair()
            val challenge = postEmpty("$base/api/videos/access/challenge", commonHeaders)
            val nonce = challenge.getString("nonce")
            val challengeId = challenge.getString("challenge_id")
            val signature = keypair.sign(nonce)
            android.util.Log.e(TAG, "Resolver[2/4] challenge OK nonce=${nonce.take(20)}...")

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
            android.util.Log.e(TAG, "Resolver[2/4] attest OK confidence=${attestResp.optDouble("confidence")}")

            val fingerprint = JSONObject().apply {
                put("token", attestResp.getString("token"))
                put("viewer_id", attestResp.getString("viewer_id"))
                put("device_id", attestResp.getString("device_id"))
                put("confidence", attestResp.getDouble("confidence"))
            }

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
                android.util.Log.e(TAG, "Resolver[3/4] PoW difficulty=$powDifficulty nonce=$powNonce")

                val t0 = System.currentTimeMillis()
                // 1.4 — parallel PoW (suspend)
                val solution = Film1kCrypto.solvePow(powNonce, powDifficulty, timeoutMs = 30_000L)
                android.util.Log.e(
                    TAG,
                    "Resolver[3/4] PoW solution=$solution elapsed=${System.currentTimeMillis() - t0}ms"
                )
                if (solution == null) {
                    android.util.Log.e(TAG, "Resolver[3/4] PoW TIMEOUT — aborting")
                    return null
                }

                val verifyResp = postJson(
                    "$base/api/videos/$code/$mode/captcha/verify",
                    JSONObject().apply {
                        put("pow_token", powToken)
                        put("solution", solution)
                        put("fingerprint", fingerprint)
                    },
                    commonHeaders
                )
                android.util.Log.e(TAG, "Resolver[3/4] verify status=${verifyResp.optString("status")}")
                if (verifyResp.optString("status") != "ok") return null
                captchaToken = verifyResp.getString("token")
            }

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
            val keyParts = pb.getJSONArray("key_parts").let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
            val iv = pb.getString("iv")
            val payload = pb.getString("payload")
            android.util.Log.e(TAG, "Resolver[4/4] playback version=$version keyParts=${keyParts.size}")

            val decryptedJson = Film1kCrypto.decryptPlayback(version, keyParts, iv, payload)
            android.util.Log.e(TAG, "Resolver[4/4] decrypt OK (${decryptedJson.length} chars)")
            JSONObject(decryptedJson)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Resolver FAILED", e)
            null
        }
    }
}
