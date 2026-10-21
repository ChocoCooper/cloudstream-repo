package com.tamilbulb

import android.util.Log

object PackardDecoder {

    private val HEAD = Regex(
        """eval\(function\(p,a,c,k,e,[dr]\)\{(.*?)\}\('(.*?)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun decode(src: String): String? {
        val m = HEAD.find(src) ?: run {
            Log.d("TamilBulb", "[packer] no PACKER block matched " +
                "(contains 'eval(function': ${src.contains("eval(function")})")
            return null
        }

        var payload = m.groupValues[2]
        val base    = m.groupValues[3].toIntOrNull() ?: 36
        val count   = m.groupValues[4].toIntOrNull() ?: 0
        val keys    = m.groupValues[5].split("|")

        Log.d("TamilBulb", "[packer] matched: payload=${payload.length}B " +
            "base=$base count=$count keys=${keys.size}")

        // i from count-1 down to 0 — matches the JS `while(c--)` loop order
        for (i in count - 1 downTo 0) {
            val key  = toBaseN(i, base)
            val repl = keys.getOrNull(i).orEmpty()
            if (repl.isEmpty()) continue
            payload = payload.replace(Regex("""\b${Regex.escape(key)}\b"""), repl)
        }
        return payload
    }

    /**
     * Exact port of the PACKER's JS `e` function:
     *
     *   e = function(c) {
     *       return (c < a ? '' : e(parseInt(c / a))) +
     *              ((c = c % a) > 35 ? String.fromCharCode(c + 29) : c.toString(36));
     *   }
     *
     * Alphabet for base ≤ 62:
     *   digits 0-9   → '0'..'9'
     *   digits 10-35 → 'a'..'z'  (from `digit.toString(36)`)
     *   digits 36-61 → 'A'..'Z'  (from `String.fromCharCode(digit + 29)`)
     */
    private fun toBaseN(num: Int, base: Int): String {
        if (num == 0) return "0"
        val sb = StringBuilder()
        var x = num
        while (x > 0) {
            val digit = x % base
            val ch = when {
                digit < 10 -> ('0'.code + digit).toChar()
                digit < 36 -> ('a'.code + digit - 10).toChar()
                else       -> ('A'.code + digit - 36).toChar()
            }
            sb.insert(0, ch)
            x /= base
        }
        return sb.toString()
    }
}
