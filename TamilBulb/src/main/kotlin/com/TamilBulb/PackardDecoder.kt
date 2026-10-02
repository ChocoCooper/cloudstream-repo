package com.tamilbulb

/**
 * Port of the "compact" Dean Edwards P.A.C.K.E.R variant used by
 * VidHide / tamilgun.space:
 *
 *   eval(function(p,a,c,k,e,d){
 *     while(c--) if(k[c]) p = p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'), k[c]);
 *     return p;
 *   }('...', 36, 610, '...'.split('|')))
 *
 * Note: this variant uses base-36 for ALL numeric tokens (both letters and
 * digits appear in the encoded payload), which is different from the classic
 * P.A.C.K.E.R that only uses base-N for values >= base.
 */
object PackardDecoder {

    private val HEAD = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{while\(c--\)if\(k\[c\]\)p=p\.replace\(new RegExp\('\\b'\+c\.toString\(a\)\+'\\b','g'\),k\[c\]\);return p\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    fun decode(src: String): String? {
        val m = HEAD.find(src) ?: return null
        var p = m.groupValues[1]
        val base = m.groupValues[2].toIntOrNull() ?: 36
        val count = m.groupValues[3].toIntOrNull() ?: 0
        val keys = m.groupValues[4].split("|")

        for (i in count - 1 downTo 0) {
            val key = i.toString(base)
            val repl = keys.getOrNull(i).orEmpty()
            if (repl.isEmpty()) continue
            p = p.replace(Regex("""\b${Regex.escape(key)}\b"""), repl)
        }
        return p
    }
}
