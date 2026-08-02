package com.Film1k

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Reverse-engineered from pow-DEJGtdh2.js (functions gr/ye/wr/yr/Er) and
 * videoPagesBundle-Bgi0QmPo.js (functions ws/ks/Xt/La/Ea/Qa). Both pieces
 * are fully deterministic — no server secret beyond what's in the API
 * responses themselves.
 */
object Film1kCrypto {

    // ---------------------------------------------------------------
    // Proof-of-Work solver
    // Custom hash: SHA-256's IV constants run through a ChaCha20-style
    // quarter-round mixing schedule (NOT actual SHA-256 or ChaCha20 —
    // a bespoke combination). Solves: smallest s (>=0) such that
    // hash(nonce + ":" + s) has >= difficulty leading zero bits.
    // ---------------------------------------------------------------

    private const val STATE_SIZE = 512          // "be"
    private const val STATE_MASK = STATE_SIZE - 1 // "lt"
    private const val ROUNDS = 2                  // "dr"
    private const val MIX_CONST_1 = 2654435761L   // "lr" = 0x9E3779B1
    private const val MIX_CONST_2 = 2246822519L   // "hr" = 0x85EBCA77

    private fun rotl32(x: Int, n: Int): Int = (x shl n) or (x ushr (32 - n))

    // Math.imul(t, e) >>> 0  — 32-bit wrapping multiply, unsigned result
    private fun imul32(a: Int, b: Int): Int = a * b

    // "ye" — one ChaCha-style quarter round mutating a 4-word state in place
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

    // "gr" — the custom digest function. Input: raw bytes. Output: 8 x 32-bit words.
    private fun digest(input: ByteArray): IntArray {
        // SHA-256 IV[0..3], used only as a seed here (not real SHA-256)
        val e = intArrayOf(1779033703, -1150833019, 1013904242, -1521486534)
        // (3144134277 and 2773480762 as signed Int: -1150833019, -1521486534)

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

    // "wr" — count leading zero bits across the 8-word digest
    private fun leadingZeroBits(digest: IntArray): Int {
        var total = 0
        for (word in digest) {
            if (word == 0) {
                total += 32
                continue
            }
            return total + Integer.numberOfLeadingZeros(word)
        }
        return total
    }

    /**
     * Solves the PoW challenge. Mirrors Er(nonce, difficulty, timeoutMs) from
     * pow-DEJGtdh2.js. Returns the solution as a string, or null on timeout.
     */
    fun solvePow(nonce: String, difficulty: Int, timeoutMs: Long = 20_000L): String? {
        if (difficulty <= 0) return "0"
        val prefix = "$nonce:"
        val start = System.currentTimeMillis()
        var s = 0L
        while (true) {
            repeat(1024) {
                val candidate = (prefix + s).toByteArray(Charsets.ISO_8859_1)
                val d = digest(candidate)
                if (leadingZeroBits(d) >= difficulty) return s.toString()
                s++
            }
            if (System.currentTimeMillis() - start > timeoutMs) return null
        }
    }

    // ---------------------------------------------------------------
    // Stream payload decryption (AES-256-GCM)
    // ---------------------------------------------------------------

    private fun base64UrlDecode(s: String): ByteArray {
        var t = s.replace('-', '+').replace('_', '/')
        val pad = (4 - t.length % 4) % 4
        t += "=".repeat(pad)
        return Base64.getDecoder().decode(t)
    }

    // "Qa" — fixed version -> [a, 31-a] lookup table (1-indexed positions into key_parts)
    private fun versionToKeyPartIndices(version: String, totalParts: Int): Pair<Int, Int>? {
        val n = version.trim().toIntOrNull() ?: return null
        if (n < 1 || n > 20) return null
        val a = n
        val b = 31 - n
        if (a < 1 || b < 1 || a > totalParts || b > totalParts) return null
        return a to b
    }

    /**
     * Decrypts the { version, key_parts, iv, payload } object returned in
     * playback.* from the /playback API response, and returns the decrypted
     * JSON string (parse it yourself — it contains "sources": [{url, ...}]).
     */
    fun decryptPlayback(version: String, keyParts: List<String>, ivB64Url: String, payloadB64Url: String): String {
        val indices = versionToKeyPartIndices(version, keyParts.size)
        val selectedParts = if (indices != null) {
            listOf(keyParts[indices.first - 1], keyParts[indices.second - 1])
        } else {
            keyParts // fallback: use all parts concatenated, as the JS does when lookup fails
        }

        val keyBytes = selectedParts.fold(ByteArray(0)) { acc, part -> acc + base64UrlDecode(part) }
        val iv = base64UrlDecode(ivB64Url)
        val ciphertextWithTag = base64UrlDecode(payloadB64Url)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keyBytes, "AES")
        // WebCrypto AES-GCM default tag length is 128 bits (16 bytes) — matches Java's default here.
        val gcmSpec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
        val plaintext = cipher.doFinal(ciphertextWithTag)

        return String(plaintext, Charsets.UTF_8)
    }
}
