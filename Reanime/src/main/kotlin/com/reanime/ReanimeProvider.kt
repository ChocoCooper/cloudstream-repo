package com.reanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.parser.Parser
import java.net.URLEncoder

class ReanimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "Reanime"
    override var lang = "en"
    override val hasMainPage = false // see note at bottom of file
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)

    // ============================================================================================
    // SEARCH — reanime.to/api/v1/search returns real, properly-quoted JSON (confirmed live),
    // unlike the detail/watch pages below. Standard typed parsing works fine here.
    // ============================================================================================

    private data class ApiTitle(
        val english: String? = null,
        val native: String? = null,
        val romaji: String? = null,
    )

    private data class ApiCoverImage(
        val large: String? = null,
        val extra_large: String? = null,
        val medium: String? = null,
    )

    private data class SearchApiItem(
        val anime_id: String,
        val anilist_id: Int? = null,
        val title: ApiTitle,
        val cover_image: ApiCoverImage? = null,
        val episodes: Int? = null,
        val status: String? = null,
        val can_watch: Boolean = false,
    )

    private data class SearchApiResponse(
        val results: List<SearchApiItem> = emptyList(),
        val total: Int = 0,
    )

    private fun bestTitle(t: ApiTitle): String =
        t.english?.takeIf { it.isNotBlank() }
            ?: t.romaji?.takeIf { it.isNotBlank() }
            ?: t.native?.takeIf { it.isNotBlank() }
            ?: "Unknown"

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/api/v1/search?q=$encoded&limit=36&offset=0"

        val res = app.get(url).parsedSafe<SearchApiResponse>() ?: return emptyList()

        return res.results.map { item ->
            val title = bestTitle(item.title)
            val poster = item.cover_image?.large ?: item.cover_image?.extra_large ?: item.cover_image?.medium
            newAnimeSearchResponse(title, "$mainUrl/anime/${item.anime_id}", TvType.Anime) {
                this.posterUrl = poster
            }
        }
    }

    // ============================================================================================
    // LOAD — the anime detail page does NOT have a clean JSON API (confirmed: only /api/v1/search
    // does). Instead, the full anime record + episode list is embedded server-side inside a
    // `kit.start(app, element, {...})` script tag as a JS-object-literal with UNQUOTED keys
    // (e.g. `anime_id:"tokyo-revengers-cp6ewh"`, not `"anime_id":"..."`) - this is SvelteKit's own
    // hydration format, not standard JSON, so it cannot be parsed with Gson/kotlinx.serialization
    // directly. Rather than pull in a JSON5 parsing library, we extract exactly the fields we need
    // via string-aware brace-matching (to correctly isolate nested objects/arrays even though the
    // text contains stray braces inside string values) plus small per-field regexes. This is more
    // fragile than a real parser - if reanime.to reorders or renames these specific fields, this
    // will need updating - but it avoids adding a new dependency for one page's data shape.
    // ============================================================================================

    /**
     * Extracts the {...} object literal following `kit.start(app, element, ` in the page HTML.
     * Brace-matches with string-literal awareness so a stray '{' or '}' inside a description
     * or title string doesn't terminate the match early.
     */
    private fun extractKitStartPayload(rawHtml: String): String? {
        val unescaped = Parser.unescapeEntities(rawHtml, false)
        val marker = "kit.start(app, element, "
        val markerIdx = unescaped.indexOf(marker)
        if (markerIdx == -1) return null

        var start = markerIdx + marker.length
        while (start < unescaped.length && unescaped[start] != '{') start++
        if (start >= unescaped.length) return null

        var i = start
        var depth = 0
        var inString = false
        var stringChar = ' '
        var escape = false
        while (i < unescaped.length) {
            val c = unescaped[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == stringChar -> inString = false
                }
            } else {
                when (c) {
                    '"', '\'' -> {
                        inString = true
                        stringChar = c
                    }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            i++
                            break
                        }
                    }
                }
            }
            i++
        }
        return unescaped.substring(start, i)
    }

    /** Isolates the value of `key:{...}` (an unquoted-key object literal) within `text`, brace-matched. */
    private fun extractObjectField(text: String, key: String): String? {
        val marker = "$key:{"
        val idx = text.indexOf(marker)
        if (idx == -1) return null
        var i = idx + marker.length - 1 // position of the opening '{'
        var depth = 0
        var inString = false
        var stringChar = ' '
        var escape = false
        val start = i
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == stringChar -> inString = false
                }
            } else {
                when (c) {
                    '"', '\'' -> {
                        inString = true
                        stringChar = c
                    }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            i++
                            break
                        }
                    }
                }
            }
            i++
        }
        return text.substring(start, i)
    }

    /** Isolates the value of `key:[...]` (an array literal) within `text`, bracket-matched. */
    private fun extractArrayField(text: String, key: String): String? {
        val marker = "$key:["
        val idx = text.indexOf(marker)
        if (idx == -1) return null
        var i = idx + marker.length - 1 // position of the opening '['
        var depth = 0
        var inString = false
        var stringChar = ' '
        var escape = false
        val start = i
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == stringChar -> inString = false
                }
            } else {
                when (c) {
                    '"', '\'' -> {
                        inString = true
                        stringChar = c
                    }
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) {
                            i++
                            break
                        }
                    }
                }
            }
            i++
        }
        return text.substring(start, i)
    }

    /** Splits a bracket-matched array-literal's inner content into its top-level `{...}` elements. */
    private fun splitTopLevelObjects(arrayInner: String): List<String> {
        val results = mutableListOf<String>()
        var i = 0
        var depth = 0
        var inString = false
        var stringChar = ' '
        var escape = false
        var objStart = -1
        while (i < arrayInner.length) {
            val c = arrayInner[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == stringChar -> inString = false
                }
            } else {
                when (c) {
                    '"', '\'' -> {
                        inString = true
                        stringChar = c
                    }
                    '{' -> {
                        if (depth == 0) objStart = i
                        depth++
                    }
                    '}' -> {
                        depth--
                        if (depth == 0 && objStart != -1) {
                            results.add(arrayInner.substring(objStart, i + 1))
                            objStart = -1
                        }
                    }
                }
            }
            i++
        }
        return results
    }

    private fun unescapeJsonString(s: String): String =
        s.replace("\\n", "\n").replace("\\r", "").replace("\\\"", "\"").replace("\\\\", "\\")

    private fun extractStringField(obj: String, key: String): String? {
        val m = Regex("\\b${Regex.escape(key)}:\"((?:[^\"\\\\]|\\\\.)*)\"").find(obj) ?: return null
        return unescapeJsonString(m.groupValues[1])
    }

    private fun extractIntField(obj: String, key: String): Int? =
        Regex("\\b${Regex.escape(key)}:(\\d+)").find(obj)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractBoolField(obj: String, key: String): Boolean =
        Regex("\\b${Regex.escape(key)}:(true|false)").find(obj)?.groupValues?.get(1) == "true"

    private fun extractStringArray(arrayLiteral: String): List<String> =
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(arrayLiteral).map { unescapeJsonString(it.groupValues[1]) }.toList()

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text
        val payload = extractKitStartPayload(html) ?: return null

        // The 'anime' object appears somewhere in the payload (exact position varies by page
        // variant - confirmed both as a direct child and nested under episodeSources in testing).
        val animeObj = extractObjectField(payload, "anime") ?: return null

        val titleObj = extractObjectField(animeObj, "title")
        val title = if (titleObj != null) {
            bestTitle(
                ApiTitle(
                    english = extractStringField(titleObj, "english"),
                    romaji = extractStringField(titleObj, "romaji"),
                    native = extractStringField(titleObj, "native"),
                )
            )
        } else "Unknown"

        val anilistId = extractIntField(animeObj, "anilist_id")
        val animeId = extractStringField(animeObj, "anime_id")
        val description = extractStringField(animeObj, "description")
        val bannerImage = extractStringField(animeObj, "banner_image")
        val coverObj = extractObjectField(animeObj, "cover_image")
        val poster = coverObj?.let { extractStringField(it, "large") ?: extractStringField(it, "extra_large") }
        val genresArr = extractArrayField(animeObj, "genres")
        val genres = genresArr?.let { extractStringArray(it) } ?: emptyList()

        // Episodes: confirmed to appear in THREE possible shapes depending on page variant:
        //   1. nested inside anime: anime.episodes.data  (some page variants)
        //   2. sibling of anime, Jikan/MAL-style wrapped: episodes:{data:[...],limit,total,...}
        //      (confirmed shape on the plain /anime/{slug} detail page - this is the one that
        //      was missing before and caused an empty "coming soon" episode list)
        //   3. sibling of anime, flat array: episodes:[...]  (seen on /watch/ page variants)
        var episodeObjs: List<String> = emptyList()

        extractObjectField(animeObj, "episodes")?.let { episodesWrapper ->
            extractArrayField(episodesWrapper, "data")?.let { arr ->
                episodeObjs = splitTopLevelObjects(arr.removeSurrounding("[", "]"))
            }
        }
        if (episodeObjs.isEmpty()) {
            extractObjectField(payload, "episodes")?.let { episodesWrapper ->
                extractArrayField(episodesWrapper, "data")?.let { arr ->
                    episodeObjs = splitTopLevelObjects(arr.removeSurrounding("[", "]"))
                }
            }
        }
        if (episodeObjs.isEmpty()) {
            extractArrayField(payload, "episodes")?.let { arr ->
                episodeObjs = splitTopLevelObjects(arr.removeSurrounding("[", "]"))
            }
        }

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for (epObj in episodeObjs) {
            val epNumber = extractIntField(epObj, "episode_number") ?: continue
            val epId = extractStringField(epObj, "episodeId") ?: "ep-$epNumber"
            val epTitle = extractStringField(epObj, "title") ?: "Episode $epNumber"
            val epThumb = extractStringField(epObj, "thumbnail")
            val epDesc = extractStringField(epObj, "description")
            val isSubbed = extractBoolField(epObj, "subbed")
            val isDubbed = extractBoolField(epObj, "dubbed")

            // data carries what loadLinks() would need (anilist_id + episode number) - kept for
            // completeness even though loadLinks() below does not currently resolve a stream.
            val data = "$anilistId|$epNumber"

            fun buildEpisode() = newEpisode(data) {
                this.name = epTitle
                this.episode = epNumber
                this.posterUrl = epThumb
                this.description = epDesc
            }

            if (isSubbed) subEpisodes.add(buildEpisode())
            if (isDubbed) dubEpisodes.add(buildEpisode())
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.backgroundPosterUrl = bannerImage ?: poster
            this.plot = description
            this.tags = genres
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    // ============================================================================================
    // LOAD LINKS — intentionally not implemented.
    //
    // reanime.to/api/flix/{anilist_id}/{episode} (confirmed, works with a plain request, no
    // Cloudflare clearance needed) returns a server list whose only server, flixcloud.cc, requires:
    //   1. Passing a Cloudflare JS challenge on some of its sub-resources.
    //   2. Decrypting its /api/m3u8/{id} response and the master.m3u8 payload itself using a
    //      proprietary scheme: a custom WebAssembly module mixes several secrets to derive PBKDF2
    //      input, which is then stretched (1000 iterations, SHA-256), XORed against page-specific
    //      data, hashed again, and used as an AES-CBC key via the real WebCrypto Subtle API - all
    //      traced directly from flixcloud.cc's own JS bundle (nodes/12.i64MycaZ.js).
    //
    // This is a purpose-built protection layer against exactly this kind of extraction, not an
    // incidental obfuscation. Replicating it would mean re-implementing their proprietary WASM
    // decryption routine outside their own player - not something this provider attempts.
    //
    // search() and load() above are fully functional; playback via this provider is not.
    // ============================================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return false
    }
}

// ------------------------------------------------------------------------------------------------
// NOTE on hasMainPage / getMainPage:
// The reanime.to homepage (/home) DOES have real, server-rendered trending/popular anime data
// using the same kit.start payload format handled above, and getMainPage() could reasonably be
// added using the same extraction helpers. It's left out of this version to keep scope to what's
// been explicitly built and confirmed end-to-end (search + full detail/episode list) - adding
// mainPage support later is straightforward with the helpers already defined above.
// ------------------------------------------------------------------------------------------------
