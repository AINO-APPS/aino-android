package app.aino.mobile.core.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.core.app.Person
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import java.io.File
import java.security.MessageDigest

/**
 * Registers Android platform conversation metadata used by System UI to promote
 * MessagingStyle notifications into the native conversation template.
 *
 * The notification remains owned by the caller. This object only creates:
 * 1. a stable, long-lived dynamic shortcut, and
 * 2. on Android 11+, a child notification channel linked to that shortcut.
 *
 * Every operation is best-effort and failures return null so message delivery can
 * continue through the normal parent channel.
 */
object ConversationNotifications {
    fun ensureConversation(
        context: Context,
        options: Map<String, String>,
        iconBitmap: android.graphics.Bitmap? = null,
    ): Map<String, String>? {
        val conversationId = options["conversationId"].orEmpty().trim()
        val title = options["title"].orEmpty().trim().ifEmpty { "Conversation" }
        val senderId = options["senderId"].orEmpty().trim()
        val senderName = options["senderName"].orEmpty().trim().ifEmpty { title }
        val avatarUri = options["avatarUri"].orEmpty().trim()
        val parentChannelId = options["parentChannelId"].orEmpty().trim()

        if (conversationId.isEmpty() || parentChannelId.isEmpty()) return null

        return try {
            val stableSuffix = conversationStableId(conversationId)
            val shortcutId = "wp-conversation-$stableSuffix"
            val channelId = app.aino.mobile.core.notifications.SoundChannels.conversationChannelId(parentChannelId, stableSuffix)

            val personBuilder = Person.Builder()
                .setKey(
                    if (senderId.isNotEmpty()) {
                        "aino-user-$senderId"
                    } else {
                        "aino-conversation-$stableSuffix"
                    },
                )
                .setName(senderName)

            val icon = loadLocalIcon(avatarUri) ?: iconBitmap?.let(IconCompat::createWithBitmap)
            icon?.let { personBuilder.setIcon(it) }
            val person = personBuilder.build()

            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    action = Intent.ACTION_VIEW
                    data = Uri.parse("aino://chat/$conversationId")
                    putExtra("conversationId", conversationId)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("aino://chat/$conversationId")).apply {
                    setPackage(context.packageName)
                    putExtra("conversationId", conversationId)
                }

            val shortcutBuilder = ShortcutInfoCompat.Builder(context, shortcutId)
                .setShortLabel(title.take(40))
                .setLongLabel(title.take(80))
                .setIntent(launchIntent)
                .setLongLived(true)
                .setPersons(arrayOf(person))
                .setCategories(setOf("android.shortcut.conversation", app.aino.mobile.core.share.SHARE_TARGET_CATEGORY))

            icon?.let { shortcutBuilder.setIcon(it) }

            ShortcutManagerCompat.pushDynamicShortcut(context, shortcutBuilder.build())

            // Android 11 introduced platform conversation channels. The parent
            // channel must already exist. Older versions still benefit from the
            // shortcut ID and continue using the parent channel.
            val conversationChannelReady =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    ensureConversationChannel(
                        context = context,
                        channelId = channelId,
                        channelName = title,
                        parentChannelId = parentChannelId,
                        shortcutId = shortcutId,
                    )
                } else {
                    false
                }

