package com.dubstamil

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.sync.withPermit
import java.net.URLDecoder
import java.net.URLEncoder

class IsaidubProvider : MainAPI() {

    override var mainUrl = "https://isaidub.guru"
    override var name = "DubsTamil"
    override val supportedTypes = setOf(TvType.Movie)
    override var lang = "ta"
    override val hasMainPage = false
    override val hasSearch = true

    // ==========================================
    // SEARCH
    // ==========================================

    override suspend fun search(query: String): List<SearchResponse> {
        return getSharedSearchData(query)
    }

    // ==========================================
    // LOAD (Movie metadata page)
    // ==========================================

    override suspend fun load(url: String): LoadResponse? {
        // Parse synthetic URL built by getSharedSearchData()
        // Format: $mainUrl/synthetic_meta?t=...&y=...&p=...&url=...&s=...
        val uri = android.net.Uri.parse(url)

        val title    = URLDecoder.decode(uri.getQueryParameter("t") ?: "", "UTF-8")
        val year     = URLDecoder.decode(uri.getQueryParameter("y") ?: "", "UTF-8")
        val poster   = URLDecoder.decode(uri.getQueryParameter("p") ?: "", "UTF-8")

        if (title.isBlank()) return null

        // Find the actual isaidub movie page URL
        val movieLinks = searchDubbedMovieLinks(title, year)
        val moviePageUrl = movieLinks.firstOrNull() ?: return null

        // Store the resolved URL back into data so loadLinks() gets it
        val dataUrl = "$mainUrl/synthetic_meta?t=${URLEncoder.encode(title,"UTF-8")}" +
                "&y=${URLEncoder.encode(year,"UTF-8")}" +
                "&p=${URLEncoder.encode(poster,"UTF-8")}" +
                "&url=${URLEncoder.encode(moviePageUrl,"UTF-8")}"

        return newMovieLoadResponse(title, dataUrl, TvType.Movie, dataUrl) {
            this.posterUrl = poster.ifBlank { null }
            this.year      = year.toIntOrNull()
        }
    }

    // ==========================================
    // LOAD LINKS — mirrors interactive_browser()
    // ==========================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        val uri         = android.net.Uri.parse(data)
        val moviePageUrl = URLDecoder.decode(uri.getQueryParameter("url") ?: "", "UTF-8")

        if (moviePageUrl.isBlank()) return false

        val finalUrl = resolveFinalLink(moviePageUrl, depth = 0)
        if (finalUrl.isNullOrBlank()) return false

        callback.invoke(
            ExtractorLink(
                source  = name,
                name    = name,
                url     = finalUrl,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                isM3u8  = finalUrl.contains(".m3u8")
            )
        )
        return true
    }

    // ==========================================
    // RECURSIVE LINK RESOLVER
    // Mirrors Python's interactive_browser()
    // ==========================================

    private suspend fun resolveFinalLink(url: String, depth: Int): String? {
        if (depth > 15) return null

        // ── Is this already a media / direct-download URL? ──
        if (isFinalDownloadUrl(url)) return url

        val response = try {
            scrapeSemaphore.withPermit { app.get(url, timeout = 15, referer = mainUrl) }
        } catch (e: Exception) { return null }

        if (!response.isSuccessful) return null

        val html = response.text
        val doc  = response.document

        // ── Scan raw HTML for embedded final links (Python does this too) ──
        val rawFinal = extractFinalFromHtml(html)
        if (rawFinal != null) return rawFinal

        // ── Choose extractor based on URL (your Python router) ──
        val links: List<Pair<String, String>> =
            if ("isaidub.guru" in url && "/download/" !in url) {
                extractIsaidubLinks(doc, url).ifEmpty { extractDownloadLinks(doc, url) }
            } else {
                extractDownloadLinks(doc, url)
            }

        if (links.isEmpty()) return null

        // ── Auto-pick "Download Server" first (Python priority) ──
        val dlServer = links.firstOrNull { "download server" in it.first.lowercase() }
        if (dlServer != null) return resolveFinalLink(dlServer.second, depth + 1)

        // ── Auto-select if only one option ──
        if (links.size == 1) return resolveFinalLink(links[0].second, depth + 1)

        // ── Quality preference order (best → lowest) ──
        val preferred = listOf("1080", "720", "480", "360")
        for (q in preferred) {
            val match = links.firstOrNull { q in it.first }
            if (match != null) return resolveFinalLink(match.second, depth + 1)
        }

        // ── Fallback: first available link ──
        return resolveFinalLink(links[0].second, depth + 1)
    }

    // ==========================================
    // HELPERS  (mirror Python functions exactly)
    // ==========================================

    /** Python: is_final_download_url() */
    private fun isFinalDownloadUrl(url: String): Boolean {
        val low = url.lowercase()
        if (low.endsWith(".mp4") || low.endsWith(".mkv") ||
            low.endsWith(".avi") || low.endsWith(".mov") || low.endsWith(".webm")) return true
        if ("download.php" in low || "dl.php" in low) return true
        return false
    }

    /** Python: raw HTML regex scan inside interactive_browser() */
    private fun extractFinalFromHtml(html: String): String? {
        val dlPhp  = Regex("""https?://[^\s"'<>]*download\.php\?[^\s"'<>]*""", RegexOption.IGNORE_CASE).find(html)
        val dlPhp2 = Regex("""https?://[^\s"'<>]*dl\.php\?[^\s"'<>]*""",       RegexOption.IGNORE_CASE).find(html)
        val mp4    = Regex("""https?://[^\s"'<>]*\.mp4[^"'\s]*""",              RegexOption.IGNORE_CASE).find(html)
        return dlPhp?.value ?: dlPhp2?.value ?: mp4?.value
    }

    /** Python: extract_isaidub_links() — divs with class "f" or "bf" */
    private fun extractIsaidubLinks(doc: org.jsoup.nodes.Document, baseUrl: String): List<Pair<String, String>> {
        val links = mutableListOf<Pair<String, String>>()
        for (div in doc.select("div.f, div.bf")) {
            val a    = div.selectFirst("a[href]") ?: continue
            val text = a.text().trim()
            val href = a.attr("href")
            if (text.isBlank() || href.isBlank()) continue
            if ("sample" in text.lowercase()) continue

            val low = href.lowercase().trimEnd('/')
            if (low.endsWith("-tamil-dubbed-movie") || low.endsWith("-tamil-dubbed")) continue
            if ("?get-page=" in low || "/category/" in low) continue

            val full = resolveUrl(baseUrl, href)
            links.add(Pair(text, full))
        }
        return links.distinctBy { it.second }
    }

    /** Python: extract_download_links() */
    private fun extractDownloadLinks(doc: org.jsoup.nodes.Document, baseUrl: String): List<Pair<String, String>> {
        val links = mutableListOf<Pair<String, String>>()
        for (a in doc.select("a[href]")) {
            val text = a.text().trim()
            val href = a.attr("href")
            if (text.isBlank() || href.isBlank()) continue

            val full = resolveUrl(baseUrl, href)
            val low  = full.lowercase()
            val lowT = text.lowercase()

            when {
                "download server" in lowT || "download" in lowT -> links.add(Pair(text, full))
                low.endsWith(".mp4") || low.endsWith(".mkv") || low.endsWith(".avi") -> links.add(Pair(text, full))
                "download.php" in low || "dl.php" in low -> links.add(Pair(text, full))
                listOf("dubpage.xyz", "dubmv.xyz", "dub.uptodub.ch").any { it in low } -> links.add(Pair(text, full))
            }
        }
        return links.distinctBy { it.second }
    }

    /** Resolve relative URLs against base */
    private fun resolveUrl(base: String, href: String): String {
        return when {
            href.startsWith("http") -> href
            href.startsWith("//")   -> "https:$href"
            href.startsWith("/")    -> {
                val uri = android.net.Uri.parse(base)
                "${uri.scheme}://${uri.host}$href"
            }
            else -> {
                val stripped = base.trimEnd('/').substringBeforeLast('/')
                "$stripped/$href"
            }
        }
    }
}
