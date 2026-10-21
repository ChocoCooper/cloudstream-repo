package com.skybap

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI
import java.security.MessageDigest

// =====================================================================
// Shared constants + helpers
// =====================================================================

private const val SKYBAP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
    "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

object SkyBapSettings {
    var allowDownloadLinks = false
}

fun skybapGetBaseUrl(url: String): String = try {
    URI(url).let { "${it.scheme}://${it.host}" }
} catch (_: Exception) { url }

fun skybapGetIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)
        ?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lower = str.lowercase()
    return when {
        lower.contains("8k") -> 4320
        lower.contains("4k") -> 2160
        lower.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

// FIX: shared browser header builder used by every CDN extractor below.
// Cloudflare Workers (myfiles.directfile2.workers.dev), StreamTape
// (tpead.net/get_video), and pixel.hubcloud.ist all require a browser
// User-Agent and a Referer, otherwise they return HTML (which ExoPlayer
// reports as UnrecognizedInputFormatException) or HTTP 403.
private fun skybapBrowserHeaders(referer: String?): Map<String, String> {
    val h = mutableMapOf(
        "User-Agent" to SKYBAP_USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "en-US,en;q=0.9",
        "Sec-Fetch-Dest" to "video",
        "Sec-Fetch-Mode" to "no-cors",
        "Sec-Fetch-Site" to "cross-site",
    )
    if (!referer.isNullOrBlank()) h["Referer"] = referer
    return h
}

private fun skybapBase64Decode(str: String): String = try {
    String(android.util.Base64.decode(str, android.util.Base64.DEFAULT))
} catch (_: Exception) {
    try { String(java.util.Base64.getDecoder().decode(str)) }
    catch (_: Exception) { "" }
}

fun skybapNormalizePixeldrain(url: String): String? {
    if (!url.contains("pixeldra", ignoreCase = true)) return null
    if (url.contains("/api/file/") && url.contains("?download")) return url
    val id = url.substringAfterLast("/").substringBefore("?").substringBefore("#")
    if (id.isBlank() || !id.matches(Regex("[A-Za-z0-9]+"))) return null
    return "https://pixeldrain.com/api/file/$id?download"
}

suspend fun skybapResolveFinalUrl(startUrl: String): String? {
    var currentUrl = startUrl
    var loopCount = 0
    while (loopCount < 7) {
        try {
            val res = app.get(currentUrl, allowRedirects = false, timeout = 2500L)
            if (res.code in 200..399) currentUrl = res.headers["Location"] ?: break
            else return null
            loopCount++
        } catch (_: Exception) { return null }
    }
    return currentUrl
}

suspend fun <A, B> Iterable<A>.skybapSafeAmap(
    concurrency: Int = 6,
    f: suspend (A) -> B?
): List<B> = coroutineScope {
    val semaphore = Semaphore(concurrency)
    map { item -> async { semaphore.withPermit {
        try { f(item) } catch (e: Exception) {
            Log.e("SkyBapExtractor", "Item failed: $item - ${e.message}"); null
        }
    }}}.awaitAll().filterNotNull()
}

// =====================================================================
// Generic Cloudflare Workers CDN — catches *.workers.dev
// FIX: this is what was returning HTTP 403 in the log
// (myfiles.directfile2.workers.dev). We now always send a Referer.
// =====================================================================

class SkyBapWorkersDev : ExtractorApi() {
    override val name = "WorkersCDN"
    override val mainUrl = "https://*.workers.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Prefer the panel referer passed in; fall back to a plausible
        // gdflix referer, which is what these Workers are provisioned for.
        val ref = referer?.takeIf { it.isNotBlank() } ?: "https://gdflix.io/"
        callback.invoke(
            newExtractorLink(name, "[Workers CDN]", url, ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
                this.referer = ref
                this.headers = skybapBrowserHeaders(ref)
            }
        )
    }
}

// =====================================================================
// Pixeldrain — mainUrl uses wildcard so one extractor catches .com & .dev
// =====================================================================