            mapOf(
                "shortcutId" to shortcutId,
                "channelId" to if (conversationChannelReady) channelId else parentChannelId,
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Direct Share: the most recent chats become share-sheet targets (Signal/WhatsApp publish
     * their top conversations the same way). Same ids as the notification shortcuts, so a
     * notified conversation keeps a single shortcut — which is why the icon must be the real
     * photo (or group collage): Android 11+ shows the shortcut icon on conversation
     * notifications, and an initials icon here would replace the photo a notification set.
     * Blocking (photo loads): call off the main thread.
     */
    fun publishShareTargets(context: Context, targets: List<ShareTargetInfo>) {
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context).coerceIn(1, MAX_SHARE_SHORTCUTS)
            val primary = app.aino.mobile.core.push.ChatNotifications.brandColor(context)
            val shown = targets.take(max)
            val account = app.aino.mobile.core.push.notificationAccount(context)
            val urls = shown.flatMap { it.photoUrls() }
            app.aino.mobile.core.push.NotificationAvatarLoader.prefetchBlocking(context, urls, account, SHARE_TARGET_PHOTO_BUDGET_MS)
            val photos = urls.distinct().mapNotNull { url ->
                runCatching { app.aino.mobile.core.push.NotificationAvatarLoader.cached(context, url, account) }.getOrNull()?.let { url to it }
            }.toMap()
            val shortcuts = shown.map { target ->
                val id = target.conversationId.toString()
                val bitmap = target.group?.let {
                    app.aino.mobile.core.avatar.ChatAvatarBitmaps.group(target.title, it, "conv-$id", primary, photos)
                } ?: app.aino.mobile.core.avatar.ChatAvatarBitmaps.user(target.title, primary, target.photoUrl?.let(photos::get))
                val icon = IconCompat.createWithBitmap(bitmap)
                ShortcutInfoCompat.Builder(context, "wp-conversation-${conversationStableId(id)}")
                    .setShortLabel(target.title.take(40))
                    .setLongLabel(target.title.take(80))
                    .setIcon(icon)
                    .setIntent(
                        Intent(Intent.ACTION_VIEW, Uri.parse("aino://chat/$id"))
                            .setPackage(context.packageName)
                            .putExtra("conversationId", id),
                    )
                    .setLongLived(true)
                    .setRank(0)
                    .setPerson(Person.Builder().setName(target.title).setKey("conversation-$id").setIcon(icon).build())
                    .setCategories(setOf("android.shortcut.conversation", app.aino.mobile.core.share.SHARE_TARGET_CATEGORY))
                    .build()
            }
            // Each push ranks first, so push oldest → newest to leave the latest chat on top.
            shortcuts.asReversed().forEach { ShortcutManagerCompat.pushDynamicShortcut(context, it) }
        }
    }

    /**
     * Pre-loads the photos recent chats would show in a notification, so a push that
     * arrives later (even with the app closed) finds them in the disk cache.
     */
    fun warmNotificationAvatars(context: Context, urls: List<String>) {
        runCatching {
            app.aino.mobile.core.push.NotificationAvatarLoader.prefetchBlocking(
                context, urls, app.aino.mobile.core.push.notificationAccount(context), WARM_BUDGET_MS,
            )
        }
    }

    data class ShareTargetInfo(
        val conversationId: Long,
        val title: String,
        val photoUrl: String? = null,
        val group: app.aino.mobile.core.avatar.GroupAvatarMetadata? = null,
    ) {
        fun photoUrls(): List<String> = (listOfNotNull(photoUrl, group?.photoUrl) +
            (if (group?.photoUrl.isNullOrBlank()) group?.members?.mapNotNull { it.avatarUrl }.orEmpty() else emptyList()))
            .filter(String::isNotBlank)
    }

    private const val MAX_SHARE_SHORTCUTS = 4
    private const val SHARE_TARGET_PHOTO_BUDGET_MS = 5_000L
    private const val WARM_BUDGET_MS = 15_000L

    private fun ensureConversationChannel(
        context: Context,
        channelId: String,
        channelName: String,
        parentChannelId: String,
        shortcutId: String,
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val parent = manager.getNotificationChannel(parentChannelId) ?: return false

        val existing = manager.getNotificationChannel(channelId)
        if (
            existing != null &&
            existing.parentChannelId == parentChannelId &&
            existing.conversationId == shortcutId
        ) {
            return true
        }

        val channel = NotificationChannel(
            channelId,
            channelName,
            SoundChannels.conversationImportance(context, shortcutId) ?: parent.importance,
        ).apply {
            description = "Messages from $channelName"
            setConversationId(parentChannelId, shortcutId)
            setShowBadge(parent.canShowBadge())
            enableLights(parent.shouldShowLights())
            lightColor = parent.lightColor
            enableVibration(parent.shouldVibrate())
            vibrationPattern = parent.vibrationPattern
            lockscreenVisibility = parent.lockscreenVisibility
            setBypassDnd(parent.canBypassDnd())
            setSound(parent.sound, parent.audioAttributes)
        }

        manager.createNotificationChannel(channel)

        val created = manager.getNotificationChannel(channelId)
        return created?.parentChannelId == parentChannelId &&
            created.conversationId == shortcutId
    }

    private fun loadLocalIcon(uriString: String): IconCompat? {
        if (uriString.isBlank()) return null

        return try {
            val uri = Uri.parse(uriString)
            val path = when (uri.scheme?.lowercase()) {
                "file" -> uri.path
                null, "" -> uriString
                else -> null
            } ?: return null

            val file = File(path)
            if (!file.exists() || !file.isFile) return null

            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
            IconCompat.createWithBitmap(bitmap)
        } catch (_: Throwable) {
            null
        }
    }
}

internal fun conversationStableId(value: String): String {
    val readable = value
        .lowercase()
        .replace(Regex("[^a-z0-9_-]"), "-")
        .trim('-')
        .take(32)

    if (readable.isNotEmpty()) return readable

    return MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(8)
        .joinToString("") { byte -> "%02x".format(byte) }
}