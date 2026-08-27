package com.hdhub4u

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.amap
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.extractors.PixelDrain
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.fixUrl
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HdStream4u : VidHidePro() {
    override var mainUrl = "https://hdstream4u.com"
}

open class VidStack : ExtractorApi() {
    override var name = "Vidstack"
    override var mainUrl = "https://vidstack.io"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:134.0) Gecko/20100101 Firefox/134.0")
        val hash = url.substringAfterLast("#").substringAfter("/")
        val baseurl = getBaseUrl(url)

        val encoded = try {
            app.get("$baseurl/api/v1/video?id=$hash", headers = headers, timeout = 15000L).text.trim()
        } catch (e: Exception) {
            Log.e("Vidstack", "Failed to fetch video info: ${e.message}")
            return
        }
        if (encoded.isBlank()) return

        val key = "kiemtienmua911ca"
        val ivList = listOf("1234567890oiuytr", "0123456789abcdef")

        val decryptedText = ivList.firstNotNullOfOrNull { iv ->
            try {
                AesHelper.decryptAES(encoded, key, iv)
            } catch (_: Exception) {
                null
            }
        } ?: run {
            Log.e("Vidstack", "Failed to decrypt with all known IVs")
            return
        }

        val m3u8 = Regex("\"source\":\"(.*?)\"").find(decryptedText)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/") ?: ""
        if (m3u8.isBlank()) {
            Log.e("Vidstack", "No source URL found after decrypt")
            return
        }

        val subtitlePattern = Regex("\"([^\"]+)\":\\s*\"([^\"]+)\"")
        val subtitleSection = Regex("\"subtitle\":\\{(.*?)\\}").find(decryptedText)?.groupValues?.get(1)

        subtitleSection?.let { section ->
            subtitlePattern.findAll(section).forEach { match ->
                val lang = match.groupValues[1]
                val rawPath = match.groupValues[2].split("#")[0]
                if (rawPath.isNotEmpty()) {
                    val path = rawPath.replace("\\/", "/")
                    val subUrl = "$mainUrl$path"
                    subtitleCallback(newSubtitleFile(lang, fixUrl(subUrl)))
                }
            }
        }

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                // FIX: forcing https -> http is unnecessary and can break playback on
                // networks/CDNs that reject plain-http streaming. Keep the scheme the
                // server actually gave us.
                url = m3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.headers = mapOf("referer" to url, "Origin" to url.substringAfterLast("/"))
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun getBaseUrl(url: String): String {
        return try {
            URI(url).let { "${it.scheme}://${it.host}" }
        } catch (e: Exception) {
            Log.e("Vidstack", "getBaseUrl fallback: ${e.message}")
            mainUrl
        }
    }
}

object AesHelper {
    private const val TRANSFORMATION = "AES/CBC/PKCS5PADDING"

    fun decryptAES(inputHex: String, key: String, iv: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES")
        val ivSpec = IvParameterSpec(iv.toByteArray(Charsets.UTF_8))

        cipher.init(Cipher.DECRYPT_MODE, secretKey, ivSpec)
        val decryptedBytes = cipher.doFinal(inputHex.hexToByteArray())
        return String(decryptedBytes, Charsets.UTF_8)
    }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have an even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

class Hubstream : VidStack() {
    override var mainUrl = "https://hubstream.*"
}

class Hubstreamdad : Hblinks() {
    override var mainUrl = "https://hblinks.*"
}

open class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.*"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val elements = try {
            app.get(url, timeout = 15000L).document.select("h3 a,h5 a,div.entry-content p a")
        } catch (e: Exception) {
            Log.e("Hblinks", "Failed to load $url: ${e.message}")
            return
        }

        elements.amap {
            val raw = it.absUrl("href").ifBlank { it.attr("href") }
            if (raw.isBlank()) return@amap
            val lower = raw.lowercase()

            try {
                when {
                    "hubdrive" in lower -> Hubdrive().getUrl(raw, name, subtitleCallback, callback)
                    "hubcloud" in lower -> HubCloud().getUrl(raw, name, subtitleCallback, callback)
                    "hubcdn" in lower -> HUBCDN().getUrl(raw, name, subtitleCallback, callback)
                    else -> loadSourceNameExtractor(name, raw, "", Qualities.Unknown.value, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e("Hblinks", "Failed to resolve $raw: ${e.message}")
            }
        }
    }
}

class Hubcdnn : ExtractorApi() {
    override val name = "Hubcdn"
    override val mainUrl = "https://hubcdn.*"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(url, timeout = 15000L).document.toString()
        } catch (e: Exception) {
            Log.e("Hubcdn", "Failed to load $url: ${e.message}")
            return
        }

        val encoded = Regex("r=([A-Za-z0-9+/=]+)").find(doc)?.groups?.get(1)?.value
        if (encoded.isNullOrEmpty()) {
            Log.e("Hubcdn", "Encoded URL not found for $url")
            return
        }

        val m3u8 = base64Decode(encoded).substringAfterLast("link=")
        if (m3u8.isBlank()) {
            Log.e("Hubcdn", "Decoded link was empty for $url")
            return
        }

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                url = m3u8,
                ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

