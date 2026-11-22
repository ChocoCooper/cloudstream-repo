package com.nartodrama

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document

/**
 * Verified against live responses (recon rounds 1-8):
 *  - /search?q=..  returns JSON when asked with Accept: application/json
 *  - catalogue pages (/featured, /genre/x): <article class="card" data-movie-title data-watch-url>,
 *    posters on img.nartodrama-api.online, pager = <a class="pager-link" href="...&page=2"> (24 cards/page)
 *  - series page:  /detail/watch/{slug}?lang=xx        (h1 title, "EP n" links, #tags)
 *  - episode page: /detail/watch/{slug}/{n}?lang=xx    (no challenge); sources are embedded in
 *        const episodeItemsRaw = [ {number, play_url, direct_play_url, direct_play_is_local,
 *                                   direct_play_is_hls, multi_resolutions, subtitle_url,
 *                                   multi_subtitles:[{language_code,label,subtitle_url}] }, ... ];
 *    subtitle URLs (/e/s/<signed>) return WebVTT.
 *  - refresh-source (the page player's own recovery call) works and returns the same keys as an
 *    episodeItemsRaw entry:
 *        GET <refreshSourceEdgeBase>/e/rs/detail/watch/{slug}/{ep}/refresh-source?lang=..&rs_ctx=<token>&force=1
 *
 * Source status (samples):
 *  - moviebox : play_url (HLS) works; hcdn3 mp4 links answer 428 to everything -> skipped
 *  - vyntage / flextv / pinedrama / reelshort(m3u8): direct links work, no Referer needed
 *  - shortmax / reelshort(R2): direct_play_url / play_url on cdn.narto-drama.com works; the signed
 *               shortmax-stream links answer 410 "link expired" even straight after a refresh -> skipped
 *  - starshort: page has no source; refresh-source returns a working m3u8 (verified in recon8)
 *  - bilitv   : play_url proxy and direct_play_url both 404, refresh returns the same URLs
 *               (token says dont_refresh_source=1) -> site-side outage; candidates are probed and
 *               dropped, so loadLinks returns false instead of dead links
 */
