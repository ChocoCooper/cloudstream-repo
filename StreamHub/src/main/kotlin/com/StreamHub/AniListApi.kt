package com.StreamHub

import com.lagradost.cloudstream3.app
import org.json.JSONObject
import java.net.URLEncoder

object AniListApi {

    private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"

    /**
     * Searches AniList by title and optional year, returning the best matching AniList ID.
     * Uses AniList's GET endpoint, which accepts GraphQL queries via URL params —
     * this avoids Cloudstream's `app.post` which only supports Map<String, String> bodies.
     */
    suspend fun getAnilistIdByTitle(title: String, year: String?): String? {
        val query = """
            query (${'$'}search: String) {
              Media(search: ${'$'}search, type: ANIME, sort: POPULARITY_DESC) {
                id
                title { romaji english native }
                startDate { year }
              }
            }
        """.trimIndent()

        val variables = JSONObject().apply { put("search", title) }

        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val encodedVars  = URLEncoder.encode(variables.toString(), "UTF-8")
        val url = "$ANILIST_GRAPHQL_URL?query=$encodedQuery&variables=$encodedVars"

        return try {
            val response = app.get(
                url,
                headers = mapOf("Accept" to "application/json"),
                timeout = 15L
            ).text

            val json = JSONObject(response)
            val media = json.optJSONObject("data")?.optJSONObject("Media")
            media?.optInt("id")?.takeIf { it > 0 }?.toString()
        } catch (_: Exception) {
            null
        }
    }
}
