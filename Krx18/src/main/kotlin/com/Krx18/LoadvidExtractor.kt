package com.KRX18

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.ServerSocket
import kotlin.concurrent.thread

class LoadvidExtractor : ExtractorApi() {
    override val name = "Loadvid"
    override val mainUrl = "https://cdn.loadvid.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).text
        
        val videoHash = Regex("""videoHash:\s*'([^']+)'""").find(document)?.groupValues?.get(1) ?: return
        val videoToken = Regex("""videoToken:\s*'([^']+)'""").find(document)?.groupValues?.get(1) ?: return
        
        val parsedDoc = Jsoup.parse(document)
        val csrfToken = parsedDoc.selectFirst("meta[name=csrf-token]")?.attr("content") ?: return

        val resolveUrl = "$mainUrl/videos/resolve-token"
        val payload = mapOf("token" to videoToken, "hash" to videoHash)

        val m3u8Response = app.post(
            resolveUrl,
            headers = mapOf(
                "Content-Type" to "application/json",
                "X-CSRF-TOKEN" to csrfToken,
                "Accept" to "application/vnd.apple.mpegurl,*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to mainUrl
            ),
            json = payload
        ).text

        if (m3u8Response.contains("#EXTM3U")) {
            
            // ExoPlayer Fix: Trick the URI parser into engaging the TsExtractor
            val spoofedM3u8 = m3u8Response.replace(".png", ".png?.ts")
            
            // Cronet Bypass: Cronet rejects 'data:' and 'file:' URIs.
            // We spin up a lightweight, native 127.0.0.1 daemon to serve the payload directly over HTTP.
            val serverSocket = ServerSocket(0)
            val localPort = serverSocket.localPort
            val localUrl = "http://127.0.0.1:$localPort/manifest.m3u8"

            thread(isDaemon = true) {
                try {
                    serverSocket.soTimeout = 30000 // Self-destruct if ExoPlayer doesn't connect within 30s
                    val socket = serverSocket.accept()
                    
                    // Consume incoming HTTP headers to keep the pipe clear
                    val input = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream()))
                    var line = input.readLine()
                    while (!line.isNullOrEmpty()) { line = input.readLine() }
                    
                    val out = socket.getOutputStream()
                    val payloadBytes = spoofedM3u8.toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\n" +
                            "Access-Control-Allow-Origin: *\r\n" +
                            "Content-Type: application/vnd.apple.mpegurl\r\n" +
                            "Content-Length: ${payloadBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                            
                    out.write(response.toByteArray(Charsets.UTF_8))
                    out.write(payloadBytes)
                    out.flush()
                    
                    socket.close()
                    serverSocket.close()
                } catch (e: Exception) {
                    runCatching { serverSocket.close() }
                }
            }

            val exoHeaders = mapOf(
                "Referer" to mainUrl,
                "Origin" to mainUrl,
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )

            // Compiler Fix: Using `type = ExtractorLinkType.M3U8` safely enables the `this.referer` lambda format
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name Auto",
                    url = localUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = exoHeaders
                }
            )
        }
    }
}
