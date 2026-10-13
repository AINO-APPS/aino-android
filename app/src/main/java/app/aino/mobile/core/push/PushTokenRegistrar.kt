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

/** Version 4 adds group-avatar metadata, retaining alert links and reactions. */
@Serializable
data class DeviceTokenRequest(val deviceToken: String, val platform: String = "android", val pushVersion: Int = PUSH_PAYLOAD_VERSION)

const val PUSH_PAYLOAD_VERSION = 4

private val DEVICE_TOKEN_JSON = Json { encodeDefaults = true }

fun encodeDeviceTokenRequest(token: String): ByteArray =
    DEVICE_TOKEN_JSON.encodeToString(DeviceTokenRequest(token.trim())).toByteArray()

class PushTokenRegistrar(private val context: Context) {
    /**
     * Registers the current FCM token for the signed-in session. True when the
     * server holds it (or nothing needs registering); false asks the caller /
     * [PushTokenRefreshWorker] to retry.
     */
    fun syncCurrentToken(force: Boolean = false): Boolean {
        val ok = syncCurrentTokenNoRetry(force)
        if (ok) {
            if (!KeystoreTokenStore(context).getToken().isNullOrBlank()) PushTokenRefreshWorker.schedule(context)
        } else {
            PushTokenRefreshWorker.retrySoon(context)
        }
        return ok
    }

    internal fun syncCurrentTokenNoRetry(force: Boolean = false): Boolean {
        val tokens = KeystoreTokenStore(context)
        if (tokens.getToken().isNullOrBlank()) return true
        if (FirebaseApp.getApps(context).isEmpty()) {
            // Local builds without app/google-services.json: the server will log
            // push_skip_no_tokens for this user and no push can ever arrive.
            android.util.Log.w(TAG, "Firebase is not configured (no google-services.json); push notifications are disabled")
            return true
        }
        return runCatching {
            val fcm = Tasks.await(FirebaseMessaging.getInstance().token, 20, TimeUnit.SECONDS)
            register(fcm, force)
        }.onFailure {
            android.util.Log.w(TAG, "Push token sync failed: ${it.javaClass.simpleName}; retrying in the background")
        }.isSuccess
    }

    companion object {
        private const val TAG = "AinoPush"
        private const val PREFS = "aino_push_registration"

        /**
         * Signal (`FcmRefreshJob`) never trusts a local "registered" flag for
         * long: the server may have dropped the token (session revoked, stale
         * token purge). Re-post at least daily.
         */
        internal const val TTL_MS = 24 * 60 * 60 * 1000L

        /** Credential cleared / sign-out: the next sign-in must register again. */
        fun forget(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            PushTokenRefreshWorker.cancel(context)
        }

        /** True only when the server acked this FCM token for the current session within [TTL_MS]. */
        fun isRegistered(context: Context): Boolean {
            val auth = KeystoreTokenStore(context).getToken() ?: return false
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getString("session", null) == sessionKey(auth) && prefs.getInt("version", 0) == PUSH_PAYLOAD_VERSION &&
                isFresh(prefs.getLong("at", 0), System.currentTimeMillis())
        }

        internal fun isFresh(ackedAt: Long, now: Long): Boolean = ackedAt in 1..now && now - ackedAt < TTL_MS
    }

    fun register(fcmToken: String, force: Boolean = false) {
        if (fcmToken.isBlank()) return
        val container = app.aino.mobile.core.AppContainer.get(context)
        val auth = container.tokens.getToken()
        if (auth.isNullOrBlank()) return
        // The server upserts (user, token); re-posting on every resume only
        // tripped its rate limit (429) and could drop a real token change.
        val key = registrationKey(fcmToken, auth)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && prefs.getString("key", null) == key && isFresh(prefs.getLong("at", 0), now)) return
        val session = sessionKey(auth)
        prefs.edit().remove("session").remove("at").commit()
        container.api.execute(ApiRequest("POST", "auth/device-token", body = encodeDeviceTokenRequest(fcmToken)))
        // The session changed while posting (sign-out, another account): leave it unregistered.
        val current = container.tokens.getToken() ?: return
        if (sessionKey(current) != session) return
        prefs.edit().putString("key", key).putLong("at", now).putString("session", session)
            .putInt("version", PUSH_PAYLOAD_VERSION).commit()
        android.util.Log.i(TAG, "Push token registered with the server")
    }
}

/**
 * The signed-in session (tenant, user and JWT `sid`). A new sign-in gets a new
 * `sid`, so it always re-registers: the server deletes a revoked session's push
 * tokens, and the old key made the app believe it was still registered.
 */
internal fun sessionKey(jwt: String): String {
    val claims = runCatching {
        val part = jwt.split('.')[1]
        val payload = String(java.util.Base64.getUrlDecoder().decode(part.padEnd((part.length + 3) / 4 * 4, '=')))
        Json.parseToJsonElement(payload) as kotlinx.serialization.json.JsonObject
    }.getOrNull() ?: return jwt.hashCode().toString()
    fun claim(name: String) = claims[name]?.toString()?.trim('"')
    val user = claim("id") ?: claim("userId") ?: claim("sub") ?: jwt.hashCode().toString()
    val tenant = claim("tenant_id") ?: claim("tenantId") ?: "0"
    return "$tenant:$user:${claim("sid").orEmpty()}"
}

/** FCM token + signed-in session, so a different account or a new sign-in re-registers. */
internal fun registrationKey(fcmToken: String, jwt: String): String =
    "${fcmToken.hashCode()}:${sessionKey(jwt)}:v$PUSH_PAYLOAD_VERSION"