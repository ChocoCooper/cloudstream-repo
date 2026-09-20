package com.StreamHub

import com.lagradost.cloudstream3.app
import org.json.JSONObject

object AniListApi {

    private const val ANILIST_GRAPHQL_URL = "https://graphql.anilist.co"

    /**
     * Searches AniList by title and optional year, returning the best matching AniList ID.
     * Uses the public GraphQL API, so no authentication is required.
     */
    suspend fun getAnilistIdByTitle(title: String, year: String?): String? {
        // Use a conservative search query. The first result is usually the best match.
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

        val requestBody = JSONObject().apply {
            put("query", query)
            put("variables", variables)
        }

        return try {
            val response = app.post(
                ANILIST_GRAPHQL_URL,
                headers = mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json"
                ),
                data = requestBody.toString()
            ).text

            val json = JSONObject(response)
            val mediaArray = json.optJSONObject("data")?.optJSONArray("Media")
            // The response for a single Media object is under "data.Media", not an array.
            // Let's handle both cases for safety.
            val media = if (mediaArray != null) mediaArray.optJSONObject(0) else json.optJSONObject("data")?.optJSONObject("Media")

            val id = media?.optInt("id")?.takeIf { it > 0 }?.toString()
            id
        } catch (_: Exception) {
            null
        }
    }
}
