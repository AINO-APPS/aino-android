package app.aino.mobile.core.push

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.aino.mobile.MainActivity
import app.aino.mobile.R
import app.aino.mobile.core.AppContainer
import app.aino.mobile.feature.chat.ChatConversation
import app.aino.mobile.feature.chat.ChatReactionEvent
import app.aino.mobile.feature.chat.ChatRealtimeMessage
import app.aino.mobile.feature.chat.ChatRepository
import app.aino.mobile.core.db.AinoDatabase
import app.aino.mobile.core.db.ScopedCache
import app.aino.mobile.core.db.SessionScopeStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

internal fun reactionEligible(action: String, ownerId: Long?, actorId: Long, userId: Long, muted: Boolean, visible: Boolean): Boolean =
    action == "added" && ownerId == userId && actorId != userId && !muted && !visible

/** Resolves exact server identities before presenting a reaction in a nonselected conversation. */
internal fun resolveReactionNotification(
    repository: ChatRepository,
    event: ChatReactionEvent,
    knownOwner: Long?,
    knownConversation: ChatConversation?,
    currentUserId: Long,
    visible: Boolean,
    post: (Long, ChatConversation) -> PostingOutcome,
): PostingOutcome {
    val owner = knownOwner ?: runCatching {
        repository.loadMessages(event.conversationId, before = event.messageId + 1)
            .firstOrNull { it.id == event.messageId && (it.conversationId == null || it.conversationId == event.conversationId) }?.senderId
    }.getOrNull() ?: return PostingOutcome.Blocked("Message ownership not loaded")
    if (owner != currentUserId || event.userId == currentUserId || event.action != "added" || visible) {
        return PostingOutcome.Suppressed("Reaction ineligible or visible")
    }
    val conversation = knownConversation?.takeIf { it.id == event.conversationId } ?: runCatching {
        repository.loadConversations().firstOrNull { it.id == event.conversationId }
    }.getOrNull() ?: return PostingOutcome.Blocked("Conversation eligibility not loaded")
    if (conversation.isMuted) return PostingOutcome.Suppressed("Muted conversation")
    return post(owner, conversation)
}

/** Socket and FCM share one account-scoped presentation ledger; delivery receipts remain independent. */
object NotificationDelivery {
    private val json = Json { ignoreUnknownKeys = true }

    fun markConversationRead(context: Context, conversationId: Long, readAtMs: Long = System.currentTimeMillis()) {
        val account = notificationAccount(context) ?: return
        val prefs = context.getSharedPreferences("aino_notification_reads", Context.MODE_PRIVATE)
        val key = "$account:$conversationId"
        prefs.edit().putLong(key, maxOf(readAtMs, prefs.getLong(key, 0))).commit()
    }

    /** A reaction is handled by viewing its conversation, not by the original message's read state. */
    fun onConversationVisible(context: Context, conversationId: Long) {
        cancelReactions(context) { it.getLong("conversation_id") == conversationId }
    }

    fun onMessage(context: Context, message: ChatRealtimeMessage, conversation: ChatConversation?): PostingOutcome {
        if (message.senderId == notificationUser(context)) return PostingOutcome.Suppressed("Own message")
        return deliver(context, "msg:${message.id}") {
            if (isRead(context, message.conversationId, runCatching { java.time.Instant.parse(message.createdAt).toEpochMilli() }.getOrNull())) PostingOutcome.Suppressed("Message already read")
            else if ((conversation ?: cachedConversation(context, message.conversationId))?.isMuted == true) PostingOutcome.Suppressed("Muted conversation")
            else ChatNotifications.showMessage(context, mapOf(
                "conversationId" to message.conversationId.toString(), "messageId" to message.id.toString(),
                "senderId" to message.senderId.toString(), "senderName" to (message.senderName ?: message.senderUsername ?: "New message"),
                "senderAvatar" to message.senderAvatar.orEmpty(), "body" to (message.content?.takeIf(String::isNotBlank) ?: message.fileName ?: "Attachment"),
                "title" to (conversation?.groupName ?: message.senderName ?: "New message"),
                "isGroup" to (conversation?.isGroup == true).toString(), "groupName" to conversation?.groupName.orEmpty(),
            ))
        }
    }

    fun onReaction(context: Context, event: ChatReactionEvent, messageOwnerId: Long?, conversation: ChatConversation?): PostingOutcome {
        if (event.action != "added") {
            cancelReactions(context) { it.getLong("conversation_id") == event.conversationId &&
                it.getLong("message_id") == event.messageId && it.getLong("actor_id") == event.userId && it.getString("emoji") == event.emoji }
            return PostingOutcome.Suppressed("Reaction removed")
        }
        if (event.sentAt?.let { it + 86400 <= java.time.Instant.now().epochSecond } == true) return PostingOutcome.Suppressed("Reaction expired")
        val reactionId = event.reactionId?.takeIf { it > 0 } ?: return PostingOutcome.Suppressed("Missing addition identity")
        val user = notificationUser(context) ?: return PostingOutcome.Suppressed("Signed out")
        return resolveReactionNotification(
            ChatRepository(AppContainer.get(context).api), event,
            messageOwnerId ?: cachedMessageOwner(context, event.conversationId, event.messageId),
            conversation ?: cachedConversation(context, event.conversationId), user,
            VisibleThread.isVisible(event.conversationId),
        ) { owner, resolvedConversation ->
            reaction(context, reactionId, event.conversationId, event.messageId, owner, event.userId,
                event.fullName.ifBlank { "Someone" }, event.emoji, event.action, resolvedConversation.isMuted)
        }
    }

    fun onPush(context: Context, push: ValidatedPush): PostingOutcome {
        val account = notificationAccount(context) ?: return PostingOutcome.Suppressed("Signed out")
        if (push.tenantId != null && push.tenantId.toString() != account.substringBefore(':')) return PostingOutcome.Suppressed("Different tenant")
        val conversationId = push.data.getValue("conversationId").toLong()
        val conversation = cachedConversation(context, conversationId)
        if (push.kind == PushKind.ChatReaction) {
            val d = push.data
            return reaction(context, d.getValue("reactionId").toLong(), conversationId, d.getValue("messageId").toLong(),
                d.getValue("messageOwnerId").toLong(), d.getValue("actorId").toLong(), d.getValue("actorName"),
                d.getValue("emoji"), d.getValue("action"), conversation?.isMuted == true)
        }
        if (push.data["senderId"]?.toLongOrNull() == notificationUser(context)) return PostingOutcome.Suppressed("Own message")
        return deliver(context, push.dedupeKey) {
            if (isRead(context, conversationId, runCatching { java.time.Instant.parse(push.data.getValue("sentAt")).toEpochMilli() }.getOrNull())) PostingOutcome.Suppressed("Message already read")
            else if (conversation?.isMuted == true) PostingOutcome.Suppressed("Muted conversation")
            else runCatching { ChatNotifications.show(context, push) }.getOrElse { PostingOutcome.Failed("Rich notification failed") }
                .let { outcome ->
                    if (outcome is PostingOutcome.Failed && PushNotifications.display(context, push)) PostingOutcome.Posted else outcome
                }
        }
    }

    private fun reaction(context: Context, id: Long, conversationId: Long, messageId: Long, owner: Long?, actor: Long,
        name: String, emoji: String, action: String, muted: Boolean): PostingOutcome =
        deliver(context, "reaction:$id") {
            val user = notificationUser(context) ?: return@deliver PostingOutcome.Suppressed("Signed out")
            if (!reactionEligible(action, owner, actor, user, muted, VisibleThread.isVisible(conversationId))) {
                return@deliver PostingOutcome.Suppressed("Reaction ineligible or visible")
            }
            PushNotifications.createChannels(context)
            notificationBlocker(context, PushNotifications.REACTIONS)?.let { return@deliver it }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return@deliver PostingOutcome.Blocked("Notification permission denied")
            val intent = Intent(context, MainActivity::class.java).apply {
                setAction("app.aino.mobile.notification.REACTION.${notificationAccount(context)}.$id")
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra("push_type", "chat_reaction"); putExtra("conversation_id", conversationId.toString())
                putExtra("push_message_id", messageId.toString()); putExtra("push_dedupe_key", "reaction:$id")
            }
            val notification = NotificationCompat.Builder(context, PushNotifications.REACTIONS)
                .addExtras(android.os.Bundle().apply {
                    putLong("conversation_id", conversationId); putLong("message_id", messageId)
                    putLong("actor_id", actor); putString("emoji", emoji)
                })
                .setSmallIcon(R.drawable.ic_stat_aino).setColor(ChatNotifications.brandColor(context))
                .setLargeIcon(NotificationAvatars.fallback(name, "aino-user-$actor"))
                .setContentTitle(name).setContentText("Reacted $emoji to your message")
                .setContentIntent(PendingIntent.getActivity(context, id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setSilent(!app.aino.mobile.core.notifications.NotificationSoundPrefs.notificationAudible(context)).build()
            runCatching {
                NotificationManagerCompat.from(context).notify(NotificationTags.REACTION, id.hashCode(), notification)
                PostingOutcome.Posted
            }.getOrElse {
                android.util.Log.w("AinoPush", "Reaction notification posting failed: ${it.javaClass.simpleName}")
                if (it is SecurityException) PostingOutcome.Blocked("Notification permission revoked")
                else PostingOutcome.Failed("Reaction notification posting failed")
            }
        }

    @Synchronized
    private fun deliver(context: Context, key: String, post: () -> PostingOutcome): PostingOutcome {
        val account = notificationAccount(context) ?: return PostingOutcome.Suppressed("Signed out")
        val scoped = "$account:$key"
        PushDeduplicator(context).accept("received:$scoped")
        val store = NotificationPresentationStore(context, account)
        if (store.handled(key)) return PostingOutcome.Suppressed("Already presented or handled")
        val outcome = runCatching(post).getOrElse { PostingOutcome.Failed("Notification posting failed") }
        store.record(key, outcome)
        if (outcome is PostingOutcome.Blocked || outcome is PostingOutcome.Failed) android.util.Log.w("AinoPush", "Notification outcome=$outcome")
        return outcome
    }

    private fun isRead(context: Context, conversationId: Long, eventAtMs: Long?): Boolean {
        val account = notificationAccount(context) ?: return false
        val read = context.getSharedPreferences("aino_notification_reads", Context.MODE_PRIVATE).getLong("$account:$conversationId", 0)
        if (eventAtMs == null) return false
        if (read > 0 && eventAtMs <= read) return true
        val responses = AppContainer.get(context).responses
        responses.scope = account.replace(':', '_')
        val fetched = responses.fetchedAt("chat/conversations") ?: return false
        return fetched >= eventAtMs && cachedConversation(context, conversationId)?.unreadCount == 0
    }

    private fun cancelReactions(context: Context, matches: (android.os.Bundle) -> Boolean) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return
        runCatching {
            manager.activeNotifications.filter { it.tag == NotificationTags.REACTION && matches(it.notification.extras) }
                .forEach { manager.cancel(it.tag, it.id) }
        }
    }

    private fun cachedConversation(context: Context, id: Long): ChatConversation? = runCatching {
        val account = notificationAccount(context) ?: return@runCatching null
        val responses = AppContainer.get(context).responses
        responses.scope = account.replace(':', '_')
        responses.get("chat/conversations")?.toString(Charsets.UTF_8)
            ?.let { json.decodeFromString<List<ChatConversation>>(it).firstOrNull { c -> c.id == id } }
    }.getOrNull()

    private fun cachedMessageOwner(context: Context, conversationId: Long, messageId: Long): Long? =
        runCatching {
            runBlocking {
                val scope = SessionScopeStore(context).read() ?: return@runBlocking null
                ScopedCache(scope, AinoDatabase.get(context).dao()).messageSnapshot(conversationId)
                    .firstOrNull { it.messageId == messageId }?.senderId
            }
        }.getOrNull()
}
