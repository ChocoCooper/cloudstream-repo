package com.Film1k

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reverse-engineered from pow-DEJGtdh2.js (functions gr/ye/wr/yr/Er) and
 * videoPagesBundle-Bgi0QmPo.js (functions ws/ks/Xt/La/Ea/Qa).
 */
object Film1kCrypto {

    private const val STATE_SIZE = 512
    private const val STATE_MASK = STATE_SIZE - 1
    private const val ROUNDS = 2
    private const val MIX_CONST_1 = 2654435761L
    private const val MIX_CONST_2 = 2246822519L

    private fun rotl32(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))
    private fun imul32(a: Int, b: Int): Int = a * b

    private fun quarterRound(state: IntArray) {
        state[0] = state[0] + state[1]
        state[3] = rotl32(state[3] xor state[0], 16)
        state[2] = state[2] + state[3]
        state[1] = rotl32(state[1] xor state[2], 12)
        state[0] = state[0] + state[1]
        state[3] = rotl32(state[3] xor state[0], 8)
        state[2] = state[2] + state[3]
        state[1] = rotl32(state[1] xor state[2], 7)
    }

    private fun digest(input: ByteArray): IntArray {
        val e = intArrayOf(1779033703, -1150833019, 1013904242, -1521486534)
        for (byte in input) {
            e[0] = e[0] + (byte.toInt() and 0xFF)
            e[0] = rotl32(e[0], 7)
            quarterRound(e)
        }
        repeat(8) { quarterRound(e) }
        val r = IntArray(STATE_SIZE)
        for (i in 0 until STATE_SIZE) {
            quarterRound(e)
            r[i] = e[0] xor e[2]
        }
        repeat(ROUNDS) {
            for (s in 0 until STATE_SIZE) {
                val a = r[s] and STATE_MASK
                var c = r[s] + r[a]
                c = rotl32(c, 13)
                c = c xor imul32(r[(s + 1) and STATE_MASK], MIX_CONST_1.toInt())
                r[s] = c
                e[0] = e[0] xor c
                quarterRound(e)
            }
        }
        val n = IntArray(8)
        val o = STATE_SIZE / 8
        for (i in 0 until 8) {
            quarterRound(e)
            var s = e[0]
            val a = i * o
            for (c in 0 until o) {
                val d = r[a + c]
                s += d
                s = rotl32(s, 5)
                s = s xor imul32(d, MIX_CONST_2.toInt())
            }
            n[i] = s xor e[2]
        }
        return n
    }

    private fun leadingZeroBits(digest: IntArray): Int {
        var total = 0
        for (word in digest) {
            if (word == 0) { total += 32; continue }
            return total + Integer.numberOfLeadingZeros(word)
        }
        return total
    }

    /**
     * Parallel PoW solver — spawns one coroutine per CPU core (capped at 4)
     * and lets each worker scan a different residue class modulo N.
     *
     * On a 4-core device this yields roughly a 3–4× speedup compared to the
     * original single-threaded solver. Each worker checks the shared result
     * atomic on every iteration and exits immediately once any worker finds
     * a valid solution.
     */
    suspend fun solvePow(
        nonce: String,
        difficulty: Int,
        timeoutMs: Long = 20_000L
    ): String? = coroutineScope {
        if (difficulty <= 0) return@coroutineScope "0"

        val prefix = "$nonce:"
        val start = System.currentTimeMillis()
        val numWorkers = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val result = AtomicReference<String?>(null)

        val jobs = (0 until numWorkers).map { workerId ->
            async(Dispatchers.Default) {
                var s = workerId.toLong()
                val step = numWorkers.toLong()

                while (result.get() == null &&
                       System.currentTimeMillis() - start < timeoutMs) {

                    var i = 0
                    while (i < 256) {
                        if (result.get() != null) return@async
                        val candidate = (prefix + s).toByteArray(Charsets.ISO_8859_1)
                        if (leadingZeroBits(digest(candidate)) >= difficulty) {
                            result.compareAndSet(null, s.toString())
                            return@async
                        }
                        s += step
                        i++
                    }
                }
            }
        }

        jobs.awaitAll()
        result.get()
    }

    // ---------------------------------------------------------------
    // Stream payload decryption (AES-256-GCM) — unchanged
    // ---------------------------------------------------------------

    private fun base64UrlDecode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        val pad = (4 - t.length % 4) % 4
        t += "=".repeat(pad)
        return Base64.getDecoder().decode(t)
    }

    private fun versionToKeyPartIndices(version: String, totalParts: Int): Pair<Int, Int>? {
        val n = version.trim().toIntOrNull() ?: return null
        if (n < 1 || n > 20) return null
        val a = n
        val b = 31 - n
        if (a < 1 || b < 1 || a > totalParts || b > totalParts) return null
        return a to b
    }

    fun decryptPlayback(
        version: String,
        keyParts: List<String>,
        ivB64Url: String,
        payloadB64Url: String
    ): String {
        val indices = versionToKeyPartIndices(version, keyParts.size)
        val selectedParts = if (indices != null) {
            listOf(keyParts[indices.first - 1], keyParts[indices.second - 1])
        } else keyParts
        val keyBytes = selectedParts.fold(ByteArray(0)) { acc, part -> acc + base64UrlDecode(part) }
        val iv = base64UrlDecode(ivB64Url)
        val ciphertextWithTag = base64UrlDecode(payloadB64Url)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        val plaintext = cipher.doFinal(ciphertextWithTag)
        return String(plaintext, Charsets.UTF_8)
    }
}
