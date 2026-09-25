package app.aino.mobile.feature.profile

import app.aino.mobile.core.auth.PasswordChangeRequest
import app.aino.mobile.core.auth.PasswordChangeResponse
import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import app.aino.mobile.core.network.TokenStore
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ProfileRepository(
    private val api: ApiClient,
    private val tokens: TokenStore? = null,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    fun load(): ProfileUser = decode(api.execute(ApiRequest(path = "profile")))

    fun updateProfile(fullName: String, username: String): ProfileUser =
        mutate("profile", UpdateProfilePayload(fullName.trim(), username.trim()), "PUT")

    fun updateEmail(email: String): ProfileUser =
        mutate("profile/email", UpdateEmailPayload(email.trim()), "PUT")

    /** The server rotates the session token; keep the bearer client signed in with it. */
    fun changePassword(current: String, next: String) {
        val response: PasswordChangeResponse = mutate("profile/password", PasswordChangeRequest(current, next), "PUT")
        tokens?.saveToken(response.token)
    }

    fun deleteAccount(password: String) {
        mutate<DeleteAccountPayload, JsonObject>("profile", DeleteAccountPayload(password), "DELETE")
    }

    /** `upload.single("avatar")` multipart field, like the web's FormData. */
    fun uploadAvatar(fileName: String, mimeType: String, bytes: ByteArray): AvatarResponse {
        val multipart = buildAvatarMultipart(fileName, mimeType, bytes, "aino-${UUID.randomUUID()}")
        try {
            return decode(
                api.execute(
                    ApiRequest(
                        "POST",
                        "profile/avatar",
                        headers = mapOf("Content-Type" to multipart.first),
                        body = multipart.second,
                    ),
                ),
            )
        } catch (error: ApiError.Http) {
            throw ProfileFailure(serverMessage(error) ?: "Avatar upload failed. Please try again.", error.statusCode, error)
        }
    }

    fun removeAvatar(): AvatarResponse = mutate("profile/avatar", Unit, "DELETE")

    fun faceStatus(): FaceStatus = decode(api.execute(ApiRequest(path = "profile/face-status")))

    fun clearFaceEnrollment() {
        mutate<Unit, JsonObject>("profile/face-enroll", Unit, "DELETE")
    }

    // ── Status v2 ────────────────────────────────────────────────────────────

    fun getStatus(): StatusPayload = decode(api.execute(ApiRequest(path = "me/status")))

    fun setStatus(status: String?): StatusPayload = mutate("me/status", SetStatusRequest(status), "PUT")

    fun setPresencePreference(preference: String): StatusPayload =
        mutate("me/status/presence-preference", PresencePreferenceRequest(preference), "PUT")

    fun activityPing() {
        mutate<Unit, JsonObject>("me/status/activity-ping", Unit, "POST")
    }

    // ── Theme ────────────────────────────────────────────────────────────────

    fun getTheme(): ThemePayload = decode(api.execute(ApiRequest(path = "tracker/theme")))

    fun setTheme(theme: String) {
        mutate<ThemePayload, JsonObject>("tracker/theme", ThemePayload(theme), "PUT")
    }

    // ── Notification prefs ───────────────────────────────────────────────────

    /** An unmigrated tenant returns `{}`; missing keys fall back to the web defaults. */
    fun notificationPrefs(): NotificationPrefs =
        decode(api.execute(ApiRequest(path = "profile/notification-prefs")))

    /** Partial update: the server merges it into the stored blob. */
    fun updateNotificationPrefs(partial: JsonObject): NotificationPrefs =
        mutate("profile/notification-prefs", partial, "PUT")

    // ── Biometric devices ────────────────────────────────────────────────────

    fun biometricDevices(): List<BiometricDevice> =
        decode<BiometricDevicesResponse>(api.execute(ApiRequest(path = "auth/biometric"))).devices

    fun revokeBiometricDevice(id: String) {
        val encoded = java.net.URLEncoder.encode(id, "UTF-8")
        // @api DELETE auth/biometric/:id
        mutate<Unit, JsonObject>("auth/biometric/$encoded", Unit, "DELETE")
    }

    /** ProfileMenu `confirmSignOut`: a remote session is clocked out before signing out. */
    fun clockOutForSignOut() {
        mutate<JsonObject, JsonObject>("tracker/clock-out", JsonObject(emptyMap()), "POST")
    }

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String = "POST"): R {
        try {
            // OkHttp requires a body for POST/PUT; DELETE may go without one.
            val bytes = if (body is Unit) ByteArray(0).takeIf { method != "DELETE" } else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            throw ProfileFailure(serverMessage(error) ?: "An error occurred", error.statusCode, error)
        }
    }

    /** "Username already taken" and "Email already in use" are server-only facts. */
    private fun serverMessage(error: ApiError.Http): String? = runCatching {
        json.parseToJsonElement(error.responseBody).jsonObject["error"]?.jsonPrimitive?.content
    }.getOrNull()

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString().ifBlank { "{}" })
}

class ProfileFailure(message: String, val statusCode: Int, cause: Throwable) : Exception(message, cause)

/** Returns `(contentType, body)` for a single `avatar` file part. */
fun buildAvatarMultipart(fileName: String, mimeType: String, bytes: ByteArray, boundary: String): Pair<String, ByteArray> {
    val safeName = fileName.replace(Regex("[\\r\\n\\\"/\\\\]"), "_").take(255).ifBlank { "avatar" }
    val out = ByteArrayOutputStream()
    fun text(value: String) = out.write(value.toByteArray(StandardCharsets.UTF_8))
    text("--$boundary\r\n")
    text("Content-Disposition: form-data; name=\"avatar\"; filename=\"$safeName\"\r\n")
    text("Content-Type: $mimeType\r\n\r\n")
    out.write(bytes)
    text("\r\n--$boundary--\r\n")
    return "multipart/form-data; boundary=$boundary" to out.toByteArray()
}