class SkyBapPixeldrain : ExtractorApi() {
    override val name = "Pixeldrain"
    override val mainUrl = "https://pixeldrain.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val dl = skybapNormalizePixeldrain(url) ?: return
        callback.invoke(
            newExtractorLink(name, "[Pixeldrain] Direct", dl, ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
                this.headers = skybapBrowserHeaders("https://pixeldrain.com/")
            }
        )
    }
}

// =====================================================================
// pixel.hubcloud.ist — direct 10 Gbps download
// FIX: set Referer + UA so the CDN accepts the request.
// =====================================================================

class SkyBapPixelHubcloud : ExtractorApi() {
    override val name = "PixelHubCloud"
    override val mainUrl = "https://pixel.hubcloud.ist"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer?.takeIf { it.isNotBlank() } ?: "https://hubcloud.ist/"
        callback.invoke(
            newExtractorLink(name, "[HubCloud 10Gbps]", url, ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
                this.referer = ref
                this.headers = skybapBrowserHeaders(ref)
            }
        )
    }
}

// =====================================================================
// instant.busycdn.xyz — signed direct download
// FIX: always send Referer + UA. Default referer points at gdflix.
// =====================================================================

class SkyBapBusyCdn : ExtractorApi() {
    override val name = "BusyCDN"
    override val mainUrl = "https://instant.busycdn.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val ref = referer?.takeIf { it.isNotBlank() } ?: "https://gdflix.io/"
        callback.invoke(
            newExtractorLink(name, "[Instant DL]", url, ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
                this.referer = ref
                this.headers = skybapBrowserHeaders(ref)
            }
        )
    }
}

// =====================================================================
// goflix.sbs — mirror page and /download-fast/… are both handled
// =====================================================================

class SkyBapGoflix : ExtractorApi() {
    override val name = "Goflix"
    override val mainUrl = "https://goflix.sbs"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.contains("/download-fast/")) {
            val filename = url.substringAfterLast("/")
            callback.invoke(
                newExtractorLink(name, "[Goflix] $filename", url, ExtractorLinkType.VIDEO) {
                    this.quality = skybapGetIndexQuality(filename)
                    this.referer = "https://goflix.sbs/"
                    this.headers = skybapBrowserHeaders("https://goflix.sbs/")
                }
            )
            return
        }
        val doc = app.get(url, referer = referer ?: "https://goflix.sbs/").document
        doc.select("a[href]").skybapSafeAmap { a ->
            val href = a.attr("href")
            when {
                href.contains("/download-fast/") -> {
                    val filename = href.substringAfterLast("/")
                    callback.invoke(
                        newExtractorLink(name, "[Goflix] $filename", href, ExtractorLinkType.VIDEO) {
                            this.quality = skybapGetIndexQuality(filename)
                            this.referer = "https://goflix.sbs/"
                            this.headers = skybapBrowserHeaders("https://goflix.sbs/")
                        }
                    )
                }
                href.contains("gofile.io/d/") -> loadExtractor(href, url, subtitleCallback, callback)
                href.contains("pixeldra") -> skybapNormalizePixeldrain(href)?.let { dl ->
                    callback.invoke(
                        newExtractorLink(name, "[Goflix→Pixeldrain]", dl, ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.Unknown.value
                            this.headers = skybapBrowserHeaders("https://pixeldrain.com/")
                        }
                    )
                }
                else -> null
            }
        }
    }
}

// =====================================================================
// gamerxyt.com/hubcloud.php — generator page
// FIX: emit links with proper referer + headers so downstream CDN
// (myfiles.directfile2.workers.dev) accepts them.
// =====================================================================

class SkyBapGamerxyt : ExtractorApi() {
    override val name = "Gamerxyt"
    override val mainUrl = "https://gamerxyt.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val pageHtml = doc.toString()
        val originRef = url  // the gamerxyt page itself is the correct referer