class PixelDrainDev : PixelDrain() {
    override var mainUrl = "https://pixeldrain.dev"
}

/**
 * FIX SUMMARY (Hubdrive):
 *  1. Single brittle CSS selector -> now tries three fallback selectors,
 *     since HubDrive's markup changes frequently and used to silently
 *     return an empty href, which then got passed straight into
 *     loadExtractor("") and failed with no useful error.
 *  2. Added a blank-href guard with logging instead of a silent no-op.
 *  3. Added the same file-size gate as HubCloud (see Utils.kt) so an
 *     oversized file never reaches the player from this path either.
 *  4. Bumped the request timeout from 5s to 15s — 5s was too aggressive
 *     for slower HubDrive mirrors and was a common source of "nothing
 *     loads" failures that look like crashes but are just timeouts.
 */
class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.space"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(url, timeout = 15000L).document
        } catch (e: Exception) {
            Log.e("Hubdrive", "Failed to load $url: ${e.message}")
            return
        }

        val href = doc.select("a.btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
            .ifBlank { doc.select("a:matchesOwn((?i)download)").attr("href") }
            .ifBlank { doc.selectFirst(".card-body a[href]")?.attr("href").orEmpty() }

        if (href.isBlank()) {
            Log.e("Hubdrive", "Could not locate a download link on $url")
            return
        }

        // Size guard: HubDrive pages usually show the size near the title/card body.
        val size = doc.selectFirst("i#size")?.text()
            ?: doc.select("*:matchesOwn((?i)\\d+(\\.\\d+)?\\s*(GB|MB))").firstOrNull()?.text()
            ?: ""
        if (isFileTooLarge(size)) {
            Log.w("Hubdrive", "Skipping oversized file ($size) at $url")
            return
        }

        when {
            href.contains("hubcloud", ignoreCase = true) ->
                HubCloud().getUrl(href, "HubDrive", subtitleCallback, callback)
            else ->
                loadExtractor(href, "HubDrive", subtitleCallback, callback)
        }
    }
}

/**
 * FIX SUMMARY (HubCloud):
 *  1. REMOVED runBlocking-in-property-initializer for `mainUrl`. The old
 *     code did:
 *         override var mainUrl: String = runBlocking { HDhub4uPlugin.getDomains()... }
 *     This ran a *blocking* network call every single time `HubCloud()` was
 *     constructed — and it gets constructed repeatedly (inside Hblinks,
 *     Hubdrive, HUBCDN dispatch, etc.) on whatever thread happens to be
 *     calling getUrl(). That's a real ANR/hang risk and unnecessary: the
 *     actual request URL is always derived from the `url` parameter passed
 *     in via URI(url), not from `mainUrl` — this property was never used
 *     for building requests, only for cloudstream's domain bookkeeping. So
 *     it's now a plain constant with no network call at all.
 *  2. Added a size gate right after scraping `size`, before iterating any
 *     buttons — this is the primary fix for "don't play 10/20GB files."
 *  3. Fixed getIndexQuality's fallback: unknown quality now correctly maps
 *     to Qualities.Unknown instead of Qualities.P2160 (was mislabeling
 *     unparseable/low quality links as 4K).
 *  4. Fixed PixelDrain ID extraction to use a proper regex instead of
 *     substringAfterLast("/"), which broke on links with query strings.
 *  5. Wrapped the whole button-processing loop per-element in try/catch so
 *     one malformed button doesn't abort every other link on the page.
 */
class HubCloud : ExtractorApi() {

    override val name = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.foo"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val tag = "HubCloud"
        val ref = referer.orEmpty()

        val uri = runCatching { URI(url) }.getOrElse {
            Log.e(tag, "Invalid URL: ${it.message}")
            return
        }

        val realUrl = uri.toString()
        val baseUrl = "${uri.scheme}://${uri.host}"

        val href = runCatching {
            if ("hubcloud.php" in realUrl) {
                realUrl
            } else {
                val raw = app.get(realUrl, timeout = 15000L).document
                    .selectFirst("#download")
                    ?.attr("href")
                    .orEmpty()

                if (raw.isBlank()) ""
                else if (raw.startsWith("http", true)) raw
                else baseUrl.trimEnd('/') + "/" + raw.trimStart('/')
            }
        }.getOrElse {
            Log.e(tag, "Failed to extract href: ${it.message}")
            ""
        }

        if (href.isBlank()) {
            Log.e(tag, "No downstream href resolved for $url")
            return
        }

        val document = try {
            app.get(href, timeout = 15000L).document
        } catch (e: Exception) {
            Log.e(tag, "Failed to load $href: ${e.message}")
            return
        }

        val size = document.selectFirst("i#size")?.text().orEmpty()
        val header = document.selectFirst("div.card-header")?.text().orEmpty()

        // --- FILE SIZE GATE ---------------------------------------------------
        // Skip the whole quality entry outright if it's over the configured cap.
        // This is checked once per page since one HubCloud page = one file/quality.
        if (isFileTooLarge(size)) {
            Log.w(tag, "Skipping oversized file ($size) from $href")
            return
        }

        val headerDetails = cleanTitle(header)
        val quality = getIndexQuality(header)

        val labelExtras = buildString {
            if (headerDetails.isNotEmpty()) append("[$headerDetails]")
            if (size.isNotEmpty()) append("[$size]")
        }

        // ------------------------------------------------------------------
        // FIX: HubCloud's mirror page doesn't always put the real download
        // target in the button's href attribute. Confirmed markup example:
        //     <script>var url="{dynamic url}";</script>
        // A button (commonly Pixeldrain) is wired up via onclick to read
        // that JS variable and navigate to it, leaving href blank, "#", or
        // "javascript:void(0)". Since we only ever parse static HTML (no JS
        // execution), the old code's `if (link.isBlank()) return@amap` threw
        // that button away before it was even inspected — so Pixeldrain (or
        // whichever button used this pattern) silently never produced a
        // link. This scans every <script> tag once per page for
        // `var name = "value";` declarations so any button can fall back to
        // one of them when its href isn't directly usable.
        // ------------------------------------------------------------------
        val scriptVars: Map<String, String> = buildMap {
            val varRegex = Regex("""var\s+(\w+)\s*=\s*["']([^"']+)["']""")
            document.select("script").forEach { script ->
                varRegex.findAll(script.data()).forEach { m ->
                    put(m.groupValues[1], m.groupValues[2])
                }
            }
        }

        /** Resolves a button's real target: its href if usable, else a JS `var` fallback. */
        fun resolveLink(element: org.jsoup.nodes.Element): String {
            val href = element.attr("href")
            val isUsable = href.isNotBlank() && href != "#" && !href.startsWith("javascript", ignoreCase = true)
            if (isUsable) return href

            // No usable href — look for the target in an onclick handler first
            // (e.g. onclick="location.href=url" or onclick="download('url_var')"),
            // then fall back to common variable names seen on these pages.
            val onclick = element.attr("onclick")
            val referencedVar = Regex("""\b(\w*url\w*|\w*link\w*)\b""", RegexOption.IGNORE_CASE)
                .find(onclick)?.groupValues?.get(1)

            return referencedVar?.let { scriptVars[it] }
                ?: scriptVars["url"]
                ?: scriptVars["link"]
                ?: scriptVars["dlink"]
                ?: scriptVars["downloadUrl"]
                ?: ""
        }

        document.select("a.btn, button.btn").amap { element ->
            try {
                val link = resolveLink(element)
                if (link.isBlank()) {
                    Log.w(tag, "Skipping button with no resolvable href or script var: ${element.ownText()}")
                    return@amap
                }
                val label = element.ownText().lowercase()

                when {
                    "fsl server" in label -> callback(
                        newExtractorLink(
                            "$ref [FSL Server]",
                            "$ref [FSL Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )

                    "download file" in label -> callback(
                        newExtractorLink(
                            ref,
                            "$ref $labelExtras",
                            link
                        ) { this.quality = quality }
                    )

                    "buzzserver" in label -> {
                        val resp = app.get("$link/download", referer = link, allowRedirects = false, timeout = 15000L)
                        val dlink = resp.headers["hx-redirect"] ?: resp.headers["HX-Redirect"]

                        if (!dlink.isNullOrBlank()) {
                            callback(
                                newExtractorLink(
                                    "$ref [BuzzServer]",
                                    "$ref [BuzzServer] $labelExtras",
                                    dlink
                                ) { this.quality = quality }
                            )
                        } else {
                            Log.w(tag, "BuzzServer: No redirect for $link")
                        }
                    }

                    "pixeldra" in label || "pixelserver" in label || "pixel server" in label || "pixeldrain" in label -> {
                        // FIX: `link` here may now be a raw pixeldrain page URL (from
                        // href) OR a URL pulled straight from a JS variable — either a
                        // page URL like ".../u/ABC123" or already a direct API URL like
                        // ".../api/file/ABC123?download". Handle all three shapes.
                        val base = getBaseUrl(link)
                        val fileId = Regex("""/u/([A-Za-z0-9]+)""").find(link)?.groupValues?.get(1)
                            ?: Regex("""/api/file/([A-Za-z0-9]+)""").find(link)?.groupValues?.get(1)
                            ?: link.substringAfterLast("/").substringBefore("?")

                        val finalUrl = when {
                            "download" in link -> link
                            "/api/file/" in link -> link
                            fileId.isNotBlank() && base.isNotBlank() -> "$base/api/file/$fileId?download"
                            else -> link
                        }

                        callback(
                            newExtractorLink(
                                "$ref Pixeldrain",
                                "$ref Pixeldrain $labelExtras",
                                finalUrl
                            ) { this.quality = quality }
                        )
                    }

                    "s3 server" in label -> callback(
                        newExtractorLink(
                            "$ref [S3 Server]",
                            "$ref [S3 Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )

                    "fslv2" in label -> callback(
                        newExtractorLink(
                            "$ref [FSLv2]",
                            "$ref [FSLv2] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )

                    "mega server" in label -> callback(
                        newExtractorLink(
                            "$ref [Mega Server]",
                            "$ref [Mega Server] $labelExtras",
                            link
                        ) { this.quality = quality }
                    )

                    else -> loadExtractor(link, "", subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.e(tag, "Failed to process button element: ${e.message}")
            }
        }
    }

    private fun getIndexQuality(str: String?): Int {
        return Regex("(\\d{3,4})[pP]")
            .find(str.orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            // FIX: was defaulting to Qualities.P2160.value (4K!) whenever the
            // quality couldn't be parsed, which mislabeled unknown/low quality
            // links as 4K. Correct behavior is to mark it Unknown.
            ?: Qualities.Unknown.value
    }

    private fun getBaseUrl(url: String): String {
        return runCatching {
            URI(url).let { "${it.scheme}://${it.host}" }
        }.getOrDefault("")
    }

    private fun cleanTitle(title: String): String {
        val name = title.replace(Regex("\\.[a-zA-Z0-9]{2,4}$"), "")

        val normalized = name
            .replace(Regex("WEB[-_. ]?DL", RegexOption.IGNORE_CASE), "WEB-DL")
            .replace(Regex("WEB[-_. ]?RIP", RegexOption.IGNORE_CASE), "WEBRIP")
            .replace(Regex("H[ .]?265", RegexOption.IGNORE_CASE), "H265")
            .replace(Regex("H[ .]?264", RegexOption.IGNORE_CASE), "H264")
            .replace(Regex("DDP[ .]?([0-9]\\.[0-9])", RegexOption.IGNORE_CASE), "DDP$1")

        val parts = normalized.split(" ", "_", ".")

        val sourceTags = setOf(
            "WEB-DL", "WEBRIP", "BLURAY", "HDRIP",
            "DVDRIP", "HDTV", "CAM", "TS", "BRRIP", "BDRIP"
        )
        val codecTags = setOf("H264", "H265", "X264", "X265", "HEVC", "AVC")
        val audioTags = setOf("AAC", "AC3", "DTS", "MP3", "FLAC", "DD", "DDP", "EAC3")
        val audioExtras = setOf("ATMOS")
        val hdrTags = setOf("SDR", "HDR", "HDR10", "HDR10+", "DV", "DOLBYVISION")

        val filtered = parts.mapNotNull { part ->
            val p = part.uppercase()
            when {
                sourceTags.contains(p) -> p
                codecTags.contains(p) -> p
                audioTags.any { p.startsWith(it) } -> p
                audioExtras.contains(p) -> p
                hdrTags.contains(p) -> if (p == "DV") "DOLBYVISION" else p
                p == "NF" || p == "CR" -> p
                else -> null
            }
        }

        return filtered.distinct().joinToString(" ")
    }
}

class HUBCDN : ExtractorApi() {
    override val name = "HUBCDN"
    override val mainUrl = "https://hubcdn.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = try {
            app.get(url, timeout = 15000L).document
        } catch (e: Exception) {
            Log.e("HUBCDN", "Failed to load $url: ${e.message}")
            return
        }

        val scriptText = doc.selectFirst("script:containsData(var reurl)")?.data()

        val encodedUrl = Regex("reurl\\s*=\\s*\"([^\"]+)\"")
            .find(scriptText ?: "")
            ?.groupValues?.get(1)
            ?.substringAfter("?r=")

        if (encodedUrl.isNullOrBlank()) {
            Log.e("HUBCDN", "reurl not found for $url")
            return
        }

        val decodedUrl = runCatching { base64Decode(encodedUrl) }.getOrNull()
            ?.substringAfterLast("link=")

        if (decodedUrl.isNullOrBlank()) {
            Log.e("HUBCDN", "Failed to decode reurl for $url")
            return
        }

        callback(
            newExtractorLink(
                this.name,
                this.name,
                decodedUrl,
                INFER_TYPE,
            ) {
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
