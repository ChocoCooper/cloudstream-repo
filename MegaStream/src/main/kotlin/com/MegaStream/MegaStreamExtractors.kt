package com.megastream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class MegaStreamExtractors {

    suspend fun invokeExtractor(url: String, name: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val target = url.lowercase()
        try {
            when {
                target.contains("voe") -> extractVoe(url, name, callback)
                target.contains("filemoon") || target.contains("kerapoxy") -> extractFilemoon(url, name, callback)
                target.contains("upstream") -> extractUpstream(url, name, callback)
                target.contains("vidsrc") -> extractVidSrc(url, name, subtitleCallback, callback)
                target.contains("superembed") -> extractSuperEmbed(url, name, callback)
                target.contains("dood") -> extractDoodStream(url, name, callback)
                target.contains("streamwish") -> extractStreamWish(url, name, callback)
                target.contains("mixdrop") -> extractMixDrop(url, name, callback)
                url.endsWith(".m3u8") || url.endsWith(".mp4") -> {
                    callback.invoke(
                        ExtractorLink(name, "$name Auto", url, url, Qualities.Unknown.value, 
                            if (url.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO)
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun extractVoe(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url).text
        val hlsLink = Regex("""'hls': ?'([^']+)'""").find(html)?.groupValues?.get(1) 
            ?: Regex("""hls": ?"([^"]+)"""").find(html)?.groupValues?.get(1)
            
        hlsLink?.let {
            callback.invoke(ExtractorLink(name, "$name Voe", it, url, Qualities.Unknown.value, ExtractorLinkType.M3U8))
        }
    }

    private suspend fun extractFilemoon(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url).text
        val unpacked = MegaStreamUtils.unpack(html)
        val hlsLink = Regex("""file:\s*"([^"]+\.m3u8[^"]*)"""").find(unpacked)?.groupValues?.get(1)
        
        hlsLink?.let {
            callback.invoke(ExtractorLink(name, "$name Filemoon", it, url, Qualities.Unknown.value, ExtractorLinkType.M3U8))
        }
    }

    private suspend fun extractUpstream(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url).text
        val unpacked = MegaStreamUtils.unpack(html)
        val hlsLink = Regex("""file:\s*"([^"]+\.m3u8[^"]*)"""").find(unpacked)?.groupValues?.get(1)
        
        hlsLink?.let {
            callback.invoke(ExtractorLink(name, "$name Upstream", it, url, Qualities.Unknown.value, ExtractorLinkType.M3U8))
        }
    }

    private suspend fun extractVidSrc(url: String, name: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val req = app.get(url, headers = mapOf("Referer" to url)).document
        val rcpSource = req.selectFirst("iframe#player_iframe")?.attr("src")
        
        if (rcpSource != null) {
            val rcpUrl = if (rcpSource.startsWith("//")) "https:$rcpSource" else rcpSource
            val rcpDoc = app.get(rcpUrl, headers = mapOf("Referer" to url)).text
            
            val hashMatch = Regex("""hash:\s*'([^']+)'""").find(rcpDoc)?.groupValues?.get(1)
            if (hashMatch != null) {
                val apiRes = app.get("https://vidsrc.me/api/source/$hashMatch").text
                Regex("""file":"([^"]+)"""").find(apiRes)?.groupValues?.get(1)?.let { stream ->
                    val finalStream = stream.replace("\\/", "/")
                    callback.invoke(ExtractorLink(name, "$name VidSrc", finalStream, url, Qualities.Unknown.value, ExtractorLinkType.M3U8))
                }
            }
        }
    }

    private suspend fun extractSuperEmbed(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url).text
        Regex("""play_url":"([^"]+)"""").find(html)?.groupValues?.get(1)?.let { stream ->
            val decoded = stream.replace("\\/", "/")
            callback.invoke(ExtractorLink(name, "$name SuperEmbed", decoded, url, Qualities.Unknown.value, ExtractorLinkType.M3U8))
        }
    }

    private suspend fun extractDoodStream(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        val id = Regex("""/([a-zA-Z0-9]+)$""").find(url)?.groupValues?.get(1) ?: return
        val base = url.substringBefore("/d")
        val html = app.get(url).text
        val md5Token = Regex("""/pass_md5/([^']+)""").find(html)?.groupValues?.get(1) ?: return
        val tokenUrl = "$base/pass_md5/$md5Token"
        val downloadUrl = app.get(tokenUrl, headers = mapOf("Referer" to url)).text
        
        val randomString = (1..10).map { ('a'..'z').random() }.joinToString("")
        val finalUrl = "$downloadUrl$randomString?token=$md5Token&expiry=${System.currentTimeMillis()}"
        
        callback.invoke(ExtractorLink(name, "$name DoodStream", finalUrl, url, Qualities.Unknown.value, ExtractorLinkType.VIDEO))
    }

    private suspend fun extractStreamWish(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url).text
        val unpacked = MegaStreamUtils.unpack(html)
        Regex("""file:\s*"([^"]+\.m3u8[^"]*)"""").find(unpacked)?.groupValues?.get(1)?.let {
            callback.invoke(ExtractorLink(name, "$name StreamWish", it, url, Qualities.Unknown.value, ExtractorLinkType.M3U8))
        }
    }

    private suspend fun extractMixDrop(url: String, name: String, callback: (ExtractorLink) -> Unit) {
        val html = app.get(url).text
        val unpacked = MegaStreamUtils.unpack(html)
        Regex("""wurl="([^"]+)"""").find(unpacked)?.groupValues?.get(1)?.let {
            val finalUrl = if (it.startsWith("//")) "https:$it" else it
            callback.invoke(ExtractorLink(name, "$name MixDrop", finalUrl, url, Qualities.Unknown.value, ExtractorLinkType.VIDEO))
        }
    }
}
