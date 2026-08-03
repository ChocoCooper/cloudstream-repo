package com.skybap

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.Qualities
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import com.fasterxml.jackson.annotation.JsonProperty
import java.net.URI
import java.security.MessageDigest

/**
 * These extractor classes are not part of Cloudstream's core extractor set,
 * so they're registered explicitly by SkyBapPlugin.load() via
 * registerExtractorAPI(...). Once registered, the ordinary loadExtractor()
 * call in SkyBapProvider will route to whichever of these matches a link's
 * domain automatically - no manual dispatch needed.
 */

// Toggle for extra "instant download" style branches that resolve a
// redirect chain rather than a normal stream/download page. Off by default
// since they add extra requests; flip to true if you want them surfaced.
// Local replacements for helpers that aren't guaranteed to be visible as
// top-level symbols across Cloudstream builds - safer to define our own
// than depend on an import path that may differ between app versions.
private const val SKYBAP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

private fun skybapBase64Decode(str: String): String {
    return try {
        String(android.util.Base64.decode(str, android.util.Base64.DEFAULT))
    } catch (e: Exception) {
        try {
            String(java.util.Base64.getDecoder().decode(str))
        } catch (e2: Exception) {
            ""
        }
    }
}

object SkyBapSettings {
    var allowDownloadLinks = false
}

// ---------------------------------------------------------------------
// Shared helpers
// ---------------------------------------------------------------------

fun skybapGetBaseUrl(url: String): String {
    return try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (e: Exception) {
        url
    }
}

fun skybapGetIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value

    Regex("""(\d{3,4})[pP]""").find(str).let {
        it?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { q -> return q }
    }

    val lower = str.lowercase()
    return when {
        lower.contains("8k") -> 4320
        lower.contains("4k") -> 2160
        lower.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

/**
 * Public domain-rotation registry maintained for common file-host mirrors
 * (hubcloud, gdflix, vcloud, etc). These hosts frequently change their
 * primary domain, so most scrapers - including this one - resolve the
 * current domain from this shared list rather than hardcoding one.
 */
suspend fun skybapGetLatestBaseUrl(baseUrl: String, source: String): String {
    return try {
        val dynamicUrls = app.get(
            "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json"
        ).parsedSafe<Map<String, String>>()
        dynamicUrls?.get(source)?.takeIf { it.isNotBlank() } ?: baseUrl
    } catch (e: Exception) {
        baseUrl
    }
}

suspend fun skybapResolveFinalUrl(startUrl: String): String? {
    var currentUrl = startUrl
    var loopCount = 0
    val maxRedirects = 7

    while (loopCount < maxRedirects) {
        try {
            val res = app.get(currentUrl, allowRedirects = false, timeout = 2500L)
            if (res.code == 200 || res.code in 300..399) {
                val location = res.headers["Location"] ?: break
                currentUrl = location
            } else {
                return null
            }
            loopCount++
        } catch (e: Exception) {
            return null
        }
    }
    return currentUrl
}

/** Bounded-concurrency map that swallows per-item failures instead of failing the batch. */
suspend fun <A, B> Iterable<A>.skybapSafeAmap(
    concurrency: Int = 6,
    f: suspend (A) -> B?
): List<B> = coroutineScope {
    val semaphore = Semaphore(concurrency)
    map { item ->
        async {
            semaphore.withPermit {
                try {
                    f(item)
                } catch (e: Exception) {
                    Log.e("SkyBapExtractor", "Item failed: $item - ${e.message}")
                    null
                }
            }
        }
    }.awaitAll().filterNotNull()
}

// ---------------------------------------------------------------------
// HubCloud / VCloud
// ---------------------------------------------------------------------

class SkyBapVCloud : SkyBapHubCloud() {
    override val name: String = "V-Cloud"
    override val mainUrl: String = "https://vcloud.*"
}

open class SkyBapHubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.*"
    override val requiresReferer = false

    private fun extractPxlUrl(html: String): String? {
        val regex = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
        return regex.find(html)?.groupValues?.get(1)
    }

    private fun extractDoubleAtob(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
        return regex.find(html)?.groupValues?.get(1)?.let { skybapBase64Decode(skybapBase64Decode(it)) }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var baseUrl = skybapGetBaseUrl(url)
        val latestBaseUrl = if (url.contains("hubcloud")) {
            skybapGetLatestBaseUrl(baseUrl, "hubcloud")
        } else {
            skybapGetLatestBaseUrl(baseUrl, "vcloud")
        }

        var newUrl = url
        if (baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val doc = app.get(newUrl).document

        var link = if (newUrl.contains("/video/")) {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        } else {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""
            if (newUrl.contains("vcloud")) {
                extractDoubleAtob(scriptTag) ?: ""
            } else {
                Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
            }
        }

        if (!link.startsWith("https://")) link = baseUrl + link
        if (link.isBlank()) return

        val document = app.get(link).document
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val quality = skybapGetIndexQuality(header)

        suspend fun myCallback(link: String, server: String = "") {
            callback.invoke(
                newExtractorLink(
                    "$name$server",
                    "$name$server $header[$size]",
                    link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }

        document.select("h2 a.btn").skybapSafeAmap { el ->
            val href = el.attr("href")
            val text = el.text()

            when {
                text.contains("FSL Server") -> myCallback(href, "[FSL Server]")
                text.contains("FSLv2") -> myCallback(href, "[FSLv2 Server]")
                text.contains("Mega Server") -> myCallback(href, "[Mega Server]")
                text.contains("Download File") -> myCallback(href)
                href.contains("pixeldra") -> {
                    val pixelLink = extractPxlUrl(document.toString()) ?: return@skybapSafeAmap null
                    val baseUrlLink = skybapGetBaseUrl(pixelLink)
                    val finalURL = if (pixelLink.contains("download", true)) pixelLink
                    else "$baseUrlLink/api/file/${pixelLink.substringAfterLast("/")}?download"
                    myCallback(finalURL, "[Pixeldrain]")
                }
                SkyBapSettings.allowDownloadLinks && text.contains("Server : 10Gbps") -> {
                    var redirectUrl = skybapResolveFinalUrl(href) ?: return@skybapSafeAmap null
                    if (redirectUrl.contains("link=")) redirectUrl = redirectUrl.substringAfter("link=")
                    myCallback(redirectUrl, "[Download]")
                }
                text.contains("Gofile") -> loadExtractor(href, "", subtitleCallback, callback)
                else -> Log.d("SkyBapHubCloud", "No server matched for: $text")
            }
        }
    }
}

// ---------------------------------------------------------------------
// GDFlix (+ mirror domains)
// ---------------------------------------------------------------------

class SkyBapGDLink : SkyBapGDFlix() {
    override var mainUrl = "https://gdlink.*"
}

class SkyBapGDFlixApp : SkyBapGDFlix() {
    override var mainUrl = "https://new.gdflix.*"
}

class SkyBapGdFlix1 : SkyBapGDFlix() {
    override var mainUrl = "https://new1.gdflix.*"
}

class SkyBapGdFlix2 : SkyBapGDFlix() {
    override var mainUrl = "https://*.gdflix.*"
}

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
        var baseUrl = skybapGetBaseUrl(url)
        val latestBaseUrl = skybapGetLatestBaseUrl(baseUrl, "gdflix")

        var newUrl = url
        if (baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val document = app.get(newUrl).document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")
        val quality = skybapGetIndexQuality(fileName)

        suspend fun myCallback(link: String, server: String = "") {
            callback.invoke(
                newExtractorLink(
                    "$name$server",
                    "$name$server $fileName[$fileSize]",
                    link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }

        document.select("div.text-center a").skybapSafeAmap { anchor ->
            val text = anchor.text()
            val link = anchor.attr("href")

            when {
                text.contains("FSL V2") -> myCallback(link, "[FSL V2]")
                text.contains("DIRECT DL") -> myCallback(link, "[Direct]")
                text.contains("DIRECT SERVER") -> myCallback(link, "[Direct]")
                text.contains("CLOUD DOWNLOAD [R2]") -> myCallback(link, "[Cloud]")

                text.contains("GD Index") -> {
                    val cfLink = baseUrl + link
                    listOf(1, 2).skybapSafeAmap { cfType ->
                        app.get("$cfLink?type=$cfType").document
                            .select("a.btn-success")
                            .skybapSafeAmap {
                                myCallback(it.attr("href"), "[CF]")
                            }
                    }
                }

                text.contains("FAST CLOUD") -> {
                    val dlink = app.get(baseUrl + link).document
                        .select("div.card-body a").attr("href")
                    if (dlink.isNotEmpty()) myCallback(dlink, "[FAST CLOUD]")
                }

                link.contains("pixeldra") -> {
                    val baseUrlLink = skybapGetBaseUrl(link)
                    val finalURL = if (link.contains("download", true)) link
                    else "$baseUrlLink/api/file/${link.substringAfterLast("/")}?download"
                    myCallback(finalURL, "[Pixeldrain]")
                }

                SkyBapSettings.allowDownloadLinks && text.contains("Instant DL") -> {
                    try {
                        val instantLink = app.get(link, allowRedirects = false)
                            .headers["location"]?.substringAfter("url=").orEmpty()
                        if (instantLink.isNotEmpty()) myCallback(instantLink, "[Instant Download]")
                    } catch (e: Exception) {
                        Log.d("SkyBapGDFlix", "Instant DL failed: $e")
                    }
                }

                text.contains("GoFile") -> {
                    try {
                        app.get(link).document.select(".row .row a").skybapSafeAmap { gofileAnchor ->
                            val gofileLink = gofileAnchor.attr("href")
                            if (gofileLink.contains("gofile")) {
                                loadExtractor(gofileLink, "", subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.d("SkyBapGDFlix", "Gofile branch failed: $e")
                    }
                }

                else -> Log.d("SkyBapGDFlix", "No server matched for: $text")
            }
        }
    }
}

// ---------------------------------------------------------------------
// Hubdrive
// ---------------------------------------------------------------------

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
        val href = app.get(url).document
            .select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
        if (href.isNotBlank()) loadExtractor(href, "", subtitleCallback, callback)
    }
}

// ---------------------------------------------------------------------
// Driveleech / Driveseed
// ---------------------------------------------------------------------

class SkyBapDriveseed : SkyBapDriveleech() {
    override val name: String = "Driveseed"
    override val mainUrl: String = "https://driveseed.*"
}

open class SkyBapDriveleech : ExtractorApi() {
    override val name: String = "Driveleech"
    override val mainUrl: String = "https://driveleech.*"
    override val requiresReferer = false

    private suspend fun cfType(url: String): List<String> {
        val downloadLinks = mutableListOf<String>()
        listOf("1", "2").forEach { t ->
            val document = app.get("$url?type=$t").document
            downloadLinks.addAll(document.select("a.btn-success").mapNotNull { it.attr("href") })
        }
        return downloadLinks
    }

    private suspend fun resumeCloudLink(baseUrl: String, path: String): String? {
        val document = app.get(baseUrl + path).document
        return document.selectFirst("a.btn-success")?.attr("href")
    }

    private suspend fun instantLink(finalLink: String): String? {
        val link = app.get(finalLink, allowRedirects = false).headers["location"]
        return link?.substringAfter("?url=")
    }

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
        } else {
            app.get(url).document
        }

        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size : ")
        val quality = skybapGetIndexQuality(fileName)

        suspend fun myCallback(link: String, server: String = "") {
            callback.invoke(
                newExtractorLink(
                    "$name$server",
                    "$name$server $fileName[$fileSize]",
                    link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = quality
                }
            )
        }

        document.select("div.text-center > a").skybapSafeAmap { element ->
            val text = element.text()
            val href = element.attr("href")

            when {
                text.contains("Cloud Download") -> myCallback(href, "[Cloud]")

                SkyBapSettings.allowDownloadLinks && text.contains("Instant Download") -> {
                    val instant = instantLink(href) ?: return@skybapSafeAmap null
                    myCallback(instant, "[Instant(Download)]")
                }

                text.contains("Direct Links") -> {
                    val link = baseUrl + href
                    cfType(link).forEach { myCallback(it, "[CF]") }
                }

                text.contains("Resume Cloud") -> {
                    val resumeCloud = resumeCloudLink(baseUrl, href) ?: return@skybapSafeAmap null
                    myCallback(resumeCloud, "[ResumeCloud]")
                }

                text.contains("gofile") -> loadExtractor(href, "", subtitleCallback, callback)

                else -> Log.d("SkyBapDriveleech", "No server matched for: $text")
            }
        }
    }
}

// ---------------------------------------------------------------------
// Gofile
// ---------------------------------------------------------------------

class SkyBapGofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"
    private val browserLanguage = "en-US"
    private val secret = "9844d94d963d30"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1) ?: return

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

        val parsedResponse = app.get(
            "$mainApi/contents/$id?cache=true&sortField=createTime&sortDirection=1",
            headers = headers
        ).parsedSafe<GofileResponse>()

        val childrenMap = parsedResponse?.data?.children ?: return
        for ((_, file) in childrenMap) {
            if (file.link.isNullOrEmpty() || file.type != "file") continue
            val fileName = file.name ?: ""
            val size = file.size ?: 0L
            callback.invoke(
                newExtractorLink(
                    "Gofile",
                    "[Gofile] $fileName [${formatBytes(size)}]",
                    file.link,
                    ExtractorLinkType.VIDEO
                ) {
                    this.quality = skybapGetIndexQuality(fileName)
                    this.headers = mapOf("Cookie" to "accountToken=$token")
                }
            )
        }
    }

    private fun generateWebsiteToken(userAgent: String, accountToken: String): String {
        val timeSlot = System.currentTimeMillis() / 1000 / 14400
        val raw = "$userAgent::$browserLanguage::$accountToken::$timeSlot::$secret"
        return sha256(raw)
    }

    private fun sha256(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024L * 1024 * 1024 -> "%.2f MB".format(bytes.toDouble() / (1024 * 1024))
        else -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
    }

    data class AccountResponse(@param:JsonProperty("data") val data: AccountData? = null)
    data class AccountData(@param:JsonProperty("token") val token: String? = null)
    data class GofileResponse(@param:JsonProperty("data") val data: GofileData? = null)
    data class GofileData(@param:JsonProperty("children") val children: Map<String, GofileFile>? = null)
    data class GofileFile(
        @param:JsonProperty("type") val type: String? = null,
        @param:JsonProperty("name") val name: String? = null,
        @param:JsonProperty("link") val link: String? = null,
        @param:JsonProperty("size") val size: Long? = 0L
    )
}

// ---------------------------------------------------------------------
// Howblogs - intermediate "link wall" pages with streaming/download links
// mixed in among ad/payroll-timer redirects.
// ---------------------------------------------------------------------

class SkyBapHowblogs : ExtractorApi() {
    override val name: String = "Howblogs"
    override val mainUrl: String = "https://howblogs.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        app.get(url).document.select("div.center_it a").skybapSafeAmap {
            val href = it.attr("href")
            if (href.isNotBlank()) loadExtractor(href, referer, subtitleCallback, callback)
        }
    }
}
