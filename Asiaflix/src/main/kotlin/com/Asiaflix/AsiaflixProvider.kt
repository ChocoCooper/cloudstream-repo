package com.asiaflix

import android.util.Base64
import android.util.Log
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

    private val apiHeaders = mapOf(
        "x-access-control" to "web",
        "Origin"           to "https://asiaflix.net",
        "Referer"          to "https://asiaflix.net/",
        "Accept"           to "application/json, text/plain, */*",
        "User-Agent"       to "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
                              "(KHTML, like Gecko) Chrome/120 Mobile Safari/537.36",
    )

    private val mapper = jacksonObjectMapper()

    // ─────────────────────────────────────────────────────────────
    //  ▓▓ LOGGING HELPERS ▓▓
    //  Every message is prefixed "ASIAFLIX/" so a single logcat filter
    //  captures the entire provider lifecycle.
    // ─────────────────────────────────────────────────────────────

    /** Info-level breadcrumb. */
    private fun log(tag: String, msg: String) {
        Log.d("ASIAFLIX", "[$tag] $msg")
    }

    /** Error-level — shows up in red in Android Studio / Termux logcat. */
    private fun err(tag: String, msg: String) {
        Log.e("ASIAFLIX", "[$tag] $msg")
    }

    /**
     * Timed GET that logs:
     *  - the full URL (including query params)
     *  - HTTP status
     *  - wall-clock milliseconds
     *  - response size in bytes
     *  - a snippet of the body on non-2xx
     */
    private suspend fun timedGet(
        tag: String,
        url: String,
        headers: Map<String, String> = apiHeaders,
        params: Map<String, String>?  = null,
        timeout: Long = 25L,
    ): String {
        val stamp = System.currentTimeMillis()
        log(tag, "GET $url" + (params?.let { "  params=$it" } ?: ""))
        val resp = try {
            app.get(url, headers = headers, params = params, timeout = timeout)
        } catch (e: Exception) {
            err(tag, "EXCEPTION after ${System.currentTimeMillis() - stamp}ms: " +
                     "${e::class.simpleName}: ${e.message}")
            throw e
        }
        val ms = System.currentTimeMillis() - stamp
        val body = resp.text ?: ""
        log(tag, "→ ${resp.code} ${resp.message}  ${ms}ms  ${body.length}B")
        if (resp.code !in 200..299) {
            err(tag, "non-2xx body preview: ${body.take(400)}")
        } else if (body.length < 800) {
            // short bodies are cheap to show in full — very useful for
            // confirming exact response shape during debugging
            log(tag, "body: $body")
        } else {
            log(tag, "body preview: ${body.take(300)} …")
        }
        return body
    }

    /** Jackson decode with full error context. Never throws. */
    private inline fun <reified T> parseList(tag: String, json: String): List<T>? {
        return try {
            mapper.readValue(json, object : TypeReference<List<T>>() {})
        } catch (e: Exception) {
            err(tag, "JSON decode to List<${T::class.simpleName}> failed: ${e.message}")
            err(tag, "raw json (first 400): ${json.take(400)}")
            null
        }
    }

    /** Jackson decode of a single object. Never throws. */
    private inline fun <reified T> parseObj(tag: String, json: String): T? {
        return try {
            mapper.readValue(json, T::class.java)
        } catch (e: Exception) {
            err(tag, "JSON decode to ${T::class.simpleName} failed: ${e.message}")
            err(tag, "raw json (first 400): ${json.take(400)}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  Main page
    // ─────────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "home" to "Home",
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest,
    ): HomePageResponse {
        log("HOME", "getMainPage(page=$page, request=${request.name})")

        val json = try {
            timedGet("HOME", "$api/home")
        } catch (e: Exception) {
            err("HOME", "network failed: ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }

        val sections = parseList<HomeSection>("HOME", json)
        if (sections == null) {
            err("HOME", "parsing failed — returning empty page")
            return newHomePageResponse(request.name, emptyList())
        }

        log("HOME", "parsed ${sections.size} sections")

        val homeLists: List<HomePageList> = sections.mapIndexedNotNull { idx, s ->
            val items = s.data.orEmpty().mapNotNull { it.toSearchResponse() }
            val label = s.title?.takeIf { it.isNotBlank() }
                        ?: s.type?.takeIf { it.isNotBlank() }
                        ?: "Section $idx"
            log("HOME", "  [$idx] '$label' type='${s.type}' raw=${s.data?.size ?: 0} ok=${items.size}")
            if (items.isEmpty()) null
            else newHomePageList(label, items)
        }

        log("HOME", "returning ${homeLists.size} non-empty sections")
        return newHomePageResponse(homeLists)
    }

    // ─────────────────────────────────────────────────────────────
    //  Search
    // ─────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse> {
        log("SEARCH", "query='$query'")
        val json = try {
            timedGet(
                "SEARCH",
                "$api/drama/search",
                params = mapOf("q" to query, "page" to "1"),
            )
        } catch (e: Exception) {
            err("SEARCH", "network failed: ${e.message}")
            return emptyList()
        }

        val root = parseObj<SearchRoot>("SEARCH", json)
        if (root == null) {
            err("SEARCH", "parse failed")
            return emptyList()
        }

        val list = root.body ?: root.data ?: emptyList()
        log("SEARCH", "got ${list.size} items (totalPages=${root.totalPages})")
        return list.mapNotNull { it.toSearchResponse() }
    }

    // ─────────────────────────────────────────────────────────────
    //  Detail page → episode list
    // ─────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse {
        log("LOAD", "url='$url'")

        val slug = url.trimEnd('/').substringAfterLast('/')
        log("LOAD", "extracted slug='$slug'")
        if (slug.isBlank()) throw ErrorLoadingException("Invalid url: $url")

        val json = timedGet(
            "LOAD",
            "$api/drama/detail",
            params = mapOf("slug" to slug),
        )

        val detail = parseObj<DramaDetail>("LOAD", json)
            ?: throw ErrorLoadingException("Parse failed for slug=$slug")

        val showName = detail.name ?: slug
        val showId   = detail._id  ?: slug
        log("LOAD", "name='$showName'  _id='$showId'  " +
                   "episodes=${detail.episodes?.size ?: 0}  " +
                   "image=${detail.image?.take(60)}")

        val episodes: List<Episode> = detail.episodes.orEmpty().map { ep ->
            val serversJson = try {
                mapper.writeValueAsString(ep.streamUrls.orEmpty())
            } catch (e: Exception) {
                err("LOAD", "serialize servers for ep ${ep.number}: ${e.message}")
                "[]"
            }
            newEpisode("$mainUrl/play/$slug/$showId/${ep.number}") {
                this.name    = ep.title?.takeIf { it.isNotBlank() }
                               ?: "Episode ${ep.number}"
                this.episode = ep.number
                this.data    = serversJson
            }
        }
        log("LOAD", "built ${episodes.size} episodes")

        if (episodes.isEmpty()) {
            err("LOAD", "no episodes — the detail parser may need updating")
        }

        // Explicit constructor avoids any ambiguity across CloudStream
        // helper overloads.
        return TvSeriesLoadResponse(
            name      = showName,
            url       = url,
            type      = TvType.AsianDrama,
            episodes  = episodes,
            posterUrl = detail.image ?: detail.coverImage,
            plot      = detail.description,
            year      = detail.releaseYear,
        ).apply {
            log("LOAD", "returning TvSeriesLoadResponse")
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
        log("LINKS", "data length=${data.length}")

        val servers: List<StreamServer> = try {
            mapper.readValue(data, object : TypeReference<List<StreamServer>>() {})
        } catch (e: Exception) {
            err("LINKS", "servers decode failed: ${e.message}")
            err("LINKS", "raw data: ${data.take(400)}")
            emptyList()
        }

        log("LINKS", "decoded ${servers.size} servers")
        if (servers.isEmpty()) return false

        for ((i, srv) in servers.withIndex()) {
            val srcUrl  = srv.url?.takeIf { it.isNotBlank() }    ?: continue
            val srcName = srv.source?.takeIf { it.isNotBlank() } ?: continue

            log("LINKS", "[$i/$servers.size] trying source='$srcName'  url=${srcUrl.take(80)}")

            val encoded = try {
                Base64.encodeToString(srcUrl.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            } catch (e: Exception) {
                err("LINKS", "  base64 failed: ${e.message}")
                continue
            }

            val streamJson = try {
                timedGet(
                    "LINKS",
                    "$api/drama/get-stream-url",
                    params = mapOf("value" to encoded, "server" to srcName),
                )
            } catch (e: Exception) {
                err("LINKS", "  stream-url failed: ${e.message}")
                continue
            }

            val resp = parseObj<StreamResponse>("LINKS", streamJson)
            if (resp == null) {
                err("LINKS", "  parse failed")
                continue
            }

            val sources = resp.sources
                ?: listOfNotNull(
                    (resp.url ?: resp.link ?: resp.stream)?.let {
                        StreamSource(url = it, isM3U8 = it.contains(".m3u8"))
                    }
                )

            if (sources.isEmpty()) {
                err("LINKS", "  response had no sources — keys=${resp::class.members}")
                continue
            }

            for (s in sources) {
                val link = s.url?.takeIf { it.isNotBlank() } ?: continue
                log("LINKS", "  ✓ emitting: m3u8=${s.isM3U8}  url=${link.take(100)}")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name   = "$srcName • ${if (s.isM3U8 == true) "HLS" else "MP4"}",
                        url    = link,
                        type   = if (s.isM3U8 == true) ExtractorLinkType.M3U8
                                 else ExtractorLinkType.VIDEO,
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "Origin"  to mainUrl,
                        )
                    }
                )
            }
            return true
        }

        err("LINKS", "no server produced a stream")
        return false
    }

    // ─────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────
    private fun TitleItem.toSearchResponse(): SearchResponse? {
        val title = name ?: run {
            err("MAP", "TitleItem missing name — skipping. _id=$_id")
            return null
        }
        val slug = slug ?: _id ?: run {
            err("MAP", "TitleItem '$title' missing slug/_id — skipping")
            return null
        }
        return TvSeriesSearchResponse(
            name      = title,
            url       = "$mainUrl/drama/$slug",
            apiName   = this@AsiaflixProvider.name,
            type      = TvType.AsianDrama,
            posterUrl = image ?: coverImage,
        )
    }
}

// ═════════════════════════════════════════════════════════════════
//  DTOs — plain data classes, decoded with Jackson.
//  (No @Serializable needed since we don't use kotlinx.serialization.)
// ═════════════════════════════════════════════════════════════════

data class HomeSection(
    val title: String? = null,
    val type:  String? = null,
    val data:  List<TitleItem>? = null,
)

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

data class SearchRoot(
    val body:        List<TitleItem>? = null,
    val data:        List<TitleItem>? = null,
    val currentPage: Int?             = null,
    val nextPage:    Int?             = null,
    val hasNext:     Boolean?         = null,
    val totalPages:  Int?             = null,
    val totalItems:  Int?             = null,
)

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

data class EpisodeEntry(
    val title:      String? = null,
    val number:     Int     = 1,
    val epUrl:      String? = null,
    val type:       String? = null,
    val streamUrls: List<StreamServer>? = null,
)

data class StreamServer(
    val source: String? = null,
    val url:    String? = null,
    val type:   Int     = 0,
)

data class StreamResponse(
    val sources: List<StreamSource>? = null,
    val url:     String? = null,
    val link:    String? = null,
    val stream:  String? = null,
)

data class StreamSource(
    val url:    String? = null,
    val isM3U8: Boolean? = null,
)
