package app.aino.mobile.feature.profile

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProfileRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    fun load(): ProfileUser = decode(api.execute(ApiRequest(path = "profile")))

    fun updateProfile(fullName: String, username: String): ProfileUser =
        mutate("profile", UpdateProfilePayload(fullName.trim(), username.trim()), "PUT")

    fun updateEmail(email: String): ProfileUser =
        mutate("profile/email", UpdateEmailPayload(email.trim()), "PUT")

    fun faceStatus(): FaceStatus = decode(api.execute(ApiRequest(path = "profile/face-status")))

    /**
     * The search service short-circuits below two characters; returning empty
     * results here keeps that contract without spending a request.
     */
    fun search(term: String): SearchResults {
        if (!searchable(term)) return SearchResults()
        val query = java.net.URLEncoder.encode(normalizeSearchTerm(term), "UTF-8")
        return decode(api.execute(ApiRequest(path = "search?q=$query")))
    }

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String = "POST"): R {
        try {
            val bytes = if (body is Unit) null else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            throw ProfileFailure(serverMessage(error) ?: "Profile update failed", error.statusCode, error)
        }
    }

    /** "Username already taken" and "Email already in use" are server-only facts. */
    private fun serverMessage(error: ApiError.Http): String? = runCatching {
        json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString())
}

class ProfileFailure(message: String, val statusCode: Int, cause: Throwable) : Exception(message, cause)
