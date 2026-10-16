package com.JavHub

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import java.net.URI

// ---------------------------------------------------------------------------
// Shared browser headers (used for page + config fetches)
// ---------------------------------------------------------------------------
private val BROWSER_HEADERS = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.5"
)

private fun originOf(url: String): String =
    try { URI(url).let { "${it.scheme}://${it.host}" } } catch (e: Exception) { "" }

private fun streamHeaders(cdnOrigin: String): Map<String, String> = mapOf(
    "Accept" to "*/*",
    "Accept-Language" to "en-US,en;q=0.5",
    "Origin" to cdnOrigin
)

private fun String.toPlaylistM3u8(): String {
    val perResolutionSegment =
        Regex("""/(?:\d{3,5}x\d{3,5}|\d{3,4}p)/[^/]+\.m3u8""")
    return when {
        perResolutionSegment.containsMatchIn(this) ->
            perResolutionSegment.replace(this, "/playlist.m3u8")
        this.contains("/video.m3u8") ->
            this.replace(Regex("""/video\.m3u8(\?.*)?$"""),
                         "/playlist.m3u8$1")
        else -> this
    }
}

// ===========================================================================
// EmbedExtractor — walks redirects/iframes, unpacks scripts, finds m3u8.
// Host-agnostic, so it works with any maxstream-family domain.
// ===========================================================================
internal object EmbedExtractor {

    private const val MAX_DEPTH = 6

    private val metaRefreshRe = Regex(
        """<meta[^>]+http-equiv=["']?refresh["']?[^>]+content=["'][^;]*;\s*url=([^"']+)""",
        RegexOption.IGNORE_CASE
    )
    private val jsLocationRe = Regex(
        """(?:window|document|top)\.location(?:\.href|\.replace)?\s*(?:=|\()\s*["']([^"']+)["']"""
    )
    private val jsLocationAssignRe = Regex(
        """(?:window|document|top)\.location\.(?:assign|replace)\(\s*["']([^"']+)["']"""
    )

