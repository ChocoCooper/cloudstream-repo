package com.megastream

import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object MegaStreamUtils {
    
    // Evaluates obfuscated P.A.C.K.E.R scripts (Essential for Filemoon/Upstream/StreamWish)
    fun unpack(javascript: String): String {
        val regex = Regex("""}\('(.*)', *(\d+), *(\d+), *'(.*)'\.split\('\|'\), *(\d+), *(.*)\)\)""")
        val match = regex.find(javascript) ?: return javascript
        
        val (p, aStr, cStr, kStr, eStr, dStr) = match.destructured
        var a = aStr.toInt()
        val c = cStr.toInt()
        val k = kStr.split("|")
        
        fun e(c: Int): String {
            val base36 = if (c < a) "" else e(c / a)
            val remainder = c % a
            val char = if (remainder > 35) (remainder + 29).toChar().toString() else remainder.toString(36)
            return base36 + char
        }
        
        var result = p
        for (i in c - 1 downTo 0) {
            if (k[i].isNotBlank()) {
                result = result.replace(Regex("\\b" + e(i) + "\\b"), k[i])
            }
        }
        return result
    }

    // Decrypts AES payloads from VidSrc RCP endpoints
    fun decryptAES(encrypted: String, key: String, iv: String): String {
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
            val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))
            cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
            val decrypted = cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT))
            return String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            return ""
        }
    }
}
