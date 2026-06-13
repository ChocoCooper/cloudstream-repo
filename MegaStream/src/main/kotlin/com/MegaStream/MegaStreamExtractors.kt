package com.megastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup

// Helper to safely execute APIs and silently ignore ISP/DNS blocks (SocketTimeouts)
private suspend fun safeApi(block: suspend () -> Unit) {
    try { block() } catch (e: Exception) {}
}

suspend fun MegaStreamProvider.invokeAllCustomProxies(
    imdbId: String,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    coroutineScope {
        val tasks = listOf(
            async { safeApi { invokeVidLink(imdbId, subtitleCallback, callback) } },
            async { safeApi { invokeMultiEmbed(imdbId, subtitleCallback, callback) } },
            async { safeApi { invokePrimeSrc(imdbId, subtitleCallback, callback) } },
            async { safeApi { invokeVideasy(imdbId, subtitleCallback, callback) } },
            async { safeApi { invokeHexa(imdbId, subtitleCallback, callback) } },
            async { safeApi { invokeDahmer(imdbId, subtitleCallback, callback) } },
            async { safeApi { invoke2Embed(imdbId, subtitleCallback, callback) } },
            async { safeApi { invokeAutoEmbed(imdbId, subtitleCallback, callback) } },
            // Vidsrc endpoints (Will likely timeout in India, but included for VPN users)
            async { safeApi { invokeVidSrc("https://vidsrc.me", imdbId, callback) } },
            async { safeApi { invokeVidSrc("https://vidsrc.net", imdbId, callback) } },
            async { safeApi { invokeVidSrc("https://vidsrc.in", imdbId, callback) } }
        )
        tasks.awaitAll()
    }
}

private suspend fun MegaStreamProvider.invokeVidLink(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "https://vidlink.pro/movie/$imdbId"
    val res = app.get(url, timeout = 8).text
    
    // Direct stream extraction
    val stream = Regex("""source\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(res)?.groupValues?.get(1)
    if (stream != null) {
        callback.invoke(newExtractorLink("MegaStream", "VidLink", stream, url, Qualities.Unknown.value, ExtractorLinkType.M3U8))
    }
    
    // Iframe fallback
    Jsoup.parse(res).select("iframe").forEach {
        val src = it.attr("src").let { s -> if (s.startsWith("//")) "https:$s" else s }
        if (src.startsWith("http")) loadExtractor(src, url, subtitleCallback, callback)
    }
}

private suspend fun MegaStreamProvider.invokeMultiEmbed(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "https://multiembed.mov/directstream.php?video_id=$imdbId"
    val res = app.get(url, timeout = 8).text
    
    // Direct stream extraction
    Regex("""(https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*)""").findAll(res).forEach {
        callback.invoke(newExtractorLink("MegaStream", "MultiEmbed", it.groupValues[1].replace("\\/", "/"), url, Qualities.Unknown.value, ExtractorLinkType.M3U8))
    }
    
    // Iframe fallback
    Jsoup.parse(res).select("iframe").forEach {
        val src = it.attr("src").let { s -> if (s.startsWith("//")) "https:$s" else s }
        if (src.startsWith("http")) loadExtractor(src, url, subtitleCallback, callback)
    }
}

private suspend fun MegaStreamProvider.invokePrimeSrc(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "https://primesrc.me/embed/movie?imdb=$imdbId"
    val doc = app.get(url, timeout = 8).document
    doc.select("iframe").forEach {
        val src = it.attr("src").let { s -> if (s.startsWith("//")) "https:$s" else s }
        if (src.startsWith("http")) loadExtractor(src, url, subtitleCallback, callback)
    }
}

private suspend fun MegaStreamProvider.invokeVideasy(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "https://api.videasy.to/embed/movie/$imdbId"
    val doc = app.get(url, timeout = 8).document
    doc.select("iframe").forEach {
        val src = it.attr("src").let { s -> if (s.startsWith("//")) "https:$s" else s }
        if (src.startsWith("http")) loadExtractor(src, url, subtitleCallback, callback)
    }
}

private suspend fun MegaStreamProvider.invokeHexa(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "https://theemoviedb.hexa.su/movie/$imdbId"
    val doc = app.get(url, timeout = 8).document
    doc.select("iframe").forEach {
        val src = it.attr("src").let { s -> if (s.startsWith("//")) "https:$s" else s }
        if (src.startsWith("http")) loadExtractor(src, url, subtitleCallback, callback)
    }
}

private suspend fun MegaStreamProvider.invokeDahmer(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "https://a.111477.xyz/movie/$imdbId"
    val res = app.get(url, timeout = 8).text
    
    Regex("""(https?://[^"'\s<>]+?\.m3u8[^"'\s<>]*)""").findAll(res).forEach {
        callback.invoke(newExtractorLink("MegaStream", "Dahmer", it.groupValues[1], url, Qualities.Unknown.value, ExtractorLinkType.M3U8))
    }
    
    Jsoup.parse(res).select("iframe").forEach {
        val src = it.attr("src").let { s -> if (s.startsWith("//")) "https:$s" else s }
        if (src.startsWith("http")) loadExtractor(src, url, subtitleCallback, callback)
    }
}

private suspend fun MegaStreamProvider.invoke2Embed(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "https://2embed.cc/embed/$imdbId"
    val doc = app.get(url, timeout = 8).document
    doc.select("iframe").forEach {
        val src = it.attr("src").let { s -> if (s.startsWith("//")) "https:$s" else s }
        if (src.startsWith("http")) loadExtractor(src, url, subtitleCallback, callback)
    }
}

private suspend fun MegaStreamProvider.invokeAutoEmbed(imdbId: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
    val url = "https://autoembed.co/movie/imdb/$imdbId"
    val doc = app.get(url, timeout = 8).document
    doc.select("iframe").forEach {
        val src = it.attr("src").let { s -> if (s.startsWith("//")) "https:$s" else s }
        if (src.startsWith("http")) loadExtractor(src, url, subtitleCallback, callback)
    }
}

// VidSrc Decryption Flow (Replaces the broken rgshows.ru proxy)
private suspend fun MegaStreamProvider.invokeVidSrc(domain: String, imdbId: String, callback: (ExtractorLink) -> Unit) {
    val url = "$domain/embed/movie/$imdbId"
    val req = app.get(url, headers = mapOf("Referer" to url), timeout = 8).document
    val rcpSource = req.selectFirst("iframe#player_iframe")?.attr("src") ?: return
    
    val rcpUrl = if (rcpSource.startsWith("//")) "https:$rcpSource" else rcpSource
    val rcpDoc = app.get(rcpUrl, headers = mapOf("Referer" to url), timeout = 8).text
    
    val hashMatch = Regex("""hash:\s*'([^']+)'""").find(rcpDoc)?.groupValues?.get(1) ?: return
    val apiRes = app.get("$domain/api/source/$hashMatch", timeout = 8).text
    
    Regex("""file":"([^"]+)"""").find(apiRes)?.groupValues?.get(1)?.let { stream ->
        val finalStream = stream.replace("\\/", "/")
        callback.invoke(
            newExtractorLink(
                source = "MegaStream", 
                name = "VidSrc Auto", 
                url = finalStream, 
                referer = url, 
                quality = Qualities.Unknown.value, 
                type = ExtractorLinkType.M3U8
            )
        )
    }
}
