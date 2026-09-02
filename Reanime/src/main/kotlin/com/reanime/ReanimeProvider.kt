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
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
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
    // DETAIL PAGE & EPISODE API
    // ------------------------------------------------------------------
    private fun extractKitStartPayload(rawHtml: String): String? {
        val unescaped = Parser.unescapeEntities(rawHtml, false)
        val marker = "kit.start(app, element, "
        val idx = unescaped.indexOf(marker)
        if (idx == -1) return null
        var start = idx + marker.length
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
                    '"', '\'' -> { inString = true; stringChar = c }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { i++; break }
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
                    '"', '\'' -> { inString = true; stringChar = c }
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) { i++; break }
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
                    '"', '\'' -> { inString = true; stringChar = c }
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) { i++; break }
                    }
                }
            }
            i++
        }
        return text.substring(start, i)
    }

    private fun unescapeJsonString(s: String): String =
        s.replace("\\n", "\n").replace("\\r", "").replace("\\\"", "\"").replace("\\\\", "\\")

    private fun extractStringField(obj: String, key: String): String? {
        val regex = Regex("""(?:\b|['"])${Regex.escape(key)}(?:['"])?\s*:\s*["']([^"']*)["']""")
        val match = regex.find(obj) ?: return null
        return unescapeJsonString(match.groupValues[1])
    }

    private fun extractIntField(obj: String, key: String): Int? =
        Regex("\\b${Regex.escape(key)}:(\\d+)").find(obj)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractBoolField(obj: String, key: String): Boolean =
        Regex("\\b${Regex.escape(key)}:(true|false)").find(obj)?.groupValues?.get(1) == "true"

    private fun extractStringArray(arrayLiteral: String): List<String> =
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(arrayLiteral).map { unescapeJsonString(it.groupValues[1]) }.toList()

    // --------------------------------------------------------------
    // EPISODE API RESPONSE
    // --------------------------------------------------------------
    private data class EpisodeApiResponse(
        @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null,
        @JsonProperty("data") val data: List<EpisodeItem>? = null,
    )

    private data class EpisodeItem(
        @JsonProperty("episode_number") val episode_number: Int? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("subbed") val subbed: Boolean? = null,
        @JsonProperty("dubbed") val dubbed: Boolean? = null,
        @JsonProperty("sub") val sub: Boolean? = null,
        @JsonProperty("dub") val dub: Boolean? = null,
    ) {
        val epNum: Int get() = episode_number ?: number ?: 0
        val isSub: Boolean get() = subbed ?: sub ?: false
        val isDub: Boolean get() = dubbed ?: dub ?: false
    }

    override suspend fun load(url: String): LoadResponse? {
        Log.d("ReanimeProvider", "load($url) called")
        val html = app.get(url).text
        val payload = extractKitStartPayload(html)
        if (payload == null) {
            Log.e("ReanimeProvider", "extractKitStartPayload returned null")
            return null
        }

        val animeObj = extractObjectField(payload, "anime")
        if (animeObj == null) {
            Log.e("ReanimeProvider", "anime object not found in payload")
            return null
        }

        val animeId = extractStringField(animeObj, "anime_id")
        val anilistId = extractIntField(animeObj, "anilist_id")
        Log.d("ReanimeProvider", "animeId=$animeId anilistId=$anilistId")

        if (animeId == null || anilistId == null) {
            Log.e("ReanimeProvider", "Missing animeId or anilistId")
            return null
        }

        val episodeUrl = "$mainUrl/api/v1/anime/$animeId/episodes"
        val epResp = app.get(episodeUrl).parsedSafe<EpisodeApiResponse>()
        if (epResp == null) {
            Log.e("ReanimeProvider", "Failed to fetch episode list")
            return null
        }
        val episodes = epResp.episodes ?: epResp.data ?: emptyList()
        Log.d("ReanimeProvider", "Fetched ${episodes.size} episodes")

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()

        for (ep in episodes) {
            val epNum = ep.epNum
            if (epNum <= 0) continue
            val epTitle = ep.title ?: "Episode $epNum"
            val dataSub = "$anilistId|$epNum|sub"
            val dataDub = "$anilistId|$epNum|dub"

            fun buildEpisode(data: String) = newEpisode(data) {
                this.name = epTitle
                this.episode = epNum
                this.posterUrl = ep.thumbnail
                this.description = ep.description
            }

            if (ep.isSub) subEpisodes.add(buildEpisode(dataSub))
            if (ep.isDub) dubEpisodes.add(buildEpisode(dataDub))
        }

        Log.d("ReanimeProvider", "Sub episodes: ${subEpisodes.size}, Dub episodes: ${dubEpisodes.size}")

        val titleObj = extractObjectField(animeObj, "title")
        val title = if (titleObj != null) {
            extractStringField(titleObj, "english")
                ?: extractStringField(titleObj, "romaji")
                ?: extractStringField(titleObj, "native")
                ?: "Unknown"
        } else "Unknown"

        val coverObj = extractObjectField(animeObj, "cover_image")
        val poster = coverObj?.let {
            extractStringField(it, "large") ?: extractStringField(it, "extra_large")
        }
        val description = extractStringField(animeObj, "description")
        val genresArr = extractArrayField(animeObj, "genres")
        val genres = genresArr?.let { extractStringArray(it) } ?: emptyList()

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genres
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    // ------------------------------------------------------------------
    // LOAD LINKS – Pure Kotlin WASM mix + manual PBKDF2
    // ------------------------------------------------------------------
    private data class FlixApiResponse(
        @JsonProperty("servers") val servers: List<FlixServer> = emptyList()
    )

    private data class FlixServer(
        @JsonProperty("dataLink") val dataLink: String? = null,
        @JsonProperty("dataType") val dataType: String? = null,
    )

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
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun sha256(input: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(input)
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

    // Pure Kotlin implementation of WebAssembly mixing (correct)
    private fun wasmMix(frag1: ByteArray, keyFrag2: ByteArray, tokenU: ByteArray, v: Int): ByteArray {
        val k = frag1.size
        val out = ByteArray(k)
        var global = v
        for (i in 0 until k) {
            var b = (frag1[i].toInt() and 0xff) xor
                    (keyFrag2[i].toInt() and 0xff) xor
                    (tokenU[i].toInt() and 0xff)
            b = ((b ushr 2) or ((b shl 6) and 0xff)) and 0xff
            b = (b + 110) and 0xff
            b = (b - 75) and 0xff
            b = (b + 161) and 0xff
            val t = (i * 48 + global) and 0xff
            b = b xor t
            out[i] = b.toByte()
        }
        return out
    }

    // Manual PBKDF2-HMAC-SHA256 to avoid charset issues
    private fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password, "HmacSHA256"))
        val hLen = 32
        val blocks = (keyLength + hLen - 1) / hLen
        val derivedKey = ByteArray(blocks * hLen)
        val block = ByteArray(hLen)
        for (blockIndex in 1..blocks) {
            mac.reset()
            mac.update(salt)
            mac.update(byteArrayOf(
                (blockIndex ushr 24).toByte(),
                (blockIndex ushr 16).toByte(),
                (blockIndex ushr 8).toByte(),
                blockIndex.toByte()
            ))
            val u = mac.doFinal()
            System.arraycopy(u, 0, block, 0, hLen)
            for (i in 1 until iterations) {
                mac.reset()
                mac.update(u)
                val t = mac.doFinal()
                for (j in 0 until hLen) {
                    block[j] = (block[j].toInt() xor t[j].toInt()).toByte()
                }
            }
            System.arraycopy(block, 0, derivedKey, (blockIndex - 1) * hLen, hLen)
        }
        return derivedKey.copyOf(keyLength)
    }

    private fun aesCbcDecrypt(data: ByteArray, key: ByteArray, iv: ByteArray): String? {
        return try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            String(cipher.doFinal(data), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("ReanimeProvider", "AES decrypt failed", e)
            null
        }
    }

    private fun extractDataObjectFromEmbedHtml(html: String): String? {
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ReanimeProvider", "loadLinks($data) called")
        val parts = data.split("|")
        if (parts.size < 2) return false
        val anilistPart = parts[0].substringAfterLast('/')
        val anilistId = anilistPart.toIntOrNull() ?: return false
        val ep = parts[1].toIntOrNull() ?: return false
        val lang = parts.getOrNull(2)?.lowercase() ?: "sub"
        Log.d("ReanimeProvider", "anilistId=$anilistId ep=$ep lang=$lang")

        // 1. Get flixcloud embed URL
        val flixApiUrl = "$mainUrl/api/flix/$anilistId/$ep"
        val flixResp = app.get(flixApiUrl).parsedSafe<FlixApiResponse>() ?: return false
        val server = flixResp.servers.firstOrNull {
            it.dataLink?.contains("flixcloud") == true &&
            (it.dataType?.lowercase() == lang || it.dataType == null)
        } ?: flixResp.servers.firstOrNull { it.dataLink?.contains("flixcloud") == true } ?: return false
        val embedUrl = server.dataLink ?: return false
        Log.d("ReanimeProvider", "embedUrl=$embedUrl")

        // 2. Fetch embed page and extract data object
        val html = app.get(embedUrl).text
        val dataObjStr = extractDataObjectFromEmbedHtml(html) ?: return false
        val seed = extractStringField(dataObjStr, "obfuscation_seed") ?: return false
        val obfCryptoData = extractObjectField(dataObjStr, "obfuscated_crypto_data") ?: return false
        val fields = resolveFields(seed)

        val containerText = extractObjectField(obfCryptoData, fields.containerName) ?: return false
        val arrayText = extractArrayField(containerText, fields.arrayName) ?: return false
        val firstObj = extractFirstObjectFromArray(arrayText) ?: return false
        val frag1B64 = extractStringField(firstObj, fields.keyField) ?: return false
        val ivB64 = extractStringField(firstObj, fields.ivField) ?: return false

        Log.d("ReanimeProvider", "Looking for keyFrag2Field: ${fields.keyFrag2Field}")
        val keyFrag2B64 = extractStringField(dataObjStr, fields.keyFrag2Field)
        if (keyFrag2B64 == null) {
            Log.e("ReanimeProvider", "keyFrag2B64 missing. Field=${fields.keyFrag2Field}. Data snippet: ${dataObjStr.take(500)}")
            return false
        }

        val L = extractStringField(dataObjStr, fields.tokenField) ?: return false

        // 3. Fetch token
        val tokenUrl = "https://flixcloud.cc/api/m3u8/$L"
        val tokenResp = app.get(tokenUrl).parsedSafe<Map<String, String>>() ?: return false
        val k = sha256Hex(L + "vid").take(10)
        val i = sha256Hex(L + "key").take(10)
        val P = tokenResp[k] ?: return false
        val U = tokenResp[i] ?: return false

        // 4. Decode
        val frag1 = Base64.decode(frag1B64, Base64.NO_WRAP)
        val keyFrag2 = Base64.decode(keyFrag2B64, Base64.NO_WRAP)
        val tokenU = Base64.decode(U, Base64.NO_WRAP)
        val encrypted = Base64.decode(P, Base64.NO_WRAP)
        val iv = Base64.decode(ivB64, Base64.NO_WRAP)
        val v = seed.take(8).toLong(16).toInt()   // fixed hex parsing

        // 5. WASM mix (pure Kotlin)
        val password = wasmMix(frag1, keyFrag2, tokenU, v)

        // 6. PBKDF2 (manual)
        val derived = pbkdf2Sha256(password, seed.toByteArray(Charsets.UTF_8), 1000, 32)

        // 7. XOR with seed
        val seedBytes = seed.toByteArray(Charsets.UTF_8)
        val xored = ByteArray(32)
        for (j in 0 until 32) xored[j] = (derived[j].toInt() xor seedBytes[j % seedBytes.size].toInt()).toByte()

        // 8. SHA-256
        val aesKey = sha256(xored)

        // Log intermediate values for debugging
        Log.d("ReanimeProvider", "password=${password.toHex()}")
        Log.d("ReanimeProvider", "derived=${derived.toHex()}")
        Log.d("ReanimeProvider", "xored=${xored.toHex()}")
        Log.d("ReanimeProvider", "aesKey=${aesKey.toHex()}")
        Log.d("ReanimeProvider", "iv=${iv.toHex()}")
        Log.d("ReanimeProvider", "encrypted=${encrypted.toHex()}")

        // 9. AES-CBC decrypt
        val decryptedUrl = aesCbcDecrypt(encrypted, aesKey, iv) ?: return false

        // 10. Return stream
        callback(
            newExtractorLink(
                source = name,
                name = "Reanime HD-2",
                url = decryptedUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = embedUrl
                this.quality = Qualities.Unknown.value
            }
        )
        Log.d("ReanimeProvider", "Successfully provided stream: ${decryptedUrl.take(60)}...")
        return true
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
