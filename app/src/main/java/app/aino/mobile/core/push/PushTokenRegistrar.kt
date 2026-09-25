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

    private companion object {
        const val TAG = "AinoPush"
    }

    fun register(fcmToken: String) {
        if (fcmToken.isBlank()) return
        val container = app.aino.mobile.core.AppContainer.get(context)
        if (container.tokens.getToken().isNullOrBlank()) return
        val api = container.api
        val body = encodeDeviceTokenRequest(fcmToken)
        api.execute(ApiRequest("POST", "auth/device-token", body = body))
    }
}