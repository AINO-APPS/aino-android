package app.aino.mobile.core.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.call.CallRingService
import app.aino.mobile.core.call.incomingCallServiceExtras
import app.aino.mobile.core.call.IncomingCallDismissals

class AinoFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PushNotifications.createChannels(this)
    }

    override fun onNewToken(token: String) {
        scope.launch {
            runCatching { PushTokenRegistrar(applicationContext).register(token) }
                .onFailure { android.util.Log.w("AinoPush", "Push token registration failed", it) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val validated = validatePushPayload(message.data).getOrElse {
            android.util.Log.w("AinoPush", "Dropped push type=${message.data["type"]}: ${it.message}")
            return
        }
        if (!PushDeduplicator(applicationContext).accept(validated.dedupeKey)) return
        var displayFallback = true
        when (validated.kind) {
            // Signal-style MessagingStyle with Reply / Mark-as-read. FCM delivers
            // onMessageReceived on a worker thread, so the avatar fetch is safe here.
            PushKind.ChatMessage -> {
                val shown = runCatching { ChatNotifications.show(applicationContext, validated) }
                    .onFailure { android.util.Log.w("AinoPush", "Conversation notification failed", it) }
                    .isSuccess
                displayFallback = !shown
            }
            PushKind.IncomingCall -> {
                val extras = incomingCallServiceExtras(
                    validated.data,
                    KeystoreTokenStore(applicationContext).getToken(),
                )
                // Android 12+ may reject a background FGS start. The ordinary
                // privacy-safe notification below remains the fallback surface.
                displayFallback = !CallRingService.start(applicationContext, extras)
            }
            PushKind.CallHandledElsewhere -> {
                val callId = validated.data.getValue("callId").toLong()
                // Accepting here also pushes "accepted" to this very device; only
                // other (still ringing) devices should stop.
                if (app.aino.mobile.core.call.CallSessionRuntime.get(applicationContext).acceptedHere(callId)) return
                CallRingService.stop(applicationContext)
                IncomingCallDismissals.dismiss(callId)
            }
            PushKind.General -> Unit
        }
        if (displayFallback || validated.kind == PushKind.CallHandledElsewhere) {
            PushNotifications.display(applicationContext, validated)
        }
    }
}