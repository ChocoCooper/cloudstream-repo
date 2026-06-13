package com.megastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup

// Constants restored directly from your ApiConstants.kt
object MegaStreamApis {
    const val PRIMESRC = "https://primesrc.me"
    const val VIDSRC_PROXY = "https://api.rgshows.ru"
    const val VIDSRC_HINDI = "https://hindi.rgshows.ru"
    const val DAHMER = "https://a.111477.xyz"
    const val HEXA = "https://theemoviedb.hexa.su"
    const val VIDEASY = "https://api.videasy.to"
    const val VIDLINK = "https://vidlink.pro"
    const val MULTIEMBED = "https://multiembed.mov"
    const val TWOEMBED = "https://2embed.cc"
    const val AUTOEMBED = "https://autoembed.co"
}

// Extension function to securely access Cloudstream's native newExtractorLink
suspend fun MegaStreamProvider.invokeAllCustomProxies(
    imdbId: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    coroutineScope {
        val tasks = listOf(
            async { safeExtract { invokeVidLink(imdbId, subtitleCallback, callback) } },
            async { safeExtract { invokePrimeSrc(imdbId, subtitleCallback, callback) } },
            async { safeExtract { invokeVideasy(imdbId, subtitleCallback, callback) } },
            async { safeExtract { invokeHexa(imdbId, subtitleCallback, callback) } },
            async { safeExtract { invokeDahmer(imdbId, subtitleCallback, callback) } },
            async { safeExtract { invokeMultiEmbed(imdbId, subtitleCallback, callback) } },
            async { safeExtract { invokeGenericIframe(MegaStreamApis.TWOEMBED, "/embed/$imdbId", subtitleCallback, callback) } },
            async { safeExtract { invokeGenericIframe(MegaStreamApis.AUTOEMBED, "/movie/imdb/$imdbId", subtitleCallback, callback) } },
            async { safeExtract { invokeVidSrcProxy(imdbId, MegaStreamApis.VIDSRC_PROXY, "VidSrc", callback) } },
            async { safeExtract { invokeVidSrcProxy(imdbId, MegaStreamApis.VIDSRC_HINDI, "VidSrc Hindi", callback) } }
        )
        tasks.awaitAll()
    }
}

// Helper to swallow SocketTimeoutExceptions from ISP blocks
private suspend fun safeExtract(block: suspend () -> Unit) {
    try { block() } catch (e: Exception) {}
}

// 1. VidLink API
private suspend fun MegaStreamProvider.invokeVidLink(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "${MegaStreamApis.VIDLINK}/movie/$imdbId"
    val res = app.get(url, timeout = 10).text
    
    Regex("""source\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").findAll(res).forEach { match ->
        callback.invoke(
            newExtractorLink(source = "MegaStream", name = "VidLink API", url = match.groupValues[1], type = ExtractorLinkType.M3U8) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }

    Jsoup.parse(res).select("iframe").forEach { iframe ->
        val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
        if (src.startsWith("http")) loadExtractor(src, url, subtitleCallback, callback)
    }
}

// 2. PrimeSrc API
private suspend fun MegaStreamProvider.invokePrimeSrc(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "${MegaStreamApis.PRIMESRC}/embed/movie?imdb=$imdbId"
    invokeGenericIframeExtractor(url, subtitleCallback, callback)
}

// 3. Videasy API
private suspend fun MegaStreamProvider.invokeVideasy(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "${MegaStreamApis.VIDEASY}/embed/movie/$imdbId"
    invokeGenericIframeExtractor(url, subtitleCallback, callback)
}

// 4. Hexa API
private suspend fun MegaStreamProvider.invokeHexa(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "${MegaStreamApis.HEXA}/movie/$imdbId"
    invokeGenericIframeExtractor(url, subtitleCallback, callback)
}

// 5. Dahmer API
private suspend fun MegaStreamProvider.invokeDahmer(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "${MegaStreamApis.DAHMER}/movie/$imdbId"
    val res = app.get(url, timeout = 10).text
    
    Regex("""(https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*)""").findAll(res).forEach { match ->
        callback.invoke(
            newExtractorLink(source = "MegaStream", name = "Dahmer API", url = match.groupValues[1], type = ExtractorLinkType.M3U8) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
    invokeGenericIframeExtractor(url, subtitleCallback, callback, res)
}

// 6. MultiEmbed API
private suspend fun MegaStreamProvider.invokeMultiEmbed(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "${MegaStreamApis.MULTIEMBED}/directstream.php?video_id=$imdbId"
    val res = app.get(url, timeout = 10).text
    
    Regex("""(https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*)""").findAll(res).forEach { match ->
        callback.invoke(
            newExtractorLink(source = "MegaStream", name = "MultiEmbed Direct", url = match.groupValues[1].replace("\\/", "/"), type = ExtractorLinkType.M3U8) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
    invokeGenericIframeExtractor(url, subtitleCallback, callback, res)
}

// 7. VidSrc Proxies (rgshows.ru & hindi.rgshows.ru)
private suspend fun MegaStreamProvider.invokeVidSrcProxy(imdbId: String, proxyUrl: String, sourceName: String, callback: (ExtractorLink) -> Unit) {
    val url = "$proxyUrl/embed/movie/$imdbId"
    val req = app.get(url, headers = mapOf("Referer" to url), timeout = 10).document
    val rcpSource = req.selectFirst("iframe#player_iframe")?.attr("src") ?: return
    
    val rcpUrl = if (rcpSource.startsWith("//")) "https:$rcpSource" else rcpSource
    val rcpDoc = app.get(rcpUrl, headers = mapOf("Referer" to url), timeout = 10).text
    
    val hashMatch = Regex("""hash:\s*'([^']+)'""").find(rcpDoc)?.groupValues?.get(1) ?: return
    val apiRes = app.get("$proxyUrl/api/source/$hashMatch", timeout = 10).text
    
    Regex("""file":"([^"]+)"""").find(apiRes)?.groupValues?.get(1)?.let { stream ->
        callback.invoke(
            newExtractorLink(source = "MegaStream", name = sourceName, url = stream.replace("\\/", "/"), type = ExtractorLinkType.M3U8) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }
}

// Generic Iframe Router
private suspend fun MegaStreamProvider.invokeGenericIframe(domain: String, path: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    invokeGenericIframeExtractor("$domain$path", subtitleCallback, callback)
}

// Universal Iframe Extractor
private suspend fun MegaStreamProvider.invokeGenericIframeExtractor(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, preloadedHtml: String? = null) {
    val html = preloadedHtml ?: app.get(url, timeout = 10).text
    Jsoup.parse(html).select("iframe").forEach { iframe ->
        val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
        if (src.startsWith("http")) {
            loadExtractor(src, url, subtitleCallback, callback)
        }
    }
}
