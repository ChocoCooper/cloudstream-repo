package com.JavHub

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

/**
 * jav.guru aggregator extractor.
 *
 * Each "STREAM XX" button on a jav.guru page hides a base64-encoded internal
 * URL that redirects to a real embed host (oppainet.net, javclan.com,
 * javplaya.com, vide0.net, maxstream.org, ...).
 *
 * This extractor focuses on the "JK" button, which resolves to maxstream.org.
 * Maxstream is a multi-step resolver (redirect stubs + nested iframes +
 * JW Player config), mirroring the working Python implementation.
 */
class JavguruExtractor : JavExtractor {
    override val name = "JavGuru"

    private val javguruBase = "https://jav.guru"

    private data class Variant(val suffix: String, val label: String)

    // Labels exactly as requested by the caller.
    private val variants = listOf(
        Variant("",      "JavGuru"),
        Variant("-MR",   "JavGuru [Uncensored]"),
        Variant("-SUBS", "JavGuru [English Subtitles]")
    )

    override suspend fun extract(code: String, callback: (ExtractorLink) -> Unit): Boolean {
        var found = false

        for (variant in variants) {
            val pageUrl = "$javguruBase/$code${variant.suffix}"
            val html = try {
                app.get(pageUrl, timeout = 15, headers = BROWSER_HEADERS).text
            } catch (e: Exception) {
                continue
            }
            if (html.isBlank()) continue

            // Extract "iframe_url":"..." values from the page source.
            val iframeUrls = Regex(
                """"iframe_url"\s*:\s*"([^"]+)"""",
                RegexOption.IGNORE_CASE
            ).findAll(html).map { it.groupValues[1] }.toList()

            if (iframeUrls.isEmpty()) continue

            for (encoded in iframeUrls) {
                val embedUrl = try {
                    resolveEmbed(encoded) ?: continue
                } catch (e: Exception) {
                    continue
                }

                // We only handle the JK button (maxstream.org).
                if (!embedUrl.contains("maxstream.org")) continue

                val ok = try {
                    MaxstreamExtractor.extract(
                        embedUrl    = embedUrl,
                        referer     = pageUrl,
                        sourceLabel = variant.label,
                        callback    = callback
                    )
                } catch (e: Exception) {
                    false
                }
                if (ok) found = true
            }

            if (found) break
        }

        return found
    }

    /**
     * Resolve a jav.guru internal base64 iframe_url into the real embed URL.
     *
     * Steps (mirror of the working Python `resolve_embed`):
     *   1. Base64-decode the encoded value
     *   2. Fetch that page
     *   3. Extract base / rtype / cid / keys from the inline config
     *   4. Build the /?Xr=Y URL from the target div's attributes
     *   5. Follow the redirect to get the final embed URL
     */
    private suspend fun resolveEmbed(encoded: String): String? {
        val decodedUrl = try {
            String(Base64.decode(encoded.trim(), Base64.DEFAULT))
        } catch (e: Exception) {
            return null
        }
        if (!decodedUrl.startsWith("http")) return null

        val iframeHtml = try {
            app.get(decodedUrl, timeout = 15, headers = BROWSER_HEADERS).text
        } catch (e: Exception) {
            return null
        }

        val base  = Regex("""base:\s*['"]([^'"]+)['"]""")
            .find(iframeHtml)?.groupValues?.get(1) ?: return null
        val rtype = Regex("""rtype:\s*['"]([^'"]+)['"]""")
            .find(iframeHtml)?.groupValues?.get(1) ?: return null
        val cid   = Regex("""cid:\s*['"]([^'"]+)['"]""")
            .find(iframeHtml)?.groupValues?.get(1) ?: return null
        val keysRaw = Regex("""keys:\s*\[([^\]]+)\]""")
            .find(iframeHtml)?.groupValues?.get(1) ?: return null

        val keys = Regex("""['"]([^'"]+)['"]""")
            .findAll(keysRaw).map { it.groupValues[1] }.toList()

        val doc = Jsoup.parse(iframeHtml)
        val targetDiv = doc.getElementById(cid) ?: return null

        val combined = keys
            .mapNotNull { k -> targetDiv.attr(k).takeIf { it.isNotBlank() } }
            .joinToString("")
            .reversed()

        val finalUrl = "${base.trimEnd('/')}/?${rtype}r=$combined"

        return try {
            val resp = app.get(finalUrl, timeout = 15, headers = BROWSER_HEADERS)
            resp.url.ifBlank { finalUrl }
        } catch (e: Exception) {
            finalUrl
        }
    }
}

