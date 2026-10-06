package app.aino.mobile.core.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.call.CallRingService
import app.aino.mobile.core.call.incomingCallServiceExtras
import app.aino.mobile.core.call.IncomingCallDismissals

/** How long a chat push may hold FCM's (serial) worker thread to store the thread delta. */
private const val CHAT_SYNC_BUDGET_MS = 1_500L

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
                // The alert never waits on the network: post it (or the plain fallback) first.
                if (!shown) PushNotifications.display(applicationContext, validated)
                displayFallback = false
                // Signal: a message that reached the device is "delivered" even if the app is closed.
                validated.data["messageId"]?.toLongOrNull()?.let { id ->
                    scope.launch {
                        runCatching {
                            app.aino.mobile.core.AppContainer.get(applicationContext).api.execute(
                                app.aino.mobile.core.network.ApiRequest(method = "POST", path = "chat/messages/$id/delivered", body = ByteArray(0)),
                            )
                        }
                    }
                }
                // Signal keeps messages local before the tap: write the thread delta to Room
                // while FCM still holds this (possibly cold) process. FCM handles pushes one at
                // a time, so only the thread write is awaited, briefly, keeping call pushes prompt.
                val threadStored = kotlinx.coroutines.CompletableDeferred<Unit>()
                validated.data["conversationId"]?.toLongOrNull()?.let { conversationId ->
                    scope.launch {
                        try {
                            runCatching { PushSync.prefetchChat(applicationContext, conversationId) { threadStored.complete(Unit) } }
                                .onFailure { android.util.Log.w("AinoPush", "Chat push sync failed", it) }
                        } finally {
                            threadStored.complete(Unit)
                        }
                    }
                } ?: threadStored.complete(Unit)
                runBlocking { withTimeoutOrNull(CHAT_SYNC_BUDGET_MS) { threadStored.await() } }
            }
            PushKind.IncomingCall -> {
                val extras = incomingCallServiceExtras(
                    validated.data,
                    KeystoreTokenStore(applicationContext).getToken(),
                )
                // Android 12+ may reject a background FGS start; PushNotifications.display
                // then posts the same CallStyle full-screen-intent notification without it.
                val ringing = CallRingService.start(applicationContext, extras) ||
                    PushNotifications.display(applicationContext, validated)
                displayFallback = false
                // Tell the caller this phone is ringing ("Calling..." → "Ringing..."), only
                // when a ring is actually on screen here.
                if (ringing) app.aino.mobile.core.call.CallRingingAck.acknowledge(
                    applicationContext,
                    validated.data.getValue("callId").toLong(),
                    validated.data.getValue("conversationId").toLong(),
                    validated.data["meetingCode"],
                )
            }
            PushKind.CallHandledElsewhere -> {
                val callId = validated.data.getValue("callId").toLong()
                // Accepting here also pushes "accepted" to this very device; only
                // other (still ringing) devices should stop.
                if (app.aino.mobile.core.call.CallSessionRuntime.get(applicationContext).acceptedHere(callId)) return
                // `cancelled` / `ended` while ringing here leaves a missed call;
                // `accepted` / `rejected` means another of my devices took it.
                app.aino.mobile.core.call.MissedCalls.onRingEndedRemotely(
                    applicationContext,
                    callId,
                    validated.data.getValue("conversationId").toLong(),
                    validated.data["reason"],
                )
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