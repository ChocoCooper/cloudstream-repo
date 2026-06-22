package recloudstream

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.kiosk.KioskExtractor
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage

class YoutubeProvider : MainAPI() {
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val supportedTypes = setOf(
        TvType.Others,
        TvType.Live,
        TvType.TvSeries
    )

    private val service = ServiceList.YouTube

    override val mainPage = mainPageOf(
        "Trending" to "Trending",
        "trending_movies_and_shows" to "Movies & Shows",
        "trending_music" to "Music",
        "trending_gaming" to "Gaming",
        "trending_podcasts_episodes" to "Podcasts",
        "live" to "Live"
    )

    private val pageCache = mutableMapOf<String, org.schabi.newpipe.extractor.Page?>()
    
    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val key = request.data
        if (page == 1) {
            pageCache.remove(key)
        }

        val extractor = getKioskExtractor(request.data)
        var pageData: InfoItemsPage<out InfoItem>? = null

        // FIX: Removed complex `.also{}` blocks to prevent Kotlin Internal Compiler Errors
        try {
            if (page == 1) {
                extractor.fetchPage()
                pageData = extractor.initialPage
                pageCache[key] = pageData?.nextPage
            } else {
                val next = pageCache[key]
                if (next == null) {
                    return newHomePageResponse(emptyList(), false)
                }
                pageData = extractor.getPage(next)
                pageCache[key] = pageData?.nextPage
            }
        } catch (e: Exception) {
            return newHomePageResponse(emptyList(), false)
        }

        if (pageData == null) return newHomePageResponse(emptyList(), false)

        val results = pageData.items.map {
            it.toSearchResponse()
        }

        val headerName = try {
            val exName = extractor.name
            if (exName.isNullOrEmpty()) request.name else exName
        } catch (e: Exception) {
            request.name
        }.ifEmpty { "Trending" }

