package com.YoutubeTamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.stream.StreamInfo

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
        "IOF Tamil" to "https://youtube.com/@indooverseasfilms-tamil",
        "WAM Tamil Movies" to "https://youtube.com/@wamtamilmovies",
        "Sony Pictures Tamil" to "https://youtube.com/@sonypictures-tamil",
        "WorldMoviesLocal Tamil" to "https://youtube.com/@worldmovieslocal_tamil-zh6gd",
        "BookMyShow Stream Tamil" to "https://youtube.com/@bookmyshow_stream_tamil",
        "WorldCinema Tamil" to "https://youtube.com/@worldcinema_tamil",
        "Dimensions Pictures Tamil" to "https://youtube.com/playlist?list=PL1NedV9y84PJ74HjYfKPktCWYTj5XDtCw"
    )

    // Cache to store pagination state (nextPage tokens) for both kiosks and custom lists.
    private val pageCache = mutableMapOf<String, Page?>()

    // ---- Resolve @handle URLs to /channel/UC... URLs ----
    private suspend fun resolveChannelUrl(url: String): String {
        // Already a canonical channel URL — nothing to do
        if (url.contains("/channel/")) return url

        // Only handle @handle URLs (and legacy /c/ and /user/ if you want)
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
            url,      // keep original URL for later load()
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return loadExtractor(
            "https://youtube.com/watch?v=$data",
            subtitleCallback,
            callback
        )
    }
}