/**
 * Maxstream.org embed resolver.
 *
 * Mirrors the working Python `extract_maxstream_stream`:
 *   1. Follow redirect stubs (meta refresh / JS location)
 *   2. Follow nested iframes (prefer cross-domain)
 *   3. Extract JW Player .m3u8 from the final player page (with a
 *      Dean Edwards packed-JS fallback via `getAndUnpack`)
 */
internal object MaxstreamExtractor {
    private const val MAX_DEPTH = 6

    private val redirectPatterns = listOf(
        Regex(
            """<meta[^>]+http-equiv=["']?refresh["']?[^>]+content=["'][^;]*;\s*url=([^"']+)""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """(?:window|document|top)\.location(?:\.href|\.replace)?\s*(?:=|\()\s*["']([^"']+)["']"""
        ),
        Regex(
            """(?:window|document|top)\.location\.(?:assign|replace)\(\s*["']([^"']+)["']"""
        )
    )

    private val jwSetupRe = Regex(
        """jwplayer\s*\(\s*["']?[\w-]+["']?\s*\)\s*\.setup\s*\(\s*(\{.*?\})\s*\)""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private val m3u8InJsonRe = Regex(
        """(?:file|src|source|hls\d?)\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""",
        RegexOption.IGNORE_CASE
    )

    private val anyM3u8Re = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")

    private fun absoluteUrl(url: String, base: String): String? {
        if (url.isBlank()) return null
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("//")) return "https:$url"
        return try {
            java.net.URI(base).resolve(url).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun host(url: String): String? = try {
        java.net.URI(url).host
    } catch (e: Exception) {
        null
    }

    private fun findRedirect(html: String, baseUrl: String): String? {
        for (rx in redirectPatterns) {
            val m = rx.find(html) ?: continue
            return absoluteUrl(m.groupValues[1], baseUrl)
        }
        return null
    }

    private fun looksLikePlayer(html: String): Boolean {
        val low = html.lowercase()
        return low.contains("jwplayer") ||
               low.contains(".m3u8") ||
               low.contains("sources") ||
               low.contains("file:") ||
               low.contains("eval(function(p,a,c,k,e,d)")
    }

    private fun pickStream(html: String): String? {
        val setup = jwSetupRe.find(html)?.groupValues?.get(1)
        val searchText = setup ?: html

        m3u8InJsonRe.find(searchText)?.let { return jsUnescape(it.groupValues[1]) }
        anyM3u8Re.find(searchText)?.let { return jsUnescape(it.groupValues[1]) }

        // Dean Edwards packed JS fallback
        val unpacked = try {
            getAndUnpack(html)
        } catch (e: Exception) {
            html
        }
        anyM3u8Re.find(unpacked)?.let { return jsUnescape(it.groupValues[1]) }
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
            val resp = try {
                app.get(currentUrl, headers = headers, timeout = 15)
            } catch (e: Exception) {
                return false
            }
            val html = resp.text

            // 1) Redirect stub?
            val redirect = findRedirect(html, currentUrl)
            if (redirect != null && redirect != currentUrl) {
                headers["Referer"] = currentUrl
                currentUrl = redirect
                continue
            }

            // 2) Iframes? (prefer cross-domain)
            val doc = Jsoup.parse(html, currentUrl)
            val iframes = doc.select("iframe[src]")
            if (iframes.isNotEmpty()) {
                var chosen: String? = null
                for (f in iframes) {
                    val src = absoluteUrl(f.attr("src"), currentUrl) ?: continue
                    if (host(src) != host(currentUrl)) {
                        chosen = src
                        break
                    }
                }
                if (chosen == null) {
                    chosen = absoluteUrl(iframes.first().attr("src"), currentUrl)
                }
                if (chosen != null) {
                    headers["Referer"] = currentUrl
                    currentUrl = chosen
                    continue
                }
            }

            // 3) Player page?
            if (looksLikePlayer(html)) {
                finalHtml = html
                break
            }

            // 4) Fallback: treat as final anyway
            finalHtml = html
            break
        }

        if (finalHtml == null) return false

        val streamUrl = pickStream(finalHtml) ?: return false

        callback.invoke(
            newExtractorLink(
                sourceLabel,
                sourceLabel,
                streamUrl,
                ExtractorLinkType.M3U8
            ) {
                this.referer = currentUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
