package com.reanime

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class ReanimeProvider : MainAPI() {
    override var mainUrl = "https://reanime.to"
    override var name = "Reanime"
    override var lang = "en"
    override val hasMainPage = false
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Anime)

    // ------------------------------------------------------------------
    // SEARCH
    // ------------------------------------------------------------------
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

    // ------------------------------------------------------------------
    // LOAD DETAILS
    // ------------------------------------------------------------------
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

    private fun extractObjectField(text: String, key: String): String? {
        val marker = "$key:{"
        val idx = text.indexOf(marker)
        if (idx == -1) return null
        var i = idx + marker.length - 1
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

    private fun extractArrayField(text: String, key: String): String? {
        val marker = "$key:["
        val idx = text.indexOf(marker)
        if (idx == -1) return null
        var i = idx + marker.length - 1
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

    // Fetch anilist_id from search API using slug
    private suspend fun getAnilistIdFromSearch(slug: String): Int? {
        return try {
            val encoded = URLEncoder.encode(slug, "UTF-8")
            val url = "$mainUrl/api/v1/search?q=$encoded&limit=1"
            val res = app.get(url).parsedSafe<SearchApiResponse>() ?: return null
            res.results.firstOrNull()?.anilist_id
        } catch (e: Exception) {
            Log.e("ReanimeProvider", "Search fallback failed: ${e.message}")
            null
        }
    }

    // Robust anilist_id extraction
    private fun extractAnilistIdFromPayload(payload: String, animeObj: String?): Int? {
        animeObj?.let {
            extractIntField(it, "anilist_id")?.let { id -> return id }
        }
        extractIntField(payload, "anilist_id")?.let { id -> return id }
        Regex("\\banilist_id\\s*:\\s*[\"']?(\\d+)[\"']?").find(payload)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        val html = app.get(url).text
        val payload = extractKitStartPayload(html) ?: return null

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

        // Try extracting anilist_id from payload first
        var anilistId = extractAnilistIdFromPayload(payload, animeObj)

        // If not found, fallback to search API using slug from URL
        if (anilistId == null) {
            val slug = url.substringAfterLast("/")
            Log.w("ReanimeProvider", "anilist_id not found in payload, trying search API for slug: $slug")
            anilistId = getAnilistIdFromSearch(slug)
        }

        if (anilistId == null) {
            Log.e("ReanimeProvider", "Could not determine anilist_id for $url")
            return null
        }

        val description = extractStringField(animeObj, "description")
        val bannerImage = extractStringField(animeObj, "banner_image")
        val coverObj = extractObjectField(animeObj, "cover_image")
        val poster = coverObj?.let { extractStringField(it, "large") ?: extractStringField(it, "extra_large") }
        val genresArr = extractArrayField(animeObj, "genres")
        val genres = genresArr?.let { extractStringArray(it) } ?: emptyList()

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
            val epTitle = extractStringField(epObj, "title") ?: "Episode $epNumber"
            val epThumb = extractStringField(epObj, "thumbnail")
            val epDesc = extractStringField(epObj, "description")
            val isSubbed = extractBoolField(epObj, "subbed")
            val isDubbed = extractBoolField(epObj, "dubbed")

            val dataSub = "$anilistId|$epNumber|sub"
            val dataDub = "$anilistId|$epNumber|dub"

            fun buildEpisode(dataStr: String) = newEpisode(dataStr) {
                this.name = epTitle
                this.episode = epNumber
                this.posterUrl = epThumb
                this.description = epDesc
            }

            if (isSubbed) subEpisodes.add(buildEpisode(dataSub))
            if (isDubbed) dubEpisodes.add(buildEpisode(dataDub))
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

    // ------------------------------------------------------------------
    // LOAD LINKS
    // ------------------------------------------------------------------
    private data class FlixApiResponse(
        @JsonProperty("servers") val servers: List<FlixServer> = emptyList()
    )

    private data class FlixServer(
        @JsonProperty("data_link") val data_link: String? = null,
        @JsonProperty("server_name") val server_name: String? = null,
        @JsonProperty("dataLink") val dataLink: String? = null,
        @JsonProperty("serverName") val serverName: String? = null,
        @JsonProperty("dataType") val dataType: String? = null,
        @JsonProperty("data_type") val data_type: String? = null
    ) {
        val link: String get() = data_link ?: dataLink ?: ""
        val name: String get() = server_name ?: serverName ?: ""
        val type: String? get() = dataType ?: data_type
    }

    private data class ResolvedFields(
        val containerName: String,
        val arrayName: String,
        val objectName: String,
        val keyField: String,
        val ivField: String,
        val tokenField: String,
        val keyFrag2Field: String
    )

    private fun sha256Hex(input: String): String {
        val bytes = input.toByteArray(Charsets.UTF_8)
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun sha256(input: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input)
    }

    private fun resolveFields(seed: String): ResolvedFields {
        var e = seed
        for (o in 0 until 3) e = sha256Hex(e + o)
        var a = e
        for (o in 0 until 3) a = sha256Hex(a + o)
        return ResolvedFields(
            containerName = "cd_${e.substring(24, 32)}",
            arrayName = "ad_${e.substring(32, 40)}",
            objectName = "od_${e.substring(40, 48)}",
            keyField = "kf_${e.substring(8, 16)}",
            ivField = "ivf_${e.substring(16, 24)}",
            tokenField = "${e.substring(48, 64)}_${e.substring(56, 64)}",
            keyFrag2Field = "${a.substring(0, 16)}_${a.substring(16, 24)}"
        )
    }

    private fun extractDataObject(html: String): String? {
        val marker = "data:{"
        var idx = html.indexOf(marker)
        if (idx == -1) {
            idx = html.indexOf("data: {")
            if (idx == -1) return null
            return extractObjectFrom(html, idx + "data: {".length - 1)
        }
        return extractObjectFrom(html, idx + marker.length - 1)
    }

    private fun extractObjectFrom(text: String, startIdx: Int): String {
        var depth = 0
        var inString = false
        var escape = false
        var i = startIdx
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escape -> escape = false
                    c == '\\' -> escape = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(startIdx, i + 1)
                    }
                }
            }
            i++
        }
        return text.substring(startIdx)
    }

    private fun extractFirstObjectFromArray(arrayText: String): String? {
        val start = arrayText.indexOf('{')
        if (start == -1) return null
        return extractObjectFrom(arrayText, start)
    }

    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val spec = PBEKeySpec(
            password.toString(Charsets.ISO_8859_1).toCharArray(),
            salt,
            iterations,
            keyLength * 8
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(spec).encoded
    }

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val decrypted = cipher.doFinal(data)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("ReanimeProvider", "AES Decrypt Failed: ${e.message}")
            null
        }
    }

    private fun wasmMix(frag1: ByteArray, keyFrag2: ByteArray, tokenU: ByteArray, v: Int): ByteArray {
        val k = frag1.size
        val out = ByteArray(k)
        var global = v
        for (i in 0 until k) {
            var b = (frag1[i].toInt() and 0xff) xor (keyFrag2[i].toInt() and 0xff) xor (tokenU[i].toInt() and 0xff)
            b = (b ushr 2) or ((b shl 6) and 0xff)
            b = (b + 110) and 0xff
            b = (b - 75) and 0xff
            b = (b + 161) and 0xff
            val t = (i * 48 + global) and 0xff
            b = b xor t
            out[i] = b.toByte()
        }
        return out
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        if (parts.size < 2) {
            Log.e("ReanimeProvider", "Data split failed. Parts size: ${parts.size}")
            return false
        }
        val anilistId = parts[0].toIntOrNull() ?: run { Log.e("ReanimeProvider", "anilistId is null"); return false }
        val ep = parts[1].toIntOrNull() ?: run { Log.e("ReanimeProvider", "ep is null"); return false }
        val lang = parts.getOrNull(2)?.lowercase() ?: "sub"

        // 1. Get flixcloud embed URL
        val flixApiUrl = "$mainUrl/api/flix/$anilistId/$ep"
        val flixResp = app.get(flixApiUrl).parsedSafe<FlixApiResponse>() ?: run {
            Log.e("ReanimeProvider", "Failed to fetch FlixApiResponse")
            return false
        }

        val server = flixResp.servers.firstOrNull {
            it.link.contains("flixcloud", ignoreCase = true) &&
            (it.type?.lowercase() == lang || it.type == null)
        } ?: flixResp.servers.firstOrNull { it.link.contains("flixcloud", ignoreCase = true) }

        val embedUrl = server?.link ?: run {
            Log.e("ReanimeProvider", "No flixcloud server found")
            return false
        }

        // 2. Fetch embed page and extract inline data object
        val html = app.get(embedUrl).text
        val dataObj = extractDataObject(html) ?: run { Log.e("ReanimeProvider", "extractDataObject returned null"); return false }

        // 3. Extract necessary fields
        val seed = extractStringField(dataObj, "obfuscation_seed") ?: run { Log.e("ReanimeProvider", "obfuscation_seed not found"); return false }
        val obfCryptoData = extractObjectField(dataObj, "obfuscated_crypto_data") ?: run { Log.e("ReanimeProvider", "obfuscated_crypto_data not found"); return false }

        val fields = resolveFields(seed)

        val containerText = extractObjectField(obfCryptoData, fields.containerName) ?: run { Log.e("ReanimeProvider", "containerName ${fields.containerName} not found"); return false }
        val arrayText = extractArrayField(containerText, fields.arrayName) ?: run { Log.e("ReanimeProvider", "arrayName ${fields.arrayName} not found"); return false }
        val firstObj = extractFirstObjectFromArray(arrayText) ?: run { Log.e("ReanimeProvider", "firstObj not found in array"); return false }

        val frag1B64 = extractStringField(firstObj, fields.keyField) ?: run { Log.e("ReanimeProvider", "keyField ${fields.keyField} not found"); return false }
        val ivB64 = extractStringField(firstObj, fields.ivField) ?: run { Log.e("ReanimeProvider", "ivField ${fields.ivField} not found"); return false }
        val keyFrag2B64 = extractStringField(dataObj, fields.keyFrag2Field) ?: run { Log.e("ReanimeProvider", "keyFrag2Field ${fields.keyFrag2Field} not found"); return false }
        val L = extractStringField(dataObj, fields.tokenField) ?: run { Log.e("ReanimeProvider", "tokenField ${fields.tokenField} not found"); return false }

        // 4. Fetch /api/m3u8/{L}
        val tokenResp = app.get("$mainUrl/api/m3u8/$L").parsedSafe<Map<String, String>>() ?: run {
            Log.e("ReanimeProvider", "Failed to fetch token m3u8 JSON")
            return false
        }

        val k = sha256Hex(L + "vid").take(10)
        val i = sha256Hex(L + "key").take(10)
        val P = tokenResp[k] ?: run { Log.e("ReanimeProvider", "Token P missing. Keys: ${tokenResp.keys}"); return false }
        val U = tokenResp[i] ?: run { Log.e("ReanimeProvider", "Token U missing. Keys: ${tokenResp.keys}"); return false }

        // 5. Base64 decode
        val frag1 = Base64.decode(frag1B64, Base64.NO_WRAP)
        val keyFrag2 = Base64.decode(keyFrag2B64, Base64.NO_WRAP)
        val tokenU = Base64.decode(U, Base64.NO_WRAP)
        val encryptedData = Base64.decode(P, Base64.NO_WRAP)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)

        // 6. WASM mixing
        val v = seed.take(8).toInt(16)
        val password = wasmMix(frag1, keyFrag2, tokenU, v)

        // 7. PBKDF2
        val derived = pbkdf2Sha256(password, seed.toByteArray(Charsets.UTF_8), 1000, 32)

        // 8. XOR with seed
        val seedBytes = seed.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(32)
        for (j in 0 until 32) {
            xored[j] = (derived[j].toInt() xor seedBytes[j % seedBytes.size].toInt()).toByte()
        }

        // 9. SHA-256
        val aesKey = sha256(xored)

        // 10. AES-CBC decrypt
        val decryptedUrl = aesCbcDecrypt(encryptedData, aesKey, iv) ?: run { Log.e("ReanimeProvider", "Final AES string decryption failed"); return false }

        // 11. Return stream
        callback(
            newExtractorLink(
                name,
                "Reanime HD-2",
                decryptedUrl,
                ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