        // (a) pixeldrain.dev/u/<id>
        Regex("""https?://pixeldrain\.(?:com|dev)/u/([A-Za-z0-9]+)""")
            .findAll(pageHtml).forEach { m ->
                val dl = "https://pixeldrain.com/api/file/${m.groupValues[1]}?download"
                callback.invoke(
                    newExtractorLink("Pixeldrain", "[Pixeldrain]", dl, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                        this.headers = skybapBrowserHeaders("https://pixeldrain.com/")
                    }
                )
            }

        // (b) pixel.hubcloud.ist/?id=…
        Regex("""https?://pixel\.hubcloud\.ist/\?id=[^\s"'<>]+""")
            .findAll(pageHtml).forEach { m ->
                val link = m.value.replace("&amp;", "&")
                callback.invoke(
                    newExtractorLink("HubCloud", "[HubCloud 10Gbps]",
                        link, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                        this.referer = originRef
                        this.headers = skybapBrowserHeaders(originRef)
                    }
                )
            }

        // (c) /download-fast/… links
        doc.select("a[href*=download-fast]").skybapSafeAmap { a ->
            val link = a.attr("href")
            callback.invoke(
                newExtractorLink("Goflix", "[Goflix]", link, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "https://goflix.sbs/"
                    this.headers = skybapBrowserHeaders("https://goflix.sbs/")
                }
            )
        }

        // (d) Workers.dev links can also appear on the generator page directly
        Regex("""https?://[^\s"'<>]+\.workers\.dev/[^\s"'<>]+""")
            .findAll(pageHtml).forEach { m ->
                val link = m.value
                callback.invoke(
                    newExtractorLink("WorkersCDN", "[Workers CDN]",
                        link, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.Unknown.value
                        this.referer = originRef
                        this.headers = skybapBrowserHeaders(originRef)
                    }
                )
            }
    }
}

// =====================================================================
// HubCloud — /drive/ pages + /video/ pages
// =====================================================================

open class SkyBapHubCloud : ExtractorApi() {
    override val name = "Hub-Cloud"
    override val mainUrl = "https://hubcloud.*"
    override val requiresReferer = false

    private fun extractPxlUrl(html: String): String? =
        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

    private fun extractDoubleAtob(html: String): String? =
        Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
            .find(html)?.groupValues?.get(1)
            ?.let { skybapBase64Decode(skybapBase64Decode(it)) }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.contains("/drive/")) {
            val doc = app.get(url, referer = referer).document
            val gen = doc.selectFirst("a[href*=gamerxyt.com/hubcloud.php]")?.attr("href")
            if (!gen.isNullOrBlank()) {
                loadExtractor(gen, url, subtitleCallback, callback)
                return
            }
        }

        val doc = app.get(url, referer = referer).document
        val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""

        var link = if (url.contains("/video/")) {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        } else if (url.contains("vcloud")) {
            extractDoubleAtob(scriptTag) ?: ""
        } else {
            Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
        }

        if (link.isBlank()) return
        if (!link.startsWith("https://")) link = skybapGetBaseUrl(url) + link

        val sub = app.get(link, referer = url).document
        val header = sub.select("div.card-header").text()
        val size = sub.select("i#size").text()
        val quality = skybapGetIndexQuality(header)
        val originRef = url

        suspend fun cb(href: String, server: String = "") {
            callback.invoke(
                newExtractorLink("$name$server", "$name$server $header[$size]",
                    href, ExtractorLinkType.VIDEO) {
                    this.quality = quality
                    this.referer = originRef
                    this.headers = skybapBrowserHeaders(originRef)
                }
            )
        }

        sub.select("h2 a.btn").skybapSafeAmap { el ->
            val href = el.attr("href")
            val text = el.text()
            when {
                text.contains("FSL Server") -> cb(href, "[FSL Server]")
                text.contains("FSLv2") -> cb(href, "[FSLv2]")
                text.contains("Mega Server") -> cb(href, "[Mega]")
                text.contains("Download File") -> cb(href)

                href.contains("pixeldra") -> {
                    val pixelLink = extractPxlUrl(sub.toString()) ?: href
                    skybapNormalizePixeldrain(pixelLink)?.let { cb(it, "[Pixeldrain]") }
                }
                text.contains("Gofile") -> loadExtractor(href, url, subtitleCallback, callback)

                SkyBapSettings.allowDownloadLinks && text.contains("10Gbps") -> {
                    var redirect = skybapResolveFinalUrl(href) ?: return@skybapSafeAmap null
                    if (redirect.contains("link=")) redirect = redirect.substringAfter("link=")
                    cb(redirect, "[10Gbps]")
                }
                else -> Log.d("SkyBapHubCloud", "No server matched: $text")
            }
        }
    }
}

class SkyBapVCloud : SkyBapHubCloud() {
    override val name = "V-Cloud"
    override val mainUrl = "https://vcloud.*"
}

// =====================================================================
// GDFlix family
// =====================================================================

class SkyBapGDLink : SkyBapGDFlix() { override var mainUrl = "https://gdlink.*" }
class SkyBapGDFlixApp : SkyBapGDFlix() { override var mainUrl = "https://new.gdflix.*" }
class SkyBapGdFlix1 : SkyBapGDFlix() { override var mainUrl = "https://new1.gdflix.*" }
class SkyBapGdFlix2 : SkyBapGDFlix() { override var mainUrl = "https://*.gdflix.*" }

open class SkyBapGDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val fileName = doc.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = doc.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")
        val quality = skybapGetIndexQuality(fileName)
        val baseUrl = skybapGetBaseUrl(url)
        val originRef = url

        suspend fun cb(href: String, server: String = "") {
            callback.invoke(
                newExtractorLink("$name$server", "$name$server $fileName[$fileSize]",
                    href, ExtractorLinkType.VIDEO) {
                    this.quality = quality
                    this.referer = originRef
                    this.headers = skybapBrowserHeaders(originRef)
                }
            )
        }

        doc.select("div.text-center a, a[data-mdb-ripple-color]").skybapSafeAmap { a ->
            val text = a.text()
            val href = a.attr("href")

            when {
                href.contains("instant.busycdn.xyz") -> cb(href, "[Instant DL]")
                text.contains("DIRECT DL") || text.contains("DIRECT SERVER") -> cb(href, "[Direct]")
                text.contains("FSL V2") -> cb(href, "[FSL V2]")
                text.contains("CLOUD DOWNLOAD [R2]") -> cb(href, "[R2]")

                href.contains("goflix.sbs") || text.contains("GoFile", true)
                    || text.contains("Multiup", true) ->
                    loadExtractor(href, url, subtitleCallback, callback)

                href.contains("pixeldra") ->
                    skybapNormalizePixeldrain(href)?.let { cb(it, "[Pixeldrain]") }

                href.contains("hubcloud.") ->
                    loadExtractor(href, url, subtitleCallback, callback)

                // FIX: catch any Workers.dev direct link on the gdflix page
                href.contains(".workers.dev") -> cb(href, "[Workers CDN]")

                text.contains("FAST CLOUD", true) || text.contains("ZIPDISK", true) -> {
                    val cloudDoc = app.get("$baseUrl$href", referer = url).document
                    val dl = cloudDoc.selectFirst("div.card-body a[href], a.btn-success[href]")
                        ?.attr("href")
                    if (!dl.isNullOrBlank()) cb(dl, "[FastCloud]")
                }

                text.contains("GD Index") -> {
                    val cfLink = baseUrl + href
                    listOf("1", "2").forEach { t ->
                        app.get("$cfLink?type=$t", referer = url).document
                            .select("a.btn-success")
                            .skybapSafeAmap { cb(it.attr("href"), "[CF]") }
                    }
                }

                else -> Log.d("SkyBapGDFlix", "No server matched: $text")
            }
        }
    }
}

// =====================================================================
// HubDrive
// =====================================================================

