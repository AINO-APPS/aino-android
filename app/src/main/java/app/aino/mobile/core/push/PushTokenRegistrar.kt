package app.aino.mobile.core.push

import android.content.Context
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class DeviceTokenRequest(val deviceToken: String, val platform: String = "android")

private val DEVICE_TOKEN_JSON = Json { encodeDefaults = true }

fun encodeDeviceTokenRequest(token: String): ByteArray =
    DEVICE_TOKEN_JSON.encodeToString(DeviceTokenRequest(token.trim())).toByteArray()

class PushTokenRegistrar(private val context: Context) {
    fun syncCurrentToken() {
        val tokens = KeystoreTokenStore(context)
        if (tokens.getToken().isNullOrBlank()) return
        if (FirebaseApp.getApps(context).isEmpty()) {
            // Local builds without app/google-services.json: the server will log
            // push_skip_no_tokens for this user and no push can ever arrive.
            android.util.Log.w(TAG, "Firebase is not configured (no google-services.json); push notifications are disabled")
            return
        }
        runCatching {
            val fcm = Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
            register(fcm)
        }.onFailure { android.util.Log.w(TAG, "Push token sync failed", it) }
    }

    companion object {
        private const val TAG = "AinoPush"
        private const val PREFS = "aino_push_registration"
        private const val TTL_MS = 12 * 60 * 60 * 1000L

        /** Sign-out: the next sign-in must register again. */
        fun forget(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }

    fun register(fcmToken: String) {
        if (fcmToken.isBlank()) return
        val container = app.aino.mobile.core.AppContainer.get(context)
        val auth = container.tokens.getToken()
        if (auth.isNullOrBlank()) return
        // The server upserts (user, token); re-posting on every resume only
        // tripped its rate limit (429) and could drop a real token change.
        val key = registrationKey(fcmToken, auth)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (prefs.getString("key", null) == key && now - prefs.getLong("at", 0) < TTL_MS) return
        container.api.execute(ApiRequest("POST", "auth/device-token", body = encodeDeviceTokenRequest(fcmToken)))
        prefs.edit().putString("key", key).putLong("at", now).apply()
    }
}

/** FCM token + signed-in user (JWT `id`/`sub`), so a different account re-registers. */
internal fun registrationKey(fcmToken: String, jwt: String): String {
    val user = runCatching {
        val payload = String(java.util.Base64.getUrlDecoder().decode(jwt.split('.')[1].padEnd((jwt.split('.')[1].length + 3) / 4 * 4, '=')))
        Json.parseToJsonElement(payload).let { it as kotlinx.serialization.json.JsonObject }
            .let { (it["id"] ?: it["userId"] ?: it["sub"])?.toString() }
    }.getOrNull() ?: jwt.hashCode().toString()
    return "${fcmToken.hashCode()}:$user"
}