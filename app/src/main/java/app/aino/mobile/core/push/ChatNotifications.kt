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
import app.aino.mobile.core.notifications.ConversationNotifications
import app.aino.mobile.core.notifications.NotificationSoundPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Signal-style conversation notifications (Signal `NotificationFactory`):
 * MessagingStyle with stacked unread lines, sender avatars, a conversation
 * shortcut, inline **Reply** (RemoteInput) and **Mark as read**.
 *
 * The status-bar small icon is monochrome by platform rule. Large and person
 * icons are always circular sender avatars, falling back to Signal-style
 * initials — never the launcher logo, which One UI renders oversized.
 */
object ChatNotifications {
    const val KEY_REPLY = "aino_reply_text"
    const val ACTION_REPLY = "app.aino.mobile.action.CHAT_REPLY"
    const val ACTION_MARK_READ = "app.aino.mobile.action.CHAT_MARK_READ"
    const val ACTION_DISMISSED = "app.aino.mobile.action.CHAT_DISMISSED"
    const val EXTRA_CONVERSATION_ID = "aino_conversation_id"
    private const val SELF_KEY = "aino-self"
    private const val DEFAULT_ACCENT = 0xFF2383E2.toInt()

    /** What is needed to re-post a conversation after an inline reply. */
    private data class Meta(
        val data: Map<String, String>,
        val title: String,
        val isGroup: Boolean,
        val channelId: String,
        val shortcutId: String?,
        val largeIcon: Bitmap,
        val account: String?,
        val generation: String,
        val photos: Map<String, Bitmap>,
    )

    private const val EXTRA_GENERATION = "aino_avatar_generation"
    private val meta = mutableMapOf<Long, Meta>()
    private val avatarJobs = mutableMapOf<Long, Job>()
    private val avatarScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal var avatarSource: NotificationAvatarSource = NotificationAvatarLoader
    internal var accountProvider: (Context) -> String? = ::notificationAccount

    private fun ensureConversation(context: Context, data: Map<String, String>, title: String, icon: Bitmap) =
        ConversationNotifications.ensureConversation(context, mapOf(
            "conversationId" to data.getValue("conversationId"), "title" to title,
            "senderId" to data.getValue("senderId"), "senderName" to data.getValue("senderName"),
            "parentChannelId" to PushNotifications.MESSAGES,
        ), iconBitmap = icon)

    private fun cachedPhoto(context: Context, url: String?, account: String?): Bitmap? =
        url?.takeIf(String::isNotBlank)?.let {
            runCatching { avatarSource.cached(context, it, account) }.getOrNull()
        }

    /** Shows (or restacks) the notification for a `chat_message` push. Blocking I/O: call off the main thread. */
    fun show(context: Context, push: ValidatedPush): PostingOutcome = showMessage(context, push.data)

