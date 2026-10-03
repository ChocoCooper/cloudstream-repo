package com.tamilbulb

import android.util.Log

/**
 * Port of the "compact" Dean Edwards P.A.C.K.E.R variant used by
 * VidHide / tamilgun.space:
 *
 *   eval(function(p,a,c,k,e,d){
 *     while(c--) if(k[c]) p = p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'), k[c]);
 *     return p;
 *   }('...', 36, 610, '...'.split('|')))
 *
 * Uses a permissive `.*?` for the function body so escaping differences
 * between HTML sources don't break the match.
 */
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

        // Replace tokens `count-1 .. 0` (descending) with keyword strings
        for (i in count - 1 downTo 0) {
            val key = i.toString(base)
            val repl = keys.getOrNull(i).orEmpty()
            if (repl.isEmpty()) continue
            payload = payload.replace(Regex("""\b${Regex.escape(key)}\b"""), repl)
        }
        return payload
    }
}