open class SkyBapHubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val mirror = doc.selectFirst("a[href*=hubcloud.ist/drive]")?.attr("href")
        if (!mirror.isNullOrBlank()) {
            loadExtractor(mirror, url, subtitleCallback, callback)
            return
        }
        val href = doc.select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
        if (href.isNotBlank()) loadExtractor(href, url, subtitleCallback, callback)
    }
}

// =====================================================================
// Driveleech / Driveseed
// =====================================================================

class SkyBapDriveseed : SkyBapDriveleech() {
    override val name = "Driveseed"
    override val mainUrl = "https://driveseed.*"
}

open class SkyBapDriveleech : ExtractorApi() {
    override val name = "Driveleech"
    override val mainUrl = "https://driveleech.*"
    override val requiresReferer = false

    private suspend fun cfType(url: String): List<String> {
        val out = mutableListOf<String>()
        listOf("1", "2").forEach { t ->
            val d = app.get("$url?type=$t").document
            out += d.select("a.btn-success").mapNotNull { it.attr("href") }
        }
        return out
    }

    private suspend fun resumeCloudLink(baseUrl: String, path: String): String? =
        app.get(baseUrl + path).document.selectFirst("a.btn-success")?.attr("href")

    private suspend fun instantLink(finalLink: String): String? =
        app.get(finalLink, allowRedirects = false).headers["location"]?.substringAfter("?url=")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = skybapGetBaseUrl(url)
        val document = if (url.contains("r?key=")) {
            val temp = app.get(url).document.selectFirst("script")?.data()
                ?.substringAfter("replace(\"")?.substringBefore("\")") ?: ""
            app.get(baseUrl + temp).document
        } else app.get(url).document

        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")
        val quality = skybapGetIndexQuality(fileName)
        val originRef = url

        suspend fun cb(link: String, server: String = "") {
            callback.invoke(
                newExtractorLink("$name$server", "$name$server $fileName[$fileSize]",
                    link, ExtractorLinkType.VIDEO) {
                    this.quality = quality
                    this.referer = originRef
                    this.headers = skybapBrowserHeaders(originRef)
                }
            )
        }

        document.select("div.text-center > a").skybapSafeAmap { el ->
            val text = el.text()
            val href = el.attr("href")
            when {
                text.contains("Cloud Download") -> cb(href, "[Cloud]")
                text.contains("Instant Download") && SkyBapSettings.allowDownloadLinks ->
                    instantLink(href)?.let { cb(it, "[Instant]") }
                text.contains("Direct Links") -> cfType(baseUrl + href).forEach { cb(it, "[CF]") }
                text.contains("Resume Cloud") ->
                    resumeCloudLink(baseUrl, href)?.let { cb(it, "[ResumeCloud]") }
                text.contains("gofile", true) -> loadExtractor(href, url, subtitleCallback, callback)
                else -> Log.d("SkyBapDriveleech", "No server matched: $text")
            }
        }
    }
}

// =====================================================================
// Gofile
// =====================================================================

class SkyBapGofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    private val mainApi = "https://api.gofile.io"
    private val browserLanguage = "en-US"
    private val secret = "5d4f7g8sd45fsd"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("""/(?:\?c=|d/)([A-Za-z0-9-]+)""")
            .find(url)?.groupValues?.get(1) ?: return

        val websiteToken = generateWebsiteToken(SKYBAP_USER_AGENT, "")
        val token = app.post(
            "$mainApi/accounts",
            headers = mapOf("X-Website-Token" to websiteToken, "X-BL" to browserLanguage)
        ).parsedSafe<AccountResponse>()?.data?.token ?: return

        val hashedToken = generateWebsiteToken(SKYBAP_USER_AGENT, token)
        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to SKYBAP_USER_AGENT,
            "Authorization" to "Bearer $token",
            "X-BL" to browserLanguage,
            "X-Website-Token" to hashedToken
        )

        val parsed = app.get(
            "$mainApi/contents/$id?cache=true&sortField=createTime&sortDirection=1",
            headers = headers
        ).parsedSafe<GofileResponse>() ?: return

        val children = parsed.data?.children ?: return
        for ((_, file) in children) {
            if (file.link.isNullOrEmpty() || file.type != "file") continue
            val fileName = file.name ?: ""
            callback.invoke(
                newExtractorLink(
                    "Gofile",
                    "[Gofile] $fileName [${formatBytes(file.size ?: 0L)}]",
                    file.link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = skybapGetIndexQuality(fileName)
                    this.headers = skybapBrowserHeaders("https://gofile.io/") +
                        mapOf("Cookie" to "accountToken=$token")
                }
            )
        }
    }

    private fun generateWebsiteToken(userAgent: String, accountToken: String): String {
        val timeSlot = System.currentTimeMillis() / 1000 / 14400
        val raw = "$userAgent::$browserLanguage::$accountToken::$timeSlot::$secret"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L * 1024 * 1024 -> "%.2f MB".format(bytes.toDouble() / (1024 * 1024))
        else -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
    }

    data class AccountResponse(@param:JsonProperty("data") val data: AccountData? = null)
    data class AccountData(@param:JsonProperty("token") val token: String? = null)
    data class GofileResponse(@param:JsonProperty("data") val data: GofileData? = null)
    data class GofileData(
        @param:JsonProperty("children") val children: Map<String, GofileFile>? = null
    )
    data class GofileFile(
        @param:JsonProperty("type") val type: String? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("link") val link: String? = null,
        @param:JsonProperty("size") val size: Long? = 0L
    )
}

// =====================================================================
// Howblogs
// =====================================================================

open class SkyBapHowblogs : ExtractorApi() {
    override val name = "Howblogs"
    override val mainUrl = "https://howblogs.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url, referer = referer).document
            .select("div.center_it a[href]")
            .skybapSafeAmap { a ->
                val href = a.attr("href")
                if (href.isNotBlank() && href.startsWith("http")) {
                    loadExtractor(href, url, subtitleCallback, callback)
                }
            }
    }
}

class SkyBapHowblogsSub : SkyBapHowblogs() {
    override val mainUrl = "https://*.howblogs.*"
}

// =====================================================================
// StreamTape aliases — FIX: always send Referer = mainUrl
// =====================================================================

class SkyBapTpead : SkyBapStreamTapeAlias() {
    override val name = "Tpead"
    override val mainUrl = "https://tpead.net"
}

class SkyBapAdvtpe : SkyBapStreamTapeAlias() {
    override val name = "Advtpe"
    override val mainUrl = "https://advtpe.*"
}

open class SkyBapStreamTapeAlias : ExtractorApi() {
    override val name = "StreamTape"
    override val mainUrl = "https://tpead.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc = app.get(url, referer = referer).document
        val script = doc.select("script").map { it.data() }
            .firstOrNull { it.contains("robotlink") } ?: return

        val first = Regex("""robotlink'\)\.innerHTML\s*=\s*'([^']*)'""")
            .find(script)?.groupValues?.get(1) ?: return
        val second = Regex("""\+\s*\('([^']*)'\)""")
            .find(script)?.groupValues?.get(1)

        val fullPath = if (!second.isNullOrBlank()) first + second else first
        val finalUrl = if (fullPath.startsWith("http")) fullPath else "https:$fullPath"

        // FIX: StreamTape's /get_video returns HTML when Referer is missing,
        // which surfaces as UnrecognizedInputFormatException in ExoPlayer.
        // Always send the page's own host as Referer.
        val pageHost = try {
            val u = URI(url)
            "${u.scheme}://${u.host}"
        } catch (_: Exception) { mainUrl }

        callback.invoke(
            newExtractorLink(name, name, finalUrl, ExtractorLinkType.VIDEO) {
                this.quality = Qualities.Unknown.value
                this.referer = pageHost
                this.headers = skybapBrowserHeaders(pageHost)
            }
        )
    }
}
