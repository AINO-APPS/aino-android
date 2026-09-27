package app.aino.mobile.core.push

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.aino.mobile.MainActivity
import app.aino.mobile.R
import app.aino.mobile.core.AppContainer
import app.aino.mobile.core.call.AvatarLoader
import app.aino.mobile.core.notifications.ConversationNotifications
import app.aino.mobile.core.notifications.NotificationSoundPrefs
import app.aino.mobile.feature.chat.resolveChatMediaUrl

/**
 * Signal-style conversation notifications (Signal `NotificationFactory`):
 * MessagingStyle with stacked unread lines, sender avatars, a conversation
 * shortcut, inline **Reply** (RemoteInput) and **Mark as read**.
 *
 * The status-bar small icon is monochrome by platform rule; the coloured AINO
 * logo is the fallback large/person icon, and Android 11+ badges conversation
 * notifications with the launcher icon.
 */
object ChatNotifications {
    const val KEY_REPLY = "aino_reply_text"
    const val ACTION_REPLY = "app.aino.mobile.action.CHAT_REPLY"
    const val ACTION_MARK_READ = "app.aino.mobile.action.CHAT_MARK_READ"
    const val EXTRA_CONVERSATION_ID = "aino_conversation_id"
    private const val SELF_KEY = "aino-self"
    private const val DEFAULT_ACCENT = 0xFF2383E2.toInt()

    /** What is needed to re-post a conversation after an inline reply. */
    private data class Meta(
        val push: ValidatedPush,
        val title: String,
        val isGroup: Boolean,
        val channelId: String,
        val shortcutId: String?,
        val avatar: Bitmap?,
    )

    private val meta = mutableMapOf<Long, Meta>()

    /** Shows (or restacks) the notification for a `chat_message` push. Blocking I/O: call off the main thread. */
    fun show(context: Context, push: ValidatedPush) {
        val data = push.data
        val conversationId = data.getValue("conversationId").toLong()
        val isGroup = data["isGroup"] == "true"
        val senderName = data.getValue("senderName")
        val title = if (isGroup) data["groupName"]?.takeIf(String::isNotBlank) ?: data.getValue("title") else senderName
        val token = AppContainer.get(context).tokens.getToken()
        val avatar = data["senderAvatar"]?.takeIf(String::isNotBlank)?.let {
            AvatarLoader.load(context, resolveChatMediaUrl(it), token)
        }
        val conversation = ConversationNotifications.ensureConversation(
            context,
            mapOf(
                "conversationId" to conversationId.toString(),
                "title" to title,
                "senderId" to data.getValue("senderId"),
                "senderName" to senderName,
                "parentChannelId" to PushNotifications.MESSAGES,
            ),
        )
        val info = Meta(
            push, title, isGroup,
            channelId = conversation?.get("channelId") ?: PushNotifications.MESSAGES,
            shortcutId = conversation?.get("shortcutId"),
            avatar = avatar,
        )
        synchronized(this) { meta[conversationId] = info }
        val lines = ChatNotificationHistory.append(
            conversationId,
            ChatNotificationLine(
                text = stripSenderPrefix(data["body"].orEmpty(), senderName, isGroup),
                timestampMs = System.currentTimeMillis(),
                senderKey = "aino-user-${data.getValue("senderId")}",
                senderName = senderName,
                senderAvatar = data["senderAvatar"],
            ),
        )
        post(context, conversationId, info, lines, silent = !NotificationSoundPrefs.notificationAudible(context))
    }

    /** Adds the user's inline reply to the thread (as Signal does) and re-posts silently. */
    fun appendReply(context: Context, conversationId: Long, text: String, failed: Boolean) {
        val info = synchronized(this) { meta[conversationId] } ?: return
        val lines = ChatNotificationHistory.append(
            conversationId,
            ChatNotificationLine(
                text = if (failed) "Not sent: $text" else text,
                timestampMs = System.currentTimeMillis(),
                senderKey = null,
                senderName = "You",
            ),
        )
        post(context, conversationId, info, lines, silent = true)
    }

    /** Dismisses a conversation's notification (mark-read action, or the thread was opened in-app). */
    fun cancel(context: Context, conversationId: Long) {
        ChatNotificationHistory.clear(conversationId)
        synchronized(this) { meta.remove(conversationId) }
        runCatching { NotificationManagerCompat.from(context).cancel(conversationId.hashCode()) }
    }

    private fun post(context: Context, conversationId: Long, info: Meta, lines: List<ChatNotificationLine>, silent: Boolean) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val logo = appLogo(context)
        val logoIcon = logo?.let(IconCompat::createWithBitmap)
        val senderIcon = info.avatar?.let(IconCompat::createWithBitmap) ?: logoIcon
        val latestSenderKey = "aino-user-${info.push.data.getValue("senderId")}"
        val me = Person.Builder().setKey(SELF_KEY).setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setGroupConversation(info.isGroup)
            .setConversationTitle(if (info.isGroup) info.title else null)
        lines.forEach { line ->
            val person = line.senderKey?.let { key ->
                Person.Builder()
                    .setKey(key)
                    .setName(line.senderName)
                    .setIcon(if (key == latestSenderKey) senderIcon else logoIcon)
                    .build()
            }
            style.addMessage(NotificationCompat.MessagingStyle.Message(line.text, line.timestampMs, person))
        }
        val requestCode = conversationId.hashCode()
        val contentIntent = PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putPushTapExtras(info.push)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, info.channelId)
            .setSmallIcon(R.drawable.ic_stat_aino)
            .setColor(brandColor(context))
            .setLargeIcon(info.avatar ?: logo)
            .setStyle(style)
            .setContentTitle(info.title)
            .setContentText(lines.lastOrNull()?.text.orEmpty())
            .setContentIntent(contentIntent)
            .addAction(replyAction(context, conversationId, requestCode))
            .addAction(markReadAction(context, conversationId, requestCode))
            .setAutoCancel(true)
            .setSilent(silent)
            .setNumber(lines.count { it.senderKey != null })
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        info.shortcutId?.let(builder::setShortcutId)
        runCatching { NotificationManagerCompat.from(context).notify(requestCode, builder.build()) }
    }

    private fun replyAction(context: Context, conversationId: Long, requestCode: Int): NotificationCompat.Action {
        // RemoteInput needs a MUTABLE PendingIntent so the system can attach the typed text.
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        val intent = PendingIntent.getBroadcast(context, requestCode, actionIntent(context, ACTION_REPLY, conversationId), flags)
        return NotificationCompat.Action.Builder(IconCompat.createWithResource(context, R.drawable.ic_stat_aino), "Reply", intent)
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("Reply").build())
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setAllowGeneratedReplies(true)
            .setShowsUserInterface(false)
            .build()
    }

    private fun markReadAction(context: Context, conversationId: Long, requestCode: Int): NotificationCompat.Action {
        val intent = PendingIntent.getBroadcast(
            context, requestCode + 1,
            actionIntent(context, ACTION_MARK_READ, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Action.Builder(IconCompat.createWithResource(context, R.drawable.ic_stat_aino), "Mark as read", intent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
    }

    private fun actionIntent(context: Context, action: String, conversationId: Long) =
        Intent(context, ChatNotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_CONVERSATION_ID, conversationId)

    @Volatile private var cachedLogo: Bitmap? = null

    /** The coloured AINO logo (drawable/aino_icon.png), shown in the expanded notification. */
    fun appLogo(context: Context): Bitmap? = cachedLogo ?: runCatching {
        BitmapFactory.decodeResource(context.resources, R.drawable.aino_icon)
    }.getOrNull()?.also { cachedLogo = it }

    /** Org accent (web BrandingContext `--primary`) tints the notification header; default AINO blue. */
    fun brandColor(context: Context): Int {
        val accent = runCatching { AppContainer.get(context).branding.state.value.accentColor }.getOrNull()
        return parseHexColor(accent) ?: DEFAULT_ACCENT
    }
}

/** `#RGB` / `#RRGGBB` / `#AARRGGBB` → ARGB int, or null when unparseable. */
internal fun parseHexColor(value: String?): Int? {
    val hex = value?.trim()?.removePrefix("#") ?: return null
    val full = when (hex.length) {
        3 -> "FF" + hex.map { "$it$it" }.joinToString("")
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    return full.toLongOrNull(16)?.toInt()
}