        return newHomePageResponse(
            listOf(
                HomePageList(
                    headerName,
                    results,
                    true
                )
            ),
            pageData.hasNextPage()
        )
    }

    private val searchPageCache = mutableMapOf<String, org.schabi.newpipe.extractor.Page?>()
    
    override suspend fun search(query: String, page: Int): SearchResponseList {
        val extractor = service.getSearchExtractor(query)
        var pageData: InfoItemsPage<out InfoItem>? = null

        try {
            if (page == 1 || !searchPageCache.containsKey(query)) {
                extractor.fetchPage()
                pageData = extractor.initialPage
                searchPageCache[query] = pageData?.nextPage
            } else {
                val next = searchPageCache[query]
                if (next == null) {
                    return newSearchResponseList(emptyList(), false)
                }
                pageData = extractor.getPage(next)
                searchPageCache[query] = pageData?.nextPage
            }
        } catch (e: Exception) {
            return newSearchResponseList(emptyList(), false)
        }

        if (pageData == null) return newSearchResponseList(emptyList(), false)

        val results = pageData.items.map {
            it.toSearchResponse()
        }

        return newSearchResponseList(
            results,
            pageData.hasNextPage()
        )
    }

    private fun getKioskExtractor(kioskId: String?): KioskExtractor<out InfoItem> {
        return if (kioskId.isNullOrBlank()) {
            service.kioskList.getDefaultKioskExtractor(null)
        } else {
            service.kioskList.getExtractorById(kioskId, null)
        }
    }

    private fun InfoItem.toSearchResponse(): SearchResponse {
        return newMovieSearchResponse(
            this.name ?: "Unknown",
            this.url ?: "",
            TvType.Others
        ) {
            this.posterUrl = this@toSearchResponse.thumbnails?.lastOrNull()?.url
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val urlType = getUrlType(url)

        return when (urlType) {
            UrlType.Video -> loadVideo(url)
            UrlType.Channel -> loadChannel(url)
            UrlType.Playlist -> loadPlaylist(url)
            UrlType.Unknown -> throw RuntimeException("Unsupported YouTube URL")
        }
    }

    private enum class UrlType {
        Video, Channel, Playlist, Unknown
    }

    private fun getUrlType(url: String): UrlType {
        return when {
            url.contains("/watch?v=") || url.contains("youtu.be/") -> UrlType.Video
            url.contains("/channel/") || url.contains("/@") || url.contains("/c/") -> UrlType.Channel
            url.contains("/playlist?list=") || (url.contains("/watch?v=") && url.contains("&list=")) -> UrlType.Playlist
            else -> UrlType.Unknown
        }
    }

    private suspend fun loadVideo(url: String): LoadResponse {
        val extractor = ServiceList.YouTube.getStreamExtractor(url)
        extractor.fetchPage()

        val info = StreamInfo.getInfo(extractor)
        val isLive = info.streamType?.name?.contains("LIVE") == true
        val type = if (isLive) TvType.Live else TvType.Others

        return newMovieLoadResponse(info.name, url, type, url) {
            this.plot = info.description?.content?.toString()
            this.posterUrl = info.thumbnails?.lastOrNull()?.url
            this.duration = info.duration?.toInt()

            val uploader = info.uploaderName
            if (!uploader.isNullOrBlank()) {
                this.actors = listOf(
                    ActorData(
                        Actor(
                            uploader,
                            info.uploaderAvatars?.lastOrNull()?.url ?: ""
                        )
                    )
                )
            }

            this.tags = info.tags?.take(5)?.toList()
        }
    }

    private suspend fun loadChannel(url: String): LoadResponse {
        val extractor = ServiceList.YouTube.getChannelExtractor(url)
        extractor.fetchPage()

        val channelName = extractor.name ?: "Unknown Channel"
        val channelDescription = extractor.description
        val channelAvatar = extractor.avatars?.lastOrNull()?.url
        val channelBanner = extractor.banners?.lastOrNull()?.url

        val tabs = extractor.tabs
        val videosTab = tabs?.firstOrNull { it.url.contains("/videos") } ?: tabs?.firstOrNull()
        ?: throw RuntimeException("No videos tab found")

        val videosExtractor = ServiceList.YouTube.getChannelTabExtractor(videosTab)
        val episodes = mutableListOf<Episode>()

        videosExtractor.fetchPage()
        var page = videosExtractor.initialPage
        
        episodes.addAll(page.items.map { item ->
            newEpisode(item.url) {
                this.name = item.name
                this.posterUrl = item.thumbnails?.lastOrNull()?.url
            }
        })

        var pagesLoaded = 1
        val maxPagesToLoad = 5

        while (page.hasNextPage() && pagesLoaded < maxPagesToLoad) {
            val nextPage = page.nextPage ?: break
            page = videosExtractor.getPage(nextPage)
            episodes.addAll(page.items.map { item ->
                newEpisode(item.url) {
                    this.name = item.name
                    this.posterUrl = item.thumbnails?.lastOrNull()?.url
                }
            })
            pagesLoaded++
        }

        return newTvSeriesLoadResponse(channelName, url, TvType.TvSeries, episodes) {
            this.plot = channelDescription
            this.posterUrl = channelBanner
            this.backgroundPosterUrl = channelBanner
            this.tags = listOf("Channel")
            this.actors = listOf(
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

        val playlistName = extractor.name ?: "Unknown Playlist"
        val playlistDescription = extractor.description?.content?.toString()
        val playlistThumbnail = extractor.thumbnails?.lastOrNull()?.url
        val uploaderName = extractor.uploaderName ?: ""

        val episodes = mutableListOf<Episode>()

        var page = extractor.initialPage
        episodes.addAll(page.items.map { item ->
            newEpisode(item.url) {
                this.name = item.name
                this.posterUrl = item.thumbnails?.lastOrNull()?.url
            }
        })

        var pagesLoaded = 1
        val maxPagesToLoad = 5

        while (page.hasNextPage() && pagesLoaded < maxPagesToLoad) {
            val nextPage = page.nextPage ?: break
            page = extractor.getPage(nextPage)
            episodes.addAll(page.items.map { item ->
                newEpisode(item.url) {
                    this.name = item.name
                    this.posterUrl = item.thumbnails?.lastOrNull()?.url
                }
            })
            pagesLoaded++
        }

        return newTvSeriesLoadResponse(playlistName, url, TvType.TvSeries, episodes) {
            this.plot = playlistDescription
            this.posterUrl = playlistThumbnail
            this.tags = if (uploaderName.isNotBlank()) listOf("Channel: $uploaderName") else listOf("Playlist")
            if (uploaderName.isNotBlank()) {
                this.actors = listOf(
                    ActorData(
                        Actor(
                            uploaderName,
                            extractor.uploaderAvatars?.lastOrNull()?.url ?: ""
                        )
                    )
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Correctly handle the URL stack and pass 'mainUrl' as referer
        val finalUrl = if (data.startsWith("http")) data else "https://www.youtube.com/watch?v=$data"
        
        return loadExtractor(
            finalUrl,
            mainUrl,
            subtitleCallback,
            callback
        )
    }
}