    private val jwSetupRe = Regex(
        """jwplayer\s*\(\s*["']?[\w-]+["']?\s*\)\s*\.setup\s*\(\s*(\{.*?\})\s*\)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )
    private val m3u8InJsonRe = Regex(
        """(?:file|src|source|hls\d?)\s*[:=]\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val anyM3u8Re = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
    private val anyMp4Re  = Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""")
    private val videoSourceRe = Regex(
        """<(?:source|video)[^>]+src=["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )
    private val b64BlobRe = Regex("""["'](aHR0c[A-Za-z0-9+/=]+)["']""")

    private val packerFullRe = Regex(
        """eval\s*\(\s*function\s*\(\s*p\s*,\s*a\s*,\s*c\s*,\s*k\s*,\s*e\s*,\s*[a-z]\s*\)\s*\{.*?\}\s*\(\s*(["'])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(["'])(.*?)\5\s*\.split\s*\(\s*(["'])\|["']\s*\)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private fun absoluteUrl(url: String, base: String): String? {
        if (url.isBlank()) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        return try { URI(base).resolve(url).toString() } catch (e: Exception) { null }
    }

    private fun hostOf(url: String): String? =
        try { URI(url).host } catch (e: Exception) { null }

    private fun jsUnescape(s: String): String = s
        .replace("\\/", "/")
        .replace("\\u0026", "&")
        .replace("\\u003d", "=")
        .replace("\\u003f", "?")

    private fun baseN(num: Int, radix: Int): String {
        val digits = "0123456789abcdefghijklmnopqrstuvwxyz"
        if (num == 0) return "0"
        var n = num
        val sb = StringBuilder()
        while (n > 0) {
            sb.insert(0, digits[n % radix]); n /= radix
        }
        return sb.toString()
    }

    private fun unpackDeanEdwards(js: String): String? {
        val m = packerFullRe.find(js) ?: return null
        val payload = m.groupValues[2]
        val radix = m.groupValues[3].toIntOrNull() ?: return null
        val count = m.groupValues[4].toIntOrNull() ?: return null
        val keys = m.groupValues[6].split("|")

        var result = payload
        for (i in count - 1 downTo 0) {
            val key = keys.getOrNull(i) ?: continue
            if (key.isEmpty()) continue
            result = result.replace(
                Regex("""\b${Regex.escape(baseN(i, radix))}\b"""), key)
        }
        return result
    }

    private fun findRedirect(html: String, baseUrl: String): String? {
        metaRefreshRe.find(html)?.let { return absoluteUrl(it.groupValues[1], baseUrl) }
        jsLocationAssignRe.find(html)?.let { return absoluteUrl(it.groupValues[1], baseUrl) }
        jsLocationRe.find(html)?.let { return absoluteUrl(it.groupValues[1], baseUrl) }
        return null
    }

    private fun pickStream(html: String): String? {
        val setup = jwSetupRe.find(html)?.groupValues?.get(1)
        val searchText = (setup ?: html).replace("\\/", "/")

        m3u8InJsonRe.find(searchText)?.let { return jsUnescape(it.groupValues[1]) }
        videoSourceRe.find(searchText)?.let { return jsUnescape(it.groupValues[1]) }
        anyM3u8Re.find(searchText)?.let { return jsUnescape(it.groupValues[1]) }
        anyMp4Re.find(searchText)?.let { return jsUnescape(it.groupValues[1]) }

        val unpacked = try { getAndUnpack(html) } catch (e: Exception) { html }
        val unpackedNorm = unpacked.replace("\\/", "/")
        anyM3u8Re.find(unpackedNorm)?.let { return jsUnescape(it.groupValues[1]) }
        videoSourceRe.find(unpackedNorm)?.let { return jsUnescape(it.groupValues[1]) }

        val doc = Jsoup.parse(html)
        for (s in doc.select("script")) {
            val body = s.data()
            if (body.isBlank()) continue
            if (!body.contains("eval(function(p,a,c,k,e")) continue
            val de = unpackDeanEdwards(body) ?: continue
            val norm = de.replace("\\/", "/")
            anyM3u8Re.find(norm)?.let { return jsUnescape(it.groupValues[1]) }
            videoSourceRe.find(norm)?.let { return jsUnescape(it.groupValues[1]) }
            m3u8InJsonRe.find(norm)?.let { return jsUnescape(it.groupValues[1]) }
        }

        for (b in b64BlobRe.findAll(html).map { it.groupValues[1] }.toSet()) {
            try {
                val dec = String(Base64.decode(b, Base64.DEFAULT))
                if (dec.contains(".m3u8") || dec.contains(".mp4")) return dec
            } catch (e: Exception) { /* ignore */ }
        }
        return null
    }

    suspend fun extract(
        embedUrl: String,
        referer: String?,
        sourceLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = BROWSER_HEADERS.toMutableMap()
        referer?.let { headers["Referer"] = it }

        var currentUrl = embedUrl
        var finalHtml: String? = null

        for (depth in 0 until MAX_DEPTH) {
            if (currentUrl.startsWith("about:") || currentUrl.isBlank()) return false

            val resp = try {
                app.get(currentUrl, headers = headers, timeout = 15)
            } catch (e: Exception) { return false }

            val html = resp.text
            currentUrl = resp.url.ifBlank { currentUrl }

            val redirect = findRedirect(html, currentUrl)
            if (redirect != null && redirect != currentUrl) {
                headers["Referer"] = currentUrl
                currentUrl = redirect
                continue
            }

            val doc = Jsoup.parse(html, currentUrl)
            val iframes = doc.select("iframe[src]")
            if (iframes.isNotEmpty()) {
                var chosen: String? = null
                for (f in iframes) {
                    val raw = f.attr("src").trim()
                    if (raw.startsWith("about:") || raw.startsWith("data:")) continue
                    val src = absoluteUrl(raw, currentUrl) ?: continue
                    if (hostOf(src) != hostOf(currentUrl)) { chosen = src; break }
                }
                if (chosen == null) {
                    for (f in iframes) {
                        val raw = f.attr("src").trim()
                        if (raw.startsWith("about:") || raw.startsWith("data:")) continue
                        chosen = absoluteUrl(raw, currentUrl)
                        if (chosen != null) break
                    }
                }
                if (chosen != null) {
                    headers["Referer"] = currentUrl
                    currentUrl = chosen
                    continue
                }
            }

            finalHtml = html
            break
        }

        if (finalHtml == null) return false

        val streamUrl = pickStream(finalHtml) ?: return false
        val cleanStream = streamUrl.toPlaylistM3u8()
        val cdnOrigin = originOf(cleanStream)

        callback.invoke(
            newExtractorLink(
                sourceLabel,
                sourceLabel,
                cleanStream,
                ExtractorLinkType.M3U8
            ) {
                this.referer = currentUrl
                this.quality = Qualities.Unknown.value
                if (cdnOrigin.isNotBlank()) {
                    this.headers = streamHeaders(cdnOrigin)
                }
            }
        )
        return true
    }
}

// ===========================================================================
// JavguruExtractor — processes ONLY the "STREAM JK" button (maxstream source)
// ===========================================================================
class JavguruExtractor {

    val name = "JavGuru"

    private val javguruBase = "https://jav.guru"

    private data class Variant(val suffix: String, val label: String)

    private val variants = listOf(
        Variant("",      "JavGuru"),
        Variant("-MR",   "JavGuru [Uncensored]"),
        Variant("-SUBS", "JavGuru [English Subtitles]")
    )

    // ---- config-page regexes ----
    private val cfgBaseRe  = Regex("""base:\s*['"]([^'"]+)['"]""")
    private val cfgRtypeRe = Regex("""rtype:\s*['"]([^'"]+)['"]""")
    private val cfgCidRe   = Regex("""cid:\s*['"]([^'"]+)['"]""")
    private val cfgKeysRe  = Regex("""keys:\s*\[([^\]]+)\]""")

    // ---- iframe_url extractor (matches both "iframe_url":"..." and
    //      "iframe_url" : "..." with optional whitespace) ----
    private val iframeUrlRe = Regex(
        """"iframe_url"\s*:\s*"([^"]*)"""",
        RegexOption.IGNORE_CASE
    )

    // ---- JK button detector ----
    // Matches:
    //   "STREAM JK", "Stream JK", "JK Player", "Player JK", "JK", "Jk"
    // Does NOT match: "STREAM JKX", "JK01", "JK-" etc.
    private val jkButtonRe = Regex("""(?:^|\s)JK(?:\s|$)""")

    private fun isJkButton(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        // Primary: token "JK" surrounded by word boundaries
        if (jkButtonRe.containsMatchIn(t.uppercase())) return true
        // Fallback: label ends with "JK"
        if (t.uppercase().endsWith("JK")) return true
        return false
    }

    /**
     * Try every JavGuru variant; for each, look for the STREAM JK button
     * and process ONLY that source. Returns true if a stream was emitted.
     */
    suspend fun extract(
        code: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        for (variant in variants) {
            val pageUrl = "$javguruBase/$code${variant.suffix}"

            val html = try {
                val r = app.get(pageUrl, timeout = 15, headers = BROWSER_HEADERS)
                if (r.code == 404) continue
                r.text
            } catch (e: Exception) { continue }

            if (html.isBlank()) continue
            if (!html.contains("iframe_url", ignoreCase = true)) continue

            if (extractJkFromPage(html, pageUrl, variant.label, callback)) {
                return true
            }
        }
        return false
    }

    // -----------------------------------------------------------------
    // Locate the JK button, pick the matching iframe_url, and drive the
    // whole extraction chain from that single candidate.
    // -----------------------------------------------------------------
    private suspend fun extractJkFromPage(
        html: String,
        pageUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = Jsoup.parse(html, pageUrl)

        // Button order corresponds 1:1 with iframe_url order
        val buttons = doc
            .select("a.wp-btn-iframe__shortcode[data-localize]")
            .map { it.text().trim() }

        val iframeMatches = iframeUrlRe.findAll(html).toList()

        // Find JK button index
        val jkIndex = buttons.indexOfFirst { isJkButton(it) }
        if (jkIndex < 0) return false          // no JK button on this page
        if (jkIndex >= iframeMatches.size) return false  // malformed page

        val sourceLabel = "$label ${buttons[jkIndex]}".trim()
        val encoded     = iframeMatches[jkIndex].groupValues[1]

        return runJkChain(encoded, pageUrl, sourceLabel, callback)
    }

    // -----------------------------------------------------------------
    // Core chain: base64 → config page → base/rtype/cid/keys →
    //              Location header → EmbedExtractor
    // -----------------------------------------------------------------
    private suspend fun runJkChain(
        encoded: String,
        pageUrl: String,
        sourceLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. base64-decode the iframe_url value
        val decodedUrl = try {
            String(Base64.decode(encoded.trim(), Base64.DEFAULT))
        } catch (e: Exception) { return false }

        if (!decodedUrl.startsWith("http")) return false
        if (decodedUrl.startsWith("about:")) return false

        // 2. fetch config page
        val iframeHtml = try {
            app.get(decodedUrl, timeout = 15, headers = BROWSER_HEADERS).text
        } catch (e: Exception) { return false }

        // 3. parse base / rtype / cid / keys
        val cfgBase   = cfgBaseRe.find(iframeHtml)?.groupValues?.get(1)
        val cfgRtype  = cfgRtypeRe.find(iframeHtml)?.groupValues?.get(1)
        val cfgCid    = cfgCidRe.find(iframeHtml)?.groupValues?.get(1)
        val cfgKeysRaw = cfgKeysRe.find(iframeHtml)?.groupValues?.get(1)

        if (cfgBase == null || cfgRtype == null ||
            cfgCid == null || cfgKeysRaw == null) {
            // Some sources serve the stream directly on the config page
            return tryEmbed(decodedUrl, pageUrl, sourceLabel, callback)
        }

        val keys = Regex("""['"]([^'"]+)['"]""")
            .findAll(cfgKeysRaw).map { it.groupValues[1] }.toList()

        val iframeDoc = Jsoup.parse(iframeHtml)
        val targetDiv = iframeDoc.getElementById(cfgCid) ?: return false

        val tokenParts = keys.mapNotNull { k ->
            targetDiv.attr(k).takeIf { it.isNotBlank() }
        }
        if (tokenParts.isEmpty()) return false

        // 4. build redirect URL
        val reversed  = tokenParts.joinToString("").reversed()
        val cleanBase = cfgBase.trimEnd('/')
        val finalUrl  = "$cleanBase/?${cfgRtype}r=$reversed"

        // 5. follow Location header → final embed URL (any host)
        val resolvedUrl = resolveRedirect(finalUrl)
        if (resolvedUrl.isBlank() || resolvedUrl.startsWith("about:")) return false

        // 6. EmbedExtractor handles the host-agnostic player + packer
        if (tryEmbed(resolvedUrl, pageUrl, sourceLabel, callback)) return true

        // 7. Cloudstream's generic extractor as a last resort
        return try {
            loadExtractor(resolvedUrl, pageUrl, { }, callback)
            true
        } catch (e: Exception) { false }
    }

    private suspend fun tryEmbed(
        url: String,
        referer: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean = try {
        EmbedExtractor.extract(
            embedUrl = url,
            referer = referer,
            sourceLabel = label,
            callback = callback
        )
    } catch (e: Exception) { false }

    // -----------------------------------------------------------------
    // Follow the Location header from a /searcho/?... URL.
    // The host we land on is whatever the redirect says — no domain
    // is hardcoded, so maxstream.org / .xyz / .sx / any future mirror
    // all work transparently.
    // -----------------------------------------------------------------
    private suspend fun resolveRedirect(url: String): String {
        return try {
            val resp = app.get(url, headers = BROWSER_HEADERS,
                               allowRedirects = false)

            val location = resp.headers["location"]
                ?: resp.headers["Location"]
                ?: resp.headers["LOCATION"]

            when {
                location == null -> url
                location.contains("javlesbians.com") -> followRedirects(location)
                location.startsWith("http") -> location
                else -> try {
                    URI(url).resolve(location).toString()
                } catch (e: Exception) { url }
            }
        } catch (e: Exception) { url }
    }

    private suspend fun followRedirects(url: String, maxHops: Int = 6): String {
        var current = url
        repeat(maxHops) {
            val resp = try {
                app.get(current, headers = BROWSER_HEADERS,
                        allowRedirects = false)
            } catch (e: Exception) { return current }

            val loc = resp.headers["location"]
                ?: resp.headers["Location"]
                ?: resp.headers["LOCATION"]
                ?: return current

            current = if (loc.startsWith("http")) loc
                      else try {
                          URI(current).resolve(loc).toString()
                      } catch (e: Exception) { return current }
        }
        return current
    }
}