class NartoDramaProvider : MainAPI() {
    override var name = "NartoDrama"
    override var mainUrl = "https://narto-drama.com"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)
    override var lang = "en"
    override val hasMainPage = true

    private val uiLang = "en-US"

    // Hosts whose links failed for every request in recon6/recon8 (428 resp. 410 even when freshly refreshed).
    private val deadHosts = listOf("hcdn3.hakunaymatata.com", "shortmax-stream.narto-drama.com")

    private val jsonHeaders = mapOf(
        "Accept" to "application/json",
        "X-Requested-With" to "XMLHttpRequest"
    )

    data class SearchItem(
        @JsonProperty("id") val id: Long? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("poster_url") val posterUrl: String? = null,
        @JsonProperty("url") val url: String? = null,
    )

    data class SearchResp(
        @JsonProperty("ok") val ok: Boolean? = null,
        @JsonProperty("items") val items: List<SearchItem>? = null,
    )

    // ---------- helpers ----------
    private fun cleanSeriesUrl(path: String): String {
        val base = path.substringBefore("?")
        return fixUrl("$base?lang=$uiLang")
    }

    private suspend fun searchJson(query: String): List<SearchResponse> {
        val resp = fetch(
            "$mainUrl/search?q=${java.net.URLEncoder.encode(query.trim(), "UTF-8")}&lang=$uiLang",
            headers = jsonHeaders
        ).parsedSafe<SearchResp>() ?: return emptyList()

        return resp.items.orEmpty().mapNotNull { item ->
            val title = item.title?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = item.url ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, cleanSeriesUrl(path), TvType.TvSeries) {
                this.posterUrl = item.posterUrl
            }
        }.distinctBy { it.url }
    }

    /** /tag/x page (first page only, no paging): returns the cards. */
    private suspend fun catalogue(path: String): List<SearchResponse> {
        val doc = fetch("$mainUrl$path?lang=$uiLang").document
        return doc.select("article.card").mapNotNull { card ->
            val title = card.attr("data-movie-title")
                .ifBlank { card.selectFirst("h3.title")?.text().orEmpty() }
                .trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val href = card.attr("data-watch-url")
                .ifBlank { card.selectFirst("a.card-link-overlay")?.attr("href").orEmpty() }
                .takeIf { it.isNotBlank() } ?: return@mapNotNull null
            newTvSeriesSearchResponse(title, cleanSeriesUrl(href), TvType.TvSeries) {
                this.posterUrl = fixUrlNull(card.selectFirst("img.poster")?.attr("src"))
            }
        }.distinctBy { it.url }
    }

    /** Extracts `const <varName> = [ ... ];` from inline JS (bracket matching, string-aware). */
    private fun extractJsArray(html: String, varName: String): JSONArray? {
        val i = html.indexOf("$varName =")
        if (i < 0) return null
        val start = html.indexOf('[', i)
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (p in start until html.length) {
            val c = html[p]
            if (inStr) {
                if (esc) esc = false
                else if (c == '\\') esc = true
                else if (c == '"') inStr = false
            } else {
                when (c) {
                    '"' -> inStr = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            return try {
                                JSONArray(html.substring(start, p + 1))
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }
            }
        }
        return null
    }

    private fun JSONObject.str(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }

    /** refreshSourceContextToken = "<base64url(JSON)>.<signature>" - returns the decoded JSON part. */
    private fun refreshTokenPayload(token: String): JSONObject? = try {
        val part = token.substringBefore('.')
        val bytes = Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val s = String(bytes, Charsets.ISO_8859_1)
        JSONObject(s.substring(0, s.lastIndexOf('}') + 1))
    } catch (e: Exception) {
        null
    }

    /**
     * Cheap liveness check (ranged GET so mp4 files are not downloaded).
     * Returns the HTTP status, or -1 when the request itself failed (DNS/timeout/TLS).
     * 404/410/403 = the link is really dead; -1 = unknown (maybe only this network).
     */
    private suspend fun probeStatus(url: String): Int = try {
        app.get(url, referer = mainUrl, headers = mapOf("Range" to "bytes=0-1"), timeout = 8L).code
    } catch (e: Exception) {
        -1
    }

    /** Candidate whose probe failed at network level; only these are offered as a last resort. */
    private data class Pending(val url: String, val label: String, val hls: Boolean?)

    private val htmlHeaders = mapOf("Accept-Language" to "en-US,en;q=0.9")

    /** GET with back-off on 429 / 5xx (the site rate-limits: "series page status 429"). */
    private suspend fun fetch(url: String, referer: String? = null, headers: Map<String, String> = emptyMap()): com.lagradost.nicehttp.NiceResponse {
        var last: com.lagradost.nicehttp.NiceResponse? = null
        for (attempt in 0..3) {
            try {
                val r = app.get(url, referer = referer, headers = htmlHeaders + headers)
                if (r.code != 429 && r.code !in 500..599) return r
                last = r
            } catch (e: Exception) {
                if (attempt == 3) throw e
            }
            kotlinx.coroutines.delay(1500L * (attempt + 1))
        }
        return last!!
    }

    /** Always ask for the English UI so titles/tags come back in English. */
    private fun withLang(url: String): String {
        val u = url.substringBefore("#")
        val noLang = u.replace(Regex("""([?&])lang=[^&]*&?"""), "$1").trimEnd('?', '&')
        return noLang + (if (noLang.contains("?")) "&" else "?") + "lang=$uiLang"
    }

    // ---------- search ----------
    override suspend fun search(query: String): List<SearchResponse> = searchJson(query)

    // ---------- main page (homepage is Turnstile-challenged; /tag/x pages use the same article.card grid) ----------
    override val mainPage = mainPageOf(
        "/tag/love-after-marriage" to "Love After Marriage",
        "/tag/ancient" to "Ancient",
        "/tag/revenge" to "Revenge",
        "/tag/hidden-identity" to "Hidden Identity",
        "/tag/flash-marriage" to "Flash Marriage",
    )

    // Each row is a single, un-paged list (the first 24 cards of the tag page).
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList<SearchResponse>(), false)
        var items = catalogue(request.data)
        if (items.isEmpty()) items = searchJson(request.name) // fallback: verified JSON search
        return newHomePageResponse(request.name, items, false)
    }

    // ---------- load ----------
    override suspend fun load(url: String): LoadResponse? {
        val url = withLang(url)
        val doc: Document = fetch(url).document
        val slug = Regex("/detail/watch/([^/?#]+)").find(url)?.groupValues?.get(1) ?: return null

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.replace(Regex("""\s+-\s+Free Streaming.*$"""), "")?.trim()
            ?: return null
        val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst("meta[property=og:description]")?.attr("content")
        val tagList = doc.select("a[href*='/tag/']")
            .map { it.text().trim().removePrefix("#") }
            .filter { it.isNotBlank() }
            .distinct()

        val epRegex = Regex("""/detail/watch/${Regex.escape(slug)}/(\d+)""")
        val numbers = doc.select("a[href]")
            .mapNotNull { epRegex.find(it.attr("href"))?.groupValues?.get(1)?.toIntOrNull() }
            .distinct()
            .sorted()

        // Single-item titles (moviebox films are "S0E0") are exposed as movies.
        if (numbers.size <= 1) {
            val n = numbers.firstOrNull() ?: 1
            return newMovieLoadResponse(title, url, TvType.Movie, "$mainUrl/detail/watch/$slug/$n?lang=$uiLang") {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tagList
            }
        }

        val episodes = numbers.map { n ->
            newEpisode("$mainUrl/detail/watch/$slug/$n?lang=$uiLang") {
                this.name = "Episode $n"
                this.episode = n
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.tags = tagList
        }
    }

    // ---------- links ----------
    /** Returns true if the link was emitted. With [probe], HLS candidates must answer 2xx first. */
    private suspend fun emit(
        url: String,
        label: String,
        isHlsHint: Boolean?,
        probe: Boolean,
        unknown: MutableList<Pending>?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val u = fixUrl(url)
        val isHls = isHlsHint
            ?: (u.contains(".m3u8") || u.contains("/e/m/") || Regex("""[?&]mh=1(&|$)""").containsMatchIn(u))
        if (probe) {
            val st = probeStatus(u)
            if (st !in 200..299) {
                if (st == -1) unknown?.add(Pending(u, label, isHlsHint))
                return false
            }
        }
        callback(
            newExtractorLink(
                source = name,
                name = "$name $label".trim(),
                url = u,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = mainUrl
                this.quality = getQualityFromName(label)
            }
        )
        return true
    }

    /** Subtitles (only called when a video link exists): multi_subtitles + the single url. */
    private suspend fun emitSubs(item: JSONObject?, subSeen: HashSet<String>, subtitleCallback: (SubtitleFile) -> Unit) {
        item?.optJSONArray("multi_subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                val u = s.str("subtitle_url") ?: continue
                if (!subSeen.add(u)) continue
                subtitleCallback(newSubtitleFile(s.str("label") ?: s.str("language_code") ?: "Subtitle", fixUrl(u)))
            }
        }
        item?.str("subtitle_url")?.let { u ->
            if (subSeen.add(u)) subtitleCallback(newSubtitleFile("Subtitle", fixUrl(u)))
        }
    }

    /**
     * Emits every usable candidate of one episode object (an entry of episodeItemsRaw, or the JSON
     * returned by refresh-source, which uses the same keys). Order: local CDN mirror, play_url,
     * multi_resolutions, other direct URL. Returns true if at least one link was emitted.
     */
    private suspend fun emitSources(
        item: JSONObject?,
        fallbackPlay: String?,
        probe: Boolean,
        seen: HashSet<String>,
        subSeen: HashSet<String>,
        unknown: MutableList<Pending>?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var found = false

        val direct = item?.str("direct_play_url")
        val directLocal = item?.optBoolean("direct_play_is_local") == true
        val directHls = if (item != null && item.has("direct_play_is_hls")) item.optBoolean("direct_play_is_hls") else null

        // 1) local CDN mirror (cdn.narto-drama.com)
        if (direct != null && directLocal && seen.add(direct)) {
            if (emit(direct, "CDN", directHls, probe, unknown, callback)) found = true
        }

        // 2) the site's play_url (moviebox HLS, vyntage, flextv, pinedrama, reelshort m3u8 ...)
        val play = item?.str("play_url") ?: fallbackPlay
        if (!play.isNullOrBlank() && deadHosts.none { play.contains(it) } && seen.add(play)) {
            if (emit(play, "Auto", null, probe, unknown, callback)) found = true
        }

        // 3) explicit resolutions
        item?.optJSONArray("multi_resolutions")?.let { res ->
            for (i in 0 until res.length()) {
                val r = res.optJSONObject(i) ?: continue
                val u = r.str("stream_url") ?: continue
                if (deadHosts.any { u.contains(it) }) continue
                if (!seen.add(u)) continue
                val label = listOfNotNull(r.str("label"), r.str("codec")).joinToString(" ").ifBlank { "Auto" }
                if (emit(u, label, null, probe, unknown, callback)) found = true
            }
        }

        // 4) any other direct upstream URL
        if (direct != null && deadHosts.none { direct.contains(it) } && seen.add(direct)) {
            if (emit(direct, "Direct", directHls, probe, unknown, callback)) found = true
        }

        if (found) emitSubs(item, subSeen, subtitleCallback)
        return found
    }

    /**
     * The page's own recovery call (what its player does when a source is missing or dead):
     *   GET <refreshSourceEdgeBase>/e/rs/detail/watch/{slug}/{ep}/refresh-source?lang=..&rs_ctx=<token>&force=1
     *   Accept: application/json
     * rs_ctx is a signed token the page hands out. Titles whose token says dont_refresh_source=1
     * (bilitv) are skipped. Called at most once per loadLinks, and only if nothing else worked.
     */
    private suspend fun refreshSource(html: String, slug: String, epNum: Int, pageUrl: String): JSONObject? {
        val token = Regex("""refreshSourceContextToken\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: return null
        if (refreshTokenPayload(token)?.optInt("dont_refresh_source") == 1) return null

        val useEdge = Regex("""refreshSourceUseEdge\s*=\s*(true|false)""").find(html)?.groupValues?.get(1) == "true"
        val edgeBase = Regex("""refreshSourceEdgeBase\s*=\s*"([^"]*)"""").find(html)
            ?.groupValues?.get(1)?.replace("\\/", "/")?.trimEnd('/')
        // only the edge route the page uses for normal providers is implemented
        if (!useEdge || edgeBase.isNullOrBlank()) return null

        val url = "$edgeBase/e/rs/detail/watch/$slug/$epNum/refresh-source?lang=$uiLang&rs_ctx=$token&force=1"
        return try {
            val resp = app.get(
                url,
                headers = mapOf("Accept" to "application/json", "Referer" to pageUrl, "Origin" to mainUrl)
            )
            if (resp.code !in 200..299) null else JSONObject(resp.text)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val data = withLang(data)
        val html = fetch(data, referer = mainUrl).text
        val slug = Regex("""/detail/watch/([^/?#]+)""").find(data)?.groupValues?.get(1) ?: return false
        val epNum = Regex("""/detail/watch/[^/]+/(\d+)""").find(data)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        // This episode's entry in the embedded list (fall back to the first entry).
        val arr = extractJsArray(html, "episodeItemsRaw")
        var item: JSONObject? = null
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val n = if (o.has("route_episode_number")) o.optInt("route_episode_number") else o.optInt("number")
                if (n == epNum) { item = o; break }
            }
            if (item == null && arr.length() > 0) item = arr.optJSONObject(0)
        }


        val seen = HashSet<String>()
        val subSeen = HashSet<String>()
        val initial = Regex("""initialSourceUrl\s*=\s*"([^"]+)"""").find(html)?.groupValues?.get(1)?.replace("\\/", "/")

        // Probe every candidate (HLS and mp4): signed links expire (moviebox-edge answered 410,
        // bilitv answers 404). Candidates whose probe failed at network level are remembered.
        val unknown = mutableListOf<Pending>()
        var found = emitSources(item, initial, true, seen, subSeen, unknown, subtitleCallback, callback)

        // Nothing usable in the cached page (starshort, flextv oss, moviebox-edge): ask the site's
        // refresh-source endpoint once.
        if (!found) {
            val fresh = refreshSource(html, slug, epNum, data)
            if (fresh != null) {
                found = emitSources(fresh, null, true, seen, subSeen, unknown, subtitleCallback, callback)
            }
        }

        // Last resort: only links that could not be probed because of a network error (HTTP
        // 404/410/403 answers are dead and are never offered).
        if (!found && unknown.isNotEmpty()) {
            for (c in unknown.distinctBy { it.url }) {
                if (emit(c.url, c.label, c.hls, false, null, callback)) found = true
            }
            if (found) emitSubs(item, subSeen, subtitleCallback)
        }
        return found
    }
}