package app.aino.mobile.core.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AinoFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PushNotifications.createChannels(this)
    }

    override fun onNewToken(token: String) {
        scope.launch { runCatching { PushTokenRegistrar(applicationContext).register(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val validated = validatePushPayload(message.data).getOrNull() ?: return
        if (!PushDeduplicator(applicationContext).accept(validated.dedupeKey)) return
        PushNotifications.display(applicationContext, validated)
    }
}