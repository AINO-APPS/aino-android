package app.aino.mobile.feature.search

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import java.net.URLEncoder
import kotlinx.serialization.json.Json

/** `globalSearch` in `client/src/api/notes.ts`. */
class SearchRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    /** Terms shorter than [SEARCH_MIN_CHARS] return empty buckets without a request, like the server. */
    fun search(query: String): SearchResults {
        val term = searchTerm(query)
        if (term.length < SEARCH_MIN_CHARS) return SearchResults()
        val encoded = URLEncoder.encode(term, "UTF-8").replace("+", "%20")
        // @api GET search
        val response = api.execute(ApiRequest(path = "search?q=$encoded"))
        return json.decodeFromString(response.bodyAsString().ifBlank { "{}" })
    }
}
