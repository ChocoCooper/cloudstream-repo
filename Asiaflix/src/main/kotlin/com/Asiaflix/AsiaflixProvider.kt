package com.asiaflix

import android.util.Base64
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class AsiaflixProvider : MainAPI() {

    // ─────────────────────────────────────────────────────────────
    //  Identity
    // ─────────────────────────────────────────────────────────────
    override var mainUrl = "https://asiaflix.net"
    override var name    = "Asiaflix"
    override var lang    = "en"

    override val hasMainPage        = true
    override val hasQuickSearch     = true
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie,
    )

    // ─────────────────────────────────────────────────────────────
    //  Constants
    // ─────────────────────────────────────────────────────────────
    private val api = "https://api.asiaflix.net/v1"

    /**
     * Every request to api.asiaflix.net must carry this header.
     * Discovered in main.js:  setHeaders: { "x-access-control": "web" }
     */
    private val apiHeaders = mapOf(
        "x-access-control" to "web",
        "Origin"           to "https://asiaflix.net",
        "Referer"          to "https://asiaflix.net/",
        "Accept"           to "application/json, text/plain, */*",
    )

    private val mapper = jacksonObjectMapper()

    // ─────────────────────────────────────────────────────────────
    //  Main page
    // ─────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "recents"  to "Latest Updates",
        "trending" to "Top Trending",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        val sections = app.get("$api/home", headers = apiHeaders)
            .parsedSafe<List<HomeSection>>()
            ?: return newHomePageResponse(request.name, emptyList())

        val items = sections
            .flatMap { it.data.orEmpty() }
            .distinctBy { it._id }
            .mapNotNull { it.toSearchResponse() }

        return newHomePageResponse(request.name, items)
    }

    // ─────────────────────────────────────────────────────────────
    //  Search
    // ─────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        val root = app.get(
            "$api/drama/search",
            headers = apiHeaders,
            params  = mapOf("q" to query, "page" to "1"),
        ).parsedSafe<SearchRoot>() ?: return emptyList()

        val list = root.body ?: root.data ?: return emptyList()
        return list.mapNotNull { it.toSearchResponse() }
    }

    // ─────────────────────────────────────────────────────────────
    //  Detail page → episode list
    // ─────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        val slug = url.trimEnd('/').substringAfterLast('/')
        if (slug.isBlank()) throw ErrorLoadingException("Invalid url: $url")

        val detail = app.get(
            "$api/drama/detail",
            headers = apiHeaders,
            params  = mapOf("slug" to slug),
        ).parsedSafe<DramaDetail>()
            ?: throw ErrorLoadingException("No detail for slug=$slug")

        val showName = detail.name ?: slug
        val showId   = detail._id  ?: slug

        val episodes: List<Episode> = detail.episodes.orEmpty().map { ep ->
            val serversJson = try {
                mapper.writeValueAsString(ep.streamUrls.orEmpty())
            } catch (_: Exception) { "[]" }

            newEpisode("$mainUrl/play/$slug/$showId/${ep.number}") {
                this.name    = ep.title?.takeIf { it.isNotBlank() }
                               ?: "Episode ${ep.number}"
                this.episode = ep.number
                this.data    = serversJson
            }
        }

        // Named arguments avoid any parameter-order ambiguity across
        // CloudStream versions.
        return newTvSeriesLoadResponse(
            name     = showName,
            url      = url,
            type     = TvType.AsianDrama,
            episodes = episodes,
        ) {
            this.posterUrl = detail.image ?: detail.coverImage
            this.plot      = detail.description
            this.year      = detail.releaseYear
            this.tags      = detail.genres ?: detail.tags
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Playback
    // ─────────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val servers: List<StreamServer> = try {
            mapper.readValue(
                data,
                object : TypeReference<List<StreamServer>>() {},
            )
        } catch (_: Exception) {
            emptyList()
        }

        if (servers.isEmpty()) return false

        for (srv in servers) {
            val srcUrl  = srv.url?.takeIf { it.isNotBlank() }    ?: continue
            val srcName = srv.source?.takeIf { it.isNotBlank() } ?: continue

            // Mirror of the site's Id(s, "ENC") = btoa(s)
            val encoded = try {
                Base64.encodeToString(srcUrl.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } catch (_: Exception) { continue }

            val resp = try {
                app.get(
                    "$api/drama/get-stream-url",
                    headers = apiHeaders,
                    params  = mapOf("value" to encoded, "server" to srcName),
                    timeout = 25L,
                ).parsedSafe<StreamResponse>()
            } catch (_: Exception) { null } ?: continue

            val sources = resp.sources
                ?: listOfNotNull(
                    (resp.url ?: resp.link ?: resp.stream)?.let {
                        StreamSource(url = it, isM3U8 = it.contains(".m3u8"))
                    }
                )

            var emitted = false
            for (source in sources) {
                val link = source.url?.takeIf { it.isNotBlank() } ?: continue
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = "$srcName • ${if (source.isM3U8 == true) "HLS" else "MP4"}",
                        url    = link,
                        type   = if (source.isM3U8 == true) ExtractorLinkType.M3U8
                                 else ExtractorLinkType.VIDEO,
                    ) {
                        // The proxy host requires Referer; Range is supplied
                        // automatically by ExoPlayer.
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "Origin"  to mainUrl,
                        )
                    }
                )
                emitted = true
            }

            // First server that yields anything wins; delete this line to
            // queue every server as a user-selectable link instead.
            if (emitted) return true
        }

        return false
    }

    // ─────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────
    private fun TitleItem.toSearchResponse(): SearchResponse? {
        val title = name ?: return null
        val slug  = slug ?: _id ?: return null
        return newTvSeriesSearchResponse(
            name = title,
            url  = "$mainUrl/drama/$slug",
            type = TvType.AsianDrama,
        ) {
            this.posterUrl = image ?: coverImage
            this.year      = releaseYear
        }
    }
}

