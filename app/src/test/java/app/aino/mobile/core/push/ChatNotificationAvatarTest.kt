package app.aino.mobile.core.push

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.ConcurrentHashMap

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [28])
class ChatNotificationAvatarTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private val photo = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
    private val groupPhoto = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
    private val loads = ConcurrentHashMap<String, CompletableDeferred<Bitmap?>>()
    private val requested = ConcurrentHashMap.newKeySet<String>()
    private val cached = ConcurrentHashMap<String, Bitmap>()
    @Volatile private var account: String? = "1:2"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        ChatNotifications.clearAll()
        VisibleThread.clear()
        PushNotifications.createChannels(context)
        ChatNotifications.accountProvider = { account }
        ChatNotifications.groupAvatarProvider = { _, _, _ -> null }
        ChatNotifications.avatarSource = object : NotificationAvatarSource {
            override fun cached(context: Context, url: String, account: String?) = cached[url]
            override suspend fun load(context: Context, url: String, account: String?): Bitmap? {
                requested += url
                return withContext(NonCancellable) { loads.getValue(url).await() }
            }
        }
    }

    @After fun cleanup() {
        ChatNotifications.clearAll()
        loads.values.forEach { it.complete(null) }
        manager.cancelAll()
        VisibleThread.clear()
        ChatNotifications.avatarSource = NotificationAvatarLoader
        ChatNotifications.accountProvider = ::notificationAccount
        ChatNotifications.groupAvatarProvider = ::loadNotificationGroupAvatar
    }

    private fun data(id: Long = 1, avatar: String = "photo") = mapOf(
        "conversationId" to "7", "messageId" to id.toString(), "senderId" to "3", "senderName" to "Asha Kumar",
        "title" to "Asha Kumar", "body" to "Message $id", "senderAvatar" to avatar, "isGroup" to "false",
    )

    private fun shown(): Notification? = manager.activeNotifications
        .firstOrNull { it.tag == NotificationTags.CHAT && it.id == 7 }?.notification

    private fun assertPhoto(notification: Notification, color: Int) {
        val bitmap = org.robolectric.Shadows.shadowOf(notification.getLargeIcon()).bitmap
        assertEquals(color, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
    }

    @Test fun cachedPhotoAppearsInLargePersonAndShortcutIconsWithoutDownloading() {
        cached["photo"] = photo
        assertEquals(PostingOutcome.Posted, ChatNotifications.showMessage(context, data()))
        assertPhoto(shown()!!, Color.RED)
        val person = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown()!!)!!.messages.single().person!!
        assertEquals("aino-user-3", person.key)
        assertNotNull(person.icon)
        val shortcut = context.getSystemService(android.content.pm.ShortcutManager::class.java)
            .dynamicShortcuts.first { it.shortLabel == "Asha Kumar" }
        val shortcutIcon = android.content.pm.ShortcutInfo::class.java.getDeclaredMethod("getIcon").invoke(shortcut)
        assertNotNull(shortcutIcon)
        val icon = shortcutIcon as android.graphics.drawable.Icon
        assertEquals(androidx.core.graphics.drawable.IconCompat.TYPE_BITMAP, icon.type)
        val shortcutBitmap = org.robolectric.Shadows.shadowOf(icon).bitmap
        val personBitmap = org.robolectric.Shadows.shadowOf(person.icon!!.toIcon(context)).bitmap
        assertTrue(shortcutBitmap.sameAs(personBitmap))
        assertEquals(Color.TRANSPARENT, shortcutBitmap.getPixel(0, 0))
        assertEquals(Color.RED, shortcutBitmap.getPixel(shortcutBitmap.width / 2, 16))
        assertTrue(requested.isEmpty())
    }

    @Test fun slowPhotoDoesNotDelayPostingAndSilentUpdatePreservesMessageActionsAndTimestamp() {
        loads["photo"] = CompletableDeferred()
        assertEquals(PostingOutcome.Posted, ChatNotifications.showMessage(context, data()))
        val initial = shown()!!
        assertNotNull(initial.getLargeIcon())
        await { "photo" in requested }
        loads.getValue("photo").complete(photo)
        await { shown() !== initial }
        val updated = shown()!!
        assertPhoto(updated, Color.RED)
        assertEquals(initial.`when`, updated.`when`)
        assertTrue(updated.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNull(updated.sound)
        assertNull(updated.vibrate)
        assertEquals(listOf("Reply", "Mark as read"), updated.actions.map { it.title.toString() })
        assertEquals(1, ChatNotificationHistory.get(7).size)
    }

    @Test fun groupPhotoAndMemberPhotoKeepTheirSeparateIdentities() {
        cached["photo"] = photo
        cached["group"] = groupPhoto
        ChatNotifications.showMessage(context, data() + mapOf("isGroup" to "true", "groupName" to "Team", "groupAvatar" to "group"))
        assertPhoto(shown()!!, Color.BLUE)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown()!!)!!
        assertEquals("Team", style.conversationTitle)
        assertEquals("Asha Kumar", style.messages.single().person!!.name)
    }

    @Test fun groupGlyphPaletteAndSenderInitialsMatchChatWithItsConversationKey() {
        ChatNotifications.showMessage(context, data(avatar = "") + mapOf("isGroup" to "true", "groupName" to "Team",
            "groupAvatar" to "", "groupMemberPreviews" to "[]"))
        val bitmap = org.robolectric.Shadows.shadowOf(shown()!!.getLargeIcon()).bitmap
        assertEquals(app.aino.mobile.core.designsystem.component.groupAvatarColors("conv-7").first,
            bitmap.getPixel(bitmap.width / 8, bitmap.height / 2))
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown()!!)!!
        val personBitmap = org.robolectric.Shadows.shadowOf(style.messages.single().person!!.icon!!.toIcon(context)).bitmap
        assertTrue(personBitmap.sameAs(app.aino.mobile.core.avatar.ChatAvatarBitmaps.user("Asha Kumar", ChatNotifications.brandColor(context))))
    }

    @Test fun memberPhotosSilentlyReplaceCollageTilesAndShortcutWithoutChangingSenderIdentity() {
        cached["photo"] = photo
        loads["member"] = CompletableDeferred()
        val previews = """[{"name":"Asha Kumar","avatar":"photo"},{"name":"Bob","avatar":"member"}]"""
        ChatNotifications.showMessage(context, data() + mapOf("isGroup" to "true", "groupName" to "Team",
            "groupAvatar" to "", "groupMemberPreviews" to previews))
        val initial = shown()!!
        await { "member" in requested }
        loads.getValue("member").complete(groupPhoto)
        await { shown() !== initial }
        val bitmap = org.robolectric.Shadows.shadowOf(shown()!!.getLargeIcon()).bitmap
        assertEquals(Color.RED, bitmap.getPixel(bitmap.width / 4, bitmap.height / 2))
        assertEquals(Color.BLUE, bitmap.getPixel(bitmap.width * 3 / 4, bitmap.height / 2))
        assertEquals(Color.TRANSPARENT, bitmap.getPixel(0, 0))
        val shortcut = context.getSystemService(android.content.pm.ShortcutManager::class.java).dynamicShortcuts.first { it.shortLabel == "Team" }
        val shortcutIcon = android.content.pm.ShortcutInfo::class.java.getDeclaredMethod("getIcon").invoke(shortcut) as android.graphics.drawable.Icon
        assertEquals(android.graphics.drawable.Icon.TYPE_BITMAP, shortcutIcon.type)
        val shortcutBitmap = org.robolectric.Shadows.shadowOf(shortcutIcon).bitmap
        assertEquals(Color.RED, shortcutBitmap.getPixel(shortcutBitmap.width / 4, shortcutBitmap.height / 2))
        assertEquals(Color.BLUE, shortcutBitmap.getPixel(shortcutBitmap.width * 3 / 4, shortcutBitmap.height / 2))
        assertEquals(Color.TRANSPARENT, shortcutBitmap.getPixel(0, 0))
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown()!!)!!
        assertEquals("aino-user-3", style.messages.single().person!!.key)
        assertEquals(initial.`when`, shown()!!.`when`)
        assertTrue(shown()!!.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertEquals(listOf("Reply", "Mark as read"), shown()!!.actions.map { it.title.toString() })
    }

    @Test fun omittedOrLegacyGroupMetadataUsesAccountScopedProviderAfterImmediateGlyphPosting() {
        val pending = CompletableDeferred<app.aino.mobile.core.avatar.GroupAvatarMetadata?>()
        ChatNotifications.groupAvatarProvider = { _, id, user ->
            assertEquals(7L, id); assertEquals("1:2", user)
            withContext(NonCancellable) { pending.await() }
        }
        cached["group"] = groupPhoto
        ChatNotifications.showMessage(context, data(avatar = "") + mapOf("isGroup" to "true", "groupName" to "Team"))
        val initial = shown()!!
        pending.complete(app.aino.mobile.core.avatar.GroupAvatarMetadata("group", emptyList()))
        await { "group" in requested || shown() !== initial }
        // Metadata updates first; the shared memory cache should supply the group photo.
        await {
            val bitmap = org.robolectric.Shadows.shadowOf(shown()!!.getLargeIcon()).bitmap
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2) == Color.BLUE
        }
        assertEquals(1, ChatNotificationHistory.get(7).size)
        assertEquals(initial.`when`, shown()!!.`when`)
    }

    @Test fun lateGroupMetadataAfterDismissalDoesNotRecreateNotificationOrShortcut() {
        val pending = CompletableDeferred<app.aino.mobile.core.avatar.GroupAvatarMetadata?>()
        ChatNotifications.groupAvatarProvider = { _, _, _ -> withContext(NonCancellable) { pending.await() } }
        ChatNotifications.showMessage(context, data(avatar = "") + mapOf("isGroup" to "true", "groupName" to "Team"))
        ChatNotifications.cancel(context, 7)
        pending.complete(app.aino.mobile.core.avatar.GroupAvatarMetadata("group", emptyList()))
        Thread.sleep(100)
        assertNull(shown())
    }

    @Test fun failedOrMissingPhotoKeepsFallbackAndMessage() {
        loads["photo"] = CompletableDeferred()
        ChatNotifications.showMessage(context, data())
        val initial = shown()
        await { "photo" in requested }
        loads.getValue("photo").complete(null)
        Thread.sleep(100)
        assertSame(initial, shown())
        ChatNotifications.showMessage(context, data(2, ""))
        assertEquals(2, ChatNotificationHistory.get(7).size)
    }

    @Test fun photoCompletionUsesNewestStackWithoutRestoringOldSenderOrReplyText() {
        loads["old"] = CompletableDeferred()
        ChatNotifications.showMessage(context, data(1, "old"))
        await { "old" in requested }
        cached["new"] = groupPhoto
        ChatNotifications.showMessage(context, data(2, "new") + ("senderName" to "New Name"))
        ChatNotifications.appendReply(context, 7, "Reply", false)
        loads.getValue("old").complete(photo)
        Thread.sleep(100)
        assertPhoto(shown()!!, Color.BLUE)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shown()!!)!!
        assertEquals(listOf("Message 1", "Message 2", "Reply"), style.messages.map { it.text.toString() })
        val icon = style.messages.first().person!!.icon!!.toIcon(context)
        val bitmap = org.robolectric.Shadows.shadowOf(icon).bitmap
        assertEquals(Color.BLUE, bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
    }

    @Test fun latePhotoCannotUpdateNewNotificationAfterConversationWasDismissed() {
        loads["old"] = CompletableDeferred()
        ChatNotifications.showMessage(context, data(1, "old"))
        await { "old" in requested }
        ChatNotifications.cancel(context, 7)
        cached["new"] = groupPhoto
        ChatNotifications.showMessage(context, data(2, "new"))
        val replacement = shown()
        loads.getValue("old").complete(photo)
        Thread.sleep(100)
        assertSame(replacement, shown())
        assertPhoto(shown()!!, Color.BLUE)
        assertEquals(listOf(2L), ChatNotificationHistory.get(7).map { it.messageId })
    }

    @Test fun dismissalReadVisibilityLogoutAndAccountSwitchNeverResurrectNotification() {
        for (mode in listOf("dismiss", "cancel", "visible", "logout", "account")) {
            val url = mode
            loads[url] = CompletableDeferred()
            account = "1:2"
            VisibleThread.clear()
            ChatNotifications.showMessage(context, data(1, url))
            await { url in requested }
            when (mode) {
                "dismiss" -> manager.cancel(NotificationTags.CHAT, 7)
                "cancel" -> ChatNotifications.cancel(context, 7)
                "visible" -> { VisibleThread.set(7); manager.cancel(NotificationTags.CHAT, 7) }
                "logout" -> { account = null; NotificationReconciler.clearAll(context) }
                "account" -> { account = "2:2"; manager.cancel(NotificationTags.CHAT, 7) }
            }
            loads.getValue(url).complete(photo)
            Thread.sleep(100)
            assertNull("Late photo resurrected $mode", shown())
            ChatNotifications.clearAll()
        }
    }

    @Test fun sharedImageLoaderFetchesPhotoAndServesNotificationMemoryCache() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val bytes = java.io.ByteArrayOutputStream().apply {
                photo.compress(Bitmap.CompressFormat.PNG, 100, this)
            }.toByteArray()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "image/png")
                .setBody(okio.Buffer().write(bytes)))
            server.start()
            val url = server.url("/avatar.png").toString()
            val loaded = kotlinx.coroutines.runBlocking { NotificationAvatarLoader.load(context, url, account) }
            assertNotNull(loaded)
            assertEquals(Color.RED, loaded!!.getPixel(64, 64))
            assertNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.getHeader("Authorization"))
            assertNotNull(NotificationAvatarLoader.cached(context, url, account))
            assertNull(NotificationAvatarLoader.cached(context, url, "other-account"))
            val repeated = kotlinx.coroutines.runBlocking { NotificationAvatarLoader.load(context, url, account) }
            assertNotNull(repeated)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun cacheIdentityIsAccountScopedAndRelativeUrlsResolveToSameKey() {
        assertNotEquals(NotificationAvatarLoader.cacheKey("/uploads/a.png", "1:2"), NotificationAvatarLoader.cacheKey("/uploads/a.png", "2:2"))
        assertEquals(NotificationAvatarLoader.cacheKey("/uploads/a.png", "1:2"),
            NotificationAvatarLoader.cacheKey(app.aino.mobile.core.media.resolveServerMediaUrl("/uploads/a.png"), "1:2"))
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for avatar update")
            Thread.sleep(10)
        }
    }
}
