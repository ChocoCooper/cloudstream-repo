package com.megastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

// Extension function to securely access Cloudstream's native newExtractorLink
suspend fun MegaStreamProvider.invokeAllCustomProxies(
    imdbId: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    coroutineScope {
        val tasks = listOf(
            async { invokeRgShows(imdbId, MegaStreamConstants.VIDSRC_PROXY, "VidSrc", callback) },
            async { invokeRgShows(imdbId, MegaStreamConstants.VIDSRC_HINDI, "VidSrc Hindi", callback) },
            async { invokeVidLink(imdbId, callback) },
            async { invokePrimeSrc(imdbId, subtitleCallback, callback) },
            async { invokeVideasy(imdbId, subtitleCallback, callback) },
            async { invokeDahmer(imdbId, callback) }
        )
        tasks.awaitAll()
    }
}

// 1. VidSrc Anti-Block Proxies (rgshows.ru)
private suspend fun MegaStreamProvider.invokeRgShows(imdbId: String, proxyUrl: String, sourceName: String, callback: (ExtractorLink) -> Unit) {
    try {
        val url = "$proxyUrl/embed/movie/$imdbId"
        val req = app.get(url, headers = mapOf("Referer" to url)).document
        val rcpSource = req.selectFirst("iframe#player_iframe")?.attr("src") ?: return
        
        val rcpUrl = if (rcpSource.startsWith("//")) "https:$rcpSource" else rcpSource
        val rcpDoc = app.get(rcpUrl, headers = mapOf("Referer" to url)).text
        
        val hashMatch = Regex("""hash:\s*'([^']+)'""").find(rcpDoc)?.groupValues?.get(1) ?: return
        val apiRes = app.get("$proxyUrl/api/source/$hashMatch").text
        
        Regex("""file":"([^"]+)"""").find(apiRes)?.groupValues?.get(1)?.let { stream ->
            val finalStream = stream.replace("\\/", "/")
            callback.invoke(
                newExtractorLink(
                    source = "MegaStream",
                    name = "$sourceName Auto",
                    url = finalStream,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    } catch (e: Exception) {}
}

// 2. VidLink Proxy (vidlink.pro)
private suspend fun MegaStreamProvider.invokeVidLink(imdbId: String, callback: (ExtractorLink) -> Unit) {
    try {
        val url = "${MegaStreamConstants.VIDLINK_API}/movie/$imdbId"
        val html = app.get(url, timeout = 10).text
        
        // Bruteforce extract the primary M3U8
        Regex("""source\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(html)?.groupValues?.get(1)?.let { stream ->
            callback.invoke(
                newExtractorLink(
                    source = "MegaStream",
                    name = "VidLink Auto",
                    url = stream,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    } catch (e: Exception) {}
}

// 3. Dahmer Movies (111477.xyz)
private suspend fun MegaStreamProvider.invokeDahmer(imdbId: String, callback: (ExtractorLink) -> Unit) {
    try {
        val url = "${MegaStreamConstants.DAHMER_MOVIES}/movie/$imdbId"
        val html = app.get(url, timeout = 10).text
        
        Regex("""file\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(html)?.groupValues?.get(1)?.let { stream ->
            callback.invoke(
                newExtractorLink(
                    source = "MegaStream",
                    name = "Dahmer Auto",
                    url = stream,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    } catch (e: Exception) {}
}

// 4. PrimeSrc & Videasy (Iframe Fallbacks)
private suspend fun MegaStreamProvider.invokePrimeSrc(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    try {
        val url = "${MegaStreamConstants.PRIMESRC_API}/embed/movie?imdb=$imdbId"
        val doc = app.get(url, timeout = 10).document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, url, subtitleCallback, callback)
            }
        }
    } catch (e: Exception) {}
}

private suspend fun MegaStreamProvider.invokeVideasy(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    try {
        val url = "${MegaStreamConstants.VIDEASY_API}/embed/movie/$imdbId"
        val doc = app.get(url, timeout = 10).document
        doc.select("iframe").forEach { iframe ->
            val src = iframe.attr("src").let { if (it.startsWith("//")) "https:$it" else it }
            if (src.isNotBlank() && src.startsWith("http")) {
                loadExtractor(src, url, subtitleCallback, callback)
            }
        }
    } catch (e: Exception) {}
}