// ═════════════════════════════════════════════════════════════════
//  DTOs — shapes verified against api.asiaflix.net
// ═════════════════════════════════════════════════════════════════

@kotlinx.serialization.Serializable
data class HomeSection(
    val title: String? = null,
    val type:  String? = null,
    val data:  List<TitleItem>? = null,
)

@kotlinx.serialization.Serializable
data class TitleItem(
    val _id:            String? = null,
    val name:           String? = null,
    val slug:           String? = null,
    val normalizedName: String? = null,
    val image:          String? = null,
    val coverImage:     String? = null,
    val country:        String? = null,
    val status:         String? = null,
    val type:           String? = null,
    val releaseYear:    Int?    = null,
)

@kotlinx.serialization.Serializable
data class SearchRoot(
    val body:        List<TitleItem>? = null,
    val data:        List<TitleItem>? = null,
    val currentPage: Int?             = null,
    val nextPage:    Int?             = null,
    val hasNext:     Boolean?         = null,
    val totalPages:  Int?             = null,
    val totalItems:  Int?             = null,
)

@kotlinx.serialization.Serializable
data class DramaDetail(
    val _id:         String? = null,
    val name:        String? = null,
    val slug:        String? = null,
    val image:       String? = null,
    val coverImage:  String? = null,
    val description: String? = null,
    val releaseYear: Int?    = null,
    val genres:      List<String>? = null,
    val tags:        List<String>? = null,
    val episodes:    List<EpisodeEntry>? = null,
)

@kotlinx.serialization.Serializable
data class EpisodeEntry(
    val title:      String? = null,
    val number:     Int     = 1,
    val epUrl:      String? = null,
    val type:       String? = null,
    val streamUrls: List<StreamServer>? = null,
)

@kotlinx.serialization.Serializable
data class StreamServer(
    val source: String? = null,
    val url:    String? = null,
    val type:   Int     = 0,
)

@kotlinx.serialization.Serializable
data class StreamResponse(
    val sources: List<StreamSource>? = null,
    val url:     String? = null,
    val link:    String? = null,
    val stream:  String? = null,
)

@kotlinx.serialization.Serializable
data class StreamSource(
    val url:    String? = null,
    val isM3U8: Boolean? = null,
)
