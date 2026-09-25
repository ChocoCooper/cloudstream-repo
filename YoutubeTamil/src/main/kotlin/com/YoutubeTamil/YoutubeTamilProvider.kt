package com.YoutubeTamil

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.YoutubeExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.stream.StreamInfo
import kotlinx.coroutines.coroutineScope
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class YoutubeTamilProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube Tamil"
    override var lang = "ta"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Others,
        TvType.Live,
        TvType.TvSeries
    )

    private val service = ServiceList.YouTube

    // ---- Custom main page: channels and playlists ----
    override val mainPage = mainPageOf(
        "IOF Tamil" to "https://www.youtube.com/channel/UCMtWgxssEYhojNFUPi0uTzQ",
        "WAM Tamil Movies" to "https://www.youtube.com/channel/UCEFIconx-E0D2ohYsdVjpng",
        "Sony Pictures Tamil" to "https://www.youtube.com/channel/UCH5rEIkKj4ioLZQMabpH4Qg",
        "WorldMoviesLocal Tamil" to "https://www.youtube.com/channel/UCF7D7DemdQDD0Zhe-UyUuUQ",
        "BookMyShow Stream Tamil" to "https://www.youtube.com/channel/UCIOPB_bXpXu-vzZKeAaQqVQ",
        "WorldCinema Tamil" to "https://www.youtube.com/channel/UCLqe9MEbZL_gSU9aWBJZN8A",
        "Dimensions Pictures Tamil" to "https://youtube.com/playlist?list=PL1NedV9y84PJ74HjYfKPktCWYTj5XDtCw"
    )

    // Cache to store pagination state (nextPage tokens) for both kiosks and custom lists.
    private val pageCache = mutableMapOf<String, Page?>()

    // ---- Resolve @handle URLs to /channel/UC... URLs ----
    private suspend fun resolveChannelUrl(url: String): String {
        if (url.contains("/channel/")) return url
        if (!url.contains("/@")) return url

        return try {
            val finalUrl = app.get(url, allowRedirects = true).url.toString()
            val match = Regex("/channel/(UC[\\w-]+)").find(finalUrl)
            if (match != null) {
                "https://www.youtube.com/channel/${match.groupValues[1]}"
            } else {
                url
            }
        } catch (e: Exception) {
            url
        }
    }

    // ---- Helper to fetch items from a channel or playlist ----
    private suspend fun getChannelOrPlaylistItems(
        url: String,
        page: Int
    ): Pair<List<SearchResponse>, Boolean> {
        val key = url
        if (page == 1) pageCache.remove(key)

        return if (url.contains("/playlist?list=")) {
            // ---- Playlist ----
            val extractor = service.getPlaylistExtractor(url)
            extractor.fetchPage()
            val pageData = if (page == 1) {
                extractor.initialPage.also { pageCache[key] = it.nextPage }
            } else {
                val next = pageCache[key] ?: return emptyList<SearchResponse>() to false
                extractor.getPage(next).also { pageCache[key] = it.nextPage }
            }
            val results = pageData.items.map { it.toSearchResponse() }
            results to pageData.hasNextPage()
        } else {
            // ---- Channel ----
            val resolvedUrl = resolveChannelUrl(url)
            val extractor = service.getChannelExtractor(resolvedUrl)
            extractor.fetchPage()
            val tabs = extractor.tabs
            val videosTab = tabs.firstOrNull { it.url.contains("/videos") } ?: tabs.firstOrNull()
                ?: return emptyList<SearchResponse>() to false

            val videosExtractor = service.getChannelTabExtractor(videosTab)
            val pageData = if (page == 1) {
                videosExtractor.initialPage.also { pageCache[key] = it.nextPage }
            } else {
                val next = pageCache[key] ?: return emptyList<SearchResponse>() to false
                videosExtractor.getPage(next).also { pageCache[key] = it.nextPage }
            }
            val results = pageData.items.map { it.toSearchResponse() }
            results to pageData.hasNextPage()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val (results, hasNext) = getChannelOrPlaylistItems(request.data, page)

        return newHomePageResponse(
            listOf(
                HomePageList(
                    request.name,
                    results,
                    true
                )
            ),
            hasNext
        )
    }

    // ---- Search ----
    private val searchPageCache = mutableMapOf<String, Page?>()

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val extractor = service.getSearchExtractor(query)
        val pageData = if (!searchPageCache.containsKey(query)) {
            extractor.fetchPage()
            extractor.initialPage.also { searchPageCache[query] = it.nextPage }
        } else {
            val next = searchPageCache[query] ?: return newSearchResponseList(emptyList(), false)
            extractor.getPage(next).also { searchPageCache[query] = it.nextPage }
        }
        val results = pageData.items.map { it.toSearchResponse() }
        return newSearchResponseList(results, pageData.hasNextPage())
    }

    private fun InfoItem.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            name ?: "Unknown",
            url ?: "",
            TvType.Others
        ) {
            posterUrl = thumbnails.lastOrNull()?.url
        }
    }

    // ---- Load ----
    override suspend fun load(url: String): LoadResponse {
        val urlType = getUrlType(url)
        return when (urlType) {
            UrlType.Video -> loadVideo(url)
            UrlType.Channel -> loadChannel(url)
            UrlType.Playlist -> loadPlaylist(url)
            UrlType.Unknown -> throw RuntimeException("Unsupported YouTube URL")
        }
    }

    private enum class UrlType { Video, Channel, Playlist, Unknown }

    private fun getUrlType(url: String): UrlType {
        return when {
            url.contains("/watch?v=") || url.contains("youtu.be/") -> UrlType.Video
            url.contains("/channel/") || url.contains("/@") || url.contains("/c/") -> UrlType.Channel
            url.contains("/playlist?list=") ||
                (url.contains("/watch?v=") && url.contains("&list=")) -> UrlType.Playlist
            else -> UrlType.Unknown
        }
    }

    private suspend fun loadVideo(url: String): LoadResponse {
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()
        val info = StreamInfo.getInfo(extractor)

        return newMovieLoadResponse(
            info.name,
            url,
            if (info.streamType?.name?.contains("LIVE") == true) TvType.Live else TvType.Others,
            url
        ) {
            plot = info.description.content.toString()
            posterUrl = info.thumbnails.lastOrNull()?.url
            duration = info.duration.toInt()

            info.uploaderName?.takeIf { it.isNotBlank() }?.let { uploader ->
                actors = listOf(
                    ActorData(
                        Actor(
                            uploader,
                            info.uploaderAvatars.lastOrNull()?.url ?: ""
                        )
                    )
                )
            }

            tags = info.tags?.take(5)?.toList()
        }
    }

    private suspend fun loadChannel(url: String): LoadResponse {
        val resolvedUrl = resolveChannelUrl(url)
        val extractor = ServiceList.YouTube.getChannelExtractor(resolvedUrl)
        extractor.fetchPage()

        val channelName = extractor.name
        val channelDescription = extractor.description
        val channelAvatar = extractor.avatars.lastOrNull()?.url
        val channelBanner = extractor.banners.lastOrNull()?.url

        val tabs = extractor.tabs
        val videosTab = tabs.firstOrNull { it.url.contains("/videos") } ?: tabs.firstOrNull()
            ?: throw RuntimeException("No videos tab found")

        val videosExtractor = ServiceList.YouTube.getChannelTabExtractor(videosTab)
        val episodes = mutableListOf<Episode>()

        var page = videosExtractor.initialPage
        episodes.addAll(page.items.map { item ->
            newEpisode(item.url) {
                name = item.name
                posterUrl = item.thumbnails.lastOrNull()?.url
            }
        })

        var pagesLoaded = 1
        val maxPagesToLoad = 5

        while (page.hasNextPage() && pagesLoaded < maxPagesToLoad) {
            page = videosExtractor.getPage(page.nextPage)
            episodes.addAll(page.items.map { item ->
                newEpisode(item.url) {
                    name = item.name
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            })
            pagesLoaded++
        }

        return newTvSeriesLoadResponse(
            channelName,
            url,
            TvType.TvSeries,
            episodes
        ) {
            plot = channelDescription
            posterUrl = channelBanner
            backgroundPosterUrl = channelBanner
            tags = listOf("Channel")
            actors = listOf(
                ActorData(
                    Actor(
                        channelName,
                        channelAvatar ?: ""
                    )
                )
            )
        }
    }

    private suspend fun loadPlaylist(url: String): LoadResponse {
        val extractor = ServiceList.YouTube.getPlaylistExtractor(url)
        extractor.fetchPage()

        val playlistName = extractor.name
        val playlistDescription = extractor.description.content.toString()
        val playlistThumbnail = extractor.thumbnails.lastOrNull()?.url
        val uploaderName = extractor.uploaderName

        val episodes = mutableListOf<Episode>()

        var page = extractor.getInitialPage()
        episodes.addAll(page.items.map { item ->
            newEpisode(item.url) {
                name = item.name
                posterUrl = item.thumbnails.lastOrNull()?.url
            }
        })

        var pagesLoaded = 1
        val maxPagesToLoad = 5

        while (page.hasNextPage() && pagesLoaded < maxPagesToLoad) {
            page = extractor.getPage(page.nextPage)
            episodes.addAll(page.items.map { item ->
                newEpisode(item.url) {
                    name = item.name
                    posterUrl = item.thumbnails.lastOrNull()?.url
                }
            })
            pagesLoaded++
        }

        return newTvSeriesLoadResponse(
            playlistName,
            url,
            TvType.TvSeries,
            episodes
        ) {
            plot = playlistDescription
            posterUrl = playlistThumbnail
            tags = if (uploaderName.isNotBlank()) listOf("Channel: $uploaderName")
                   else listOf("Playlist")
            if (uploaderName.isNotBlank()) {
                actors = listOf(
                    ActorData(
                        Actor(
                            uploaderName,
                            extractor.uploaderAvatars.lastOrNull()?.url ?: ""
                        )
                    )
                )
            }
        }
    }

    // ---- LOAD LINKS: Builds a master playlist locally ----
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean = coroutineScope {
        // 1. Collect all individual links from YoutubeExtractor
        val collectedLinks = mutableListOf<ExtractorLink>()
        val collectingCallback: (ExtractorLink) -> Unit = { link ->
            collectedLinks.add(link)
        }

        // The `data` parameter is the full video URL.
        YoutubeExtractor().getUrl(data, null, subtitleCallback, collectingCallback)

        // 2. Map allowed qualities to their resolution and estimated bandwidth
        val allowedQualities = mapOf(
            Qualities.P1080.value to Triple("1920x1080", 5000000, "1080p"),
            Qualities.P720.value to Triple("1280x720", 2500000, "720p"),
            Qualities.P480.value to Triple("854x480", 1200000, "480p"),
            Qualities.P360.value to Triple("640x360", 600000, "360p")
        )

        // 3. Build the HLS master playlist string
        val m3u8Builder = StringBuilder()
        m3u8Builder.append("#EXTM3U\n#EXT-X-VERSION:3\n")

        var hasLinks = false
        for (link in collectedLinks) {
            val qualityInfo = allowedQualities[link.quality] ?: continue
            val (resolution, bandwidth, _) = qualityInfo

            // Note: YouTube links are usually progressive MP4/WebM.
            // We are placing them in an HLS master playlist so the player treats them as variants.
            m3u8Builder.append("#EXT-X-STREAM-INF:BANDWIDTH=$bandwidth,RESOLUTION=$resolution,CODECS=\"avc1.640028,mp4a.40.2\"\n")
            m3u8Builder.append("${link.url}\n")
            hasLinks = true
        }

        if (!hasLinks) {
            return@coroutineScope false
        }

        // 4. Serve the generated playlist via the local Kotlin server
        val manifestBytes = m3u8Builder.toString().toByteArray(Charsets.UTF_8)
        val localUrl = LocalMasterPlaylistServer.serve(manifestBytes)

        // 5. Emit the single local URL as "YouTube"
        callback(
            newExtractorLink(
                source = "YouTube",
                name = "YouTube",
                url = localUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://www.youtube.com/"
                this.quality = Qualities.Unknown.value // Let the player auto-select
            }
        )

        return@coroutineScope true
    }

    // ---- Local Server (In-Memory) ----
    // This object runs a tiny HTTP server on localhost to serve the master playlist.
    private object LocalMasterPlaylistServer {
        private const val TAG = "YT-LocalServer"
        private const val TTL_MS = 30 * 60 * 1000L // 30 minutes

        private data class Entry(val bytes: ByteArray, val createdAt: Long)

        private val lock = Any()
        private var serverSocket: ServerSocket? = null
        private var port: Int = -1
        private val manifests = ConcurrentHashMap<String, Entry>()

        private fun ensureStarted(): Int {
            synchronized(lock) {
                val existing = serverSocket
                if (existing != null && !existing.isClosed) return port

                val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                serverSocket = ss
                port = ss.localPort
                Log.d(TAG, "LocalServer listening on 127.0.0.1:$port")

                thread(isDaemon = true, name = "YT-MasterPlaylistServer") {
                    while (!ss.isClosed) {
                        try {
                            val client = ss.accept()
                            thread(isDaemon = true, name = "YT-MasterPlaylistClient") { handleClient(client) }
                        } catch (e: Exception) {
                            if (!ss.isClosed) Log.e(TAG, "Server accept error: $e")
                        }
                    }
                }
                return port
            }
        }

        private fun handleClient(socket: Socket) {
            socket.use { s ->
                try {
                    s.soTimeout = 10000
                    val input = s.getInputStream().bufferedReader(Charsets.US_ASCII)
                    val requestLine = input.readLine() ?: return
                    // Consume headers
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }

                    val path = requestLine.split(" ").getOrNull(1) ?: "/"
                    val id = path.trimStart('/').substringBefore("?").substringBefore(".")
                    val entry = manifests[id]
                    val output = s.getOutputStream()

                    if (entry == null) {
                        val body = "not found".toByteArray()
                        val header = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        output.write(header.toByteArray(Charsets.US_ASCII))
                        output.write(body)
                    } else {
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${entry.bytes.size}\r\nAccess-Control-Allow-Origin: *\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n"
                        output.write(header.toByteArray(Charsets.US_ASCII))
                        output.write(entry.bytes)
                    }
                    output.flush()
                } catch (e: Exception) {
                    Log.e(TAG, "Client error: $e")
                }
            }
        }

        private fun pruneExpired() {
            val cutoff = System.currentTimeMillis() - TTL_MS
            manifests.entries.removeAll { it.value.createdAt < cutoff }
        }

        fun serve(bytes: ByteArray): String {
            val p = ensureStarted()
            pruneExpired()
            val id = UUID.randomUUID().toString()
            manifests[id] = Entry(bytes, System.currentTimeMillis())
            return "http://127.0.0.1:$p/$id.m3u8"
        }
    }
}
