package app.aino.mobile.core.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import app.aino.mobile.core.AppContainer
import app.aino.mobile.feature.chat.ChatRepository
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles the conversation notification's **Reply** (RemoteInput) and
 * **Mark as read** actions without opening the app (Signal
 * `RemoteReplyReceiver` / `MarkReadReceiver`), and the swipe-away delete intent.
 */
class ChatNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getLongExtra(ChatNotifications.EXTRA_CONVERSATION_ID, -1L)
        if (conversationId <= 0) return
        // Swipe-away needs no network: just forget the stacked lines.
        if (intent.action == ChatNotifications.ACTION_DISMISSED) {
            ChatNotifications.forget(conversationId)
            return
        }
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val repository = ChatRepository(AppContainer.get(app).api)
                when (intent.action) {
                    ChatNotifications.ACTION_REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)
                            ?.getCharSequence(ChatNotifications.KEY_REPLY)?.toString()?.trim()
                        if (text.isNullOrEmpty()) return@launch
                        val sent = runCatching {
                            repository.sendMessage(conversationId, text, clientMessageId = UUID.randomUUID().toString())
                        }.isSuccess
                        if (sent) runCatching { repository.markRead(conversationId) }
                        ChatNotifications.appendReply(app, conversationId, text, failed = !sent)
                    }
                    ChatNotifications.ACTION_MARK_READ -> {
                        runCatching { repository.markRead(conversationId) }
                        ChatNotifications.cancel(app, conversationId)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