    /** Internal presentation metadata, not a synthesized FCM payload. */
    @Synchronized
    fun showMessage(context: Context, data: Map<String, String>): PostingOutcome {
        val conversationId = data.getValue("conversationId").toLong()
        // Signal DefaultMessageNotifier: the thread on screen is marked read and
        // never notifies; only the short in-conversation tone may play.
        if (VisibleThread.isVisible(conversationId)) {
            cancel(context, conversationId)
            VisibleThread.reportSuppressed(conversationId)
            if (NotificationSoundPrefs.notificationAudible(context)) playInThreadTone(context)
            return PostingOutcome.Suppressed("Visible conversation")
        }
        val isGroup = data["isGroup"] == "true"
        val senderName = data.getValue("senderName")
        val title = if (isGroup) data["groupName"]?.takeIf(String::isNotBlank) ?: data.getValue("title") else senderName
        val senderKey = "aino-user-${data.getValue("senderId")}"
        val account = accountProvider(context)
        val previous = meta[conversationId]?.takeIf { it.account == account }
        if (previous == null) ChatNotificationHistory.clear(conversationId)
        val photos = previous?.photos.orEmpty().toMutableMap()
        val senderUrl = data["senderAvatar"]?.takeIf(String::isNotBlank)
        val avatar = cachedPhoto(context, senderUrl, account)
        if (senderUrl != null && avatar != null) photos[senderUrl] = avatar
        // The group icon belongs to the conversation; each message keeps its sender icon.
        val conversationKey = if (isGroup) "aino-conversation-$conversationId" else senderKey
        val conversationUrl = data[if (isGroup) "groupAvatar" else "senderAvatar"]?.takeIf(String::isNotBlank)
        val conversationPhoto = cachedPhoto(context, conversationUrl, account) ?: photos[conversationUrl]
        if (conversationUrl != null && conversationPhoto != null) photos[conversationUrl] = conversationPhoto
        val largeIcon = conversationPhoto ?: NotificationAvatars.fallback(title, conversationKey)
        val conversation = ensureConversation(context, data, title,
            conversationPhoto ?: NotificationAvatars.fallback(title, conversationKey, adaptive = true))
        val info = Meta(
            data.toMap(), title, isGroup,
            channelId = conversation?.get("channelId") ?: PushNotifications.MESSAGES,
            shortcutId = conversation?.get("shortcutId"),
            largeIcon = largeIcon,
            account = account,
            generation = previous?.generation ?: UUID.randomUUID().toString(),
            photos = photos,
        )
        notificationBlocker(context, info.channelId)?.let { return it }
        synchronized(this) { meta[conversationId] = info }
        val lines = ChatNotificationHistory.append(
            conversationId,
            ChatNotificationLine(
                text = stripSenderPrefix(data["body"].orEmpty(), senderName, isGroup),
                timestampMs = System.currentTimeMillis(),
                senderKey = senderKey,
                senderName = senderName,
                senderAvatar = data["senderAvatar"],
                messageId = data["messageId"]?.toLongOrNull(),
            ),
        )
        val outcome = post(context, conversationId, info, lines, silent = !NotificationSoundPrefs.notificationAudible(context))
        if (outcome == PostingOutcome.Posted) loadPhotos(context.applicationContext, conversationId, info)
        return outcome
    }

    private fun loadPhotos(context: Context, conversationId: Long, info: Meta) {
        avatarJobs.remove(conversationId)?.cancel()
        val source = avatarSource
        val urls = (ChatNotificationHistory.get(conversationId).mapNotNull { it.senderAvatar } +
            listOfNotNull(info.data[if (info.isGroup) "groupAvatar" else "senderAvatar"]))
            .filter(String::isNotBlank).distinct().filterNot(info.photos::containsKey)
        if (urls.isEmpty()) return
        avatarJobs[conversationId] = avatarScope.launch {
            for (url in urls) {
                if (accountProvider(context) != info.account) break
                val photo = try {
                    source.load(context, url, info.account)
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    android.util.Log.w("AinoPush", "Notification avatar unavailable: ${error.javaClass.simpleName}")
                    null
                } ?: continue
                synchronized(this@ChatNotifications) {
                    val current = meta[conversationId]
                    val active = runCatching {
                        context.getSystemService(android.app.NotificationManager::class.java)?.activeNotifications
                            ?.any { it.tag == NotificationTags.CHAT && it.id == conversationId.hashCode() &&
                                it.notification.extras.getString(EXTRA_GENERATION) == info.generation } == true
                    }.getOrDefault(false)
                    if (current?.generation != info.generation || accountProvider(context) != info.account ||
                        VisibleThread.isVisible(conversationId) || !active ||
                        notificationBlocker(context, current.channelId) != null) return@launch
                    val lines = ChatNotificationHistory.get(conversationId)
                    if (lines.isEmpty()) return@launch
                    val conversationUrl = current.data[if (current.isGroup) "groupAvatar" else "senderAvatar"]
                    val updated = current.copy(photos = current.photos + (url to photo),
                        largeIcon = if (url == conversationUrl) photo else current.largeIcon)
                    meta[conversationId] = updated
                    if (url == conversationUrl) ensureConversation(context, updated.data, updated.title, photo)
                    post(context, conversationId, updated, lines, silent = true, onlyAlertOnce = true)
                }
            }
        }
    }

    /** Adds the user's inline reply to the thread (as Signal does) and re-posts silently. */
    @Synchronized
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
    @Synchronized
    fun cancel(context: Context, conversationId: Long) {
        forget(conversationId)
        val manager = NotificationManagerCompat.from(context)
        runCatching { manager.cancel(NotificationTags.CHAT, conversationId.hashCode()) }
        // Posted untagged by app versions before 0.15.2.
        runCatching { manager.cancel(conversationId.hashCode()) }
    }

    /**
     * The user swiped the conversation away: drop its stacked lines so the next
     * message starts a fresh notification instead of resurrecting (and
     * re-counting) the dismissed ones. Nothing is marked read.
     */
    @Synchronized
    fun forget(conversationId: Long) {
        avatarJobs.remove(conversationId)?.cancel()
        ChatNotificationHistory.clear(conversationId)
        meta.remove(conversationId)
    }

    @Synchronized
    fun clearAll() {
        avatarJobs.values.forEach(Job::cancel)
        avatarJobs.clear()
        meta.clear()
        ChatNotificationHistory.clearAll()
    }

    private fun post(context: Context, conversationId: Long, info: Meta, lines: List<ChatNotificationLine>, silent: Boolean,
        onlyAlertOnce: Boolean = false): PostingOutcome {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return PostingOutcome.Blocked("Notification permission denied")
        notificationBlocker(context, info.channelId)?.let { return it }
        val icons = mutableMapOf<String, IconCompat>()
        val senders = lines.filter { it.senderKey != null }.associateBy { it.senderKey }
        fun iconFor(line: ChatNotificationLine, key: String): IconCompat = icons.getOrPut(key) {
            val latest = senders[key] ?: line
            val photo = info.photos[latest.senderAvatar] ?: cachedPhoto(context, latest.senderAvatar, info.account)
            IconCompat.createWithBitmap(photo ?: NotificationAvatars.fallback(latest.senderName, key))
        }
        val me = Person.Builder().setKey(SELF_KEY).setName("You").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setGroupConversation(info.isGroup)
            .setConversationTitle(if (info.isGroup) info.title else null)
        lines.forEach { line ->
            val person = line.senderKey?.let { key ->
                Person.Builder()
                    .setKey(key)
                    .setName(line.senderName)
                    .setIcon(iconFor(line, key))
                    .build()
            }
            style.addMessage(NotificationCompat.MessagingStyle.Message(line.text, line.timestampMs, person))
        }
        val requestCode = conversationId.hashCode()
        val contentIntent = PendingIntent.getActivity(
            context, requestCode,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putChatNotificationTapExtras(info.data)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, info.channelId)
            .setSmallIcon(R.drawable.ic_stat_aino)
            .setColor(brandColor(context))
            .setLargeIcon(info.largeIcon)
            .setStyle(style)
            .setContentTitle(info.title)
            .setContentText(lines.lastOrNull()?.text.orEmpty())
            .setContentIntent(contentIntent)
            .addAction(replyAction(context, conversationId, requestCode))
            .addAction(markReadAction(context, conversationId, requestCode))
            .setDeleteIntent(dismissIntent(context, conversationId, requestCode))
            .setAutoCancel(true)
            .setSilent(silent)
            .setOnlyAlertOnce(onlyAlertOnce)
            .setWhen(lines.lastOrNull()?.timestampMs ?: System.currentTimeMillis())
            .addExtras(android.os.Bundle().apply { putString(EXTRA_GENERATION, info.generation) })
            // Launcher badge: one per conversation. A per-line number made One UI
            // show the stacked backlog (up to MAX_LINES, own replies aside) as
            // unread long after the messages were read.
            .setNumber(1)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        info.shortcutId?.let(builder::setShortcutId)
        return runCatching {
            NotificationManagerCompat.from(context).notify(NotificationTags.CHAT, requestCode, builder.build())
            PostingOutcome.Posted
        }.getOrElse {
            android.util.Log.w("AinoPush", "Message notification posting failed: ${it.javaClass.simpleName}")
            PostingOutcome.Failed("Message notification posting failed")
        }
    }

    /** Swipe-away / clear-all: forget the stacked lines (Signal `DeleteNotificationReceiver`). */
    private fun dismissIntent(context: Context, conversationId: Long, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode + 2,
            actionIntent(context, ACTION_DISMISSED, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

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
            .putExtra("notification_account", notificationAccount(context))

    @Volatile private var cachedLogo: Bitmap? = null

    /** The AINO logo circle-cropped, for non-chat notifications (a square large icon is blown up by One UI). */
    fun appLogo(context: Context): Bitmap? = cachedLogo ?: runCatching {
        BitmapFactory.decodeResource(context.resources, R.drawable.aino_icon)?.let(NotificationAvatars::circle)
    }.getOrNull()?.also { cachedLogo = it }

    /** Signal's short in-conversation alert for a message landing in the thread on screen. */
    private fun playInThreadTone(context: Context) {
        runCatching {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION) ?: return
            android.media.RingtoneManager.getRingtone(context, uri)?.play()
        }
    }

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
