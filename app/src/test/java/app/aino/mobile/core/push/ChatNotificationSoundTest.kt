package app.aino.mobile.core.push

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.Bitmap
import androidx.core.app.NotificationCompat
import app.aino.mobile.core.notifications.NotificationSoundPrefs
import app.aino.mobile.core.settings.ChatPreferences
import app.aino.mobile.core.settings.NotificationContent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Signal sound rules: silent in the open thread, audible everywhere else unless muted. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ChatNotificationSoundTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager
    private var tones = 0
    private val realToneOutput = NotificationSoundPrefs.inThreadToneOutput

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        ChatPreferences.resetForTest()
        ChatPreferences.clear(context)
        ChatNotifications.clearAll()
        VisibleThread.clear()
        PushNotifications.createChannels(context)
        ChatNotifications.accountProvider = { "1:2" }
        ChatNotifications.groupAvatarProvider = { _, _, _ -> null }
        ChatNotifications.avatarSource = object : NotificationAvatarSource {
            override fun cached(context: Context, url: String, account: String?): Bitmap? = null
            override suspend fun load(context: Context, url: String, account: String?): Bitmap? = null
        }
        NotificationSoundPrefs.resetToneThrottleForTest()
        NotificationSoundPrefs.inThreadToneOutput = { tones++; true }
        context.getSystemService(android.media.AudioManager::class.java).ringerMode = android.media.AudioManager.RINGER_MODE_NORMAL
    }

    @After fun cleanup() {
        ChatNotifications.clearAll()
        manager.cancelAll()
        VisibleThread.clear()
        ChatPreferences.clear(context)
        ChatNotifications.avatarSource = NotificationAvatarLoader
        ChatNotifications.accountProvider = ::notificationAccount
        ChatNotifications.groupAvatarProvider = ::loadNotificationGroupAvatar
        NotificationSoundPrefs.resetToneThrottleForTest()
        NotificationSoundPrefs.appVisible = false
        NotificationSoundPrefs.inThreadToneOutput = realToneOutput
    }

    private fun data(id: Long = 1) = mapOf(
        "conversationId" to "7", "messageId" to id.toString(), "senderId" to "3", "senderName" to "Asha Kumar",
        "title" to "Asha Kumar", "body" to "Secret plan $id", "senderAvatar" to "", "isGroup" to "false",
    )

    private fun shown(): Notification? = manager.activeNotifications
        .firstOrNull { it.tag == NotificationTags.CHAT && it.id == 7 }?.notification

    private fun silent(notification: Notification): Boolean =
        NotificationCompat.getGroupAlertBehavior(notification) == NotificationCompat.GROUP_ALERT_SUMMARY &&
            notification.group != null

    @Test fun openThreadNeverPostsAndPlaysNoToneByDefault() {
        VisibleThread.set(7)
        NotificationSoundPrefs.appVisible = true
        val outcome = ChatNotifications.showMessage(context, data())
        assertTrue(outcome is PostingOutcome.Suppressed)
        assertEquals(null, shown())
        assertEquals(0, tones)
    }

    @Test fun openThreadTonePlaysOnlyWithInChatSounds() {
        ChatPreferences.update(context) { it.copy(inChatSounds = true) }
        VisibleThread.set(7)
        ChatNotifications.showMessage(context, data())
        assertEquals(1, tones)
    }

    @Test fun leavingTheThreadAlertsAudiblyEvenWhileTheAppIsOpen() {
        VisibleThread.set(7)
        VisibleThread.clear(7)
        NotificationSoundPrefs.appVisible = true
        assertEquals(PostingOutcome.Posted, ChatNotifications.showMessage(context, data()))
        assertFalse(silent(shown()!!))
        assertTrue(NotificationSoundPrefs.notificationAudible(context))
    }

    @Test fun muteAllSilencesPostedAlerts() {
        ChatPreferences.update(context) { it.copy(muteAll = true) }
        assertFalse(NotificationSoundPrefs.notificationAudible(context))
        assertEquals(PostingOutcome.Posted, ChatNotifications.showMessage(context, data()))
        assertTrue(silent(shown()!!))
    }

    @Test fun showNameOnlyHidesTheMessageText() {
        ChatPreferences.update(context) { it.copy(notificationContent = NotificationContent.NameOnly) }
        ChatNotifications.showMessage(context, data())
        val notification = shown()!!
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertEquals("New message", style.messages.single().text.toString())
        assertEquals("Asha Kumar", style.messages.single().person!!.name.toString())
        assertNotEquals("Secret plan 1", notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
    }

    @Test fun showNothingHidesSenderAndText() {
        ChatPreferences.update(context) { it.copy(notificationContent = NotificationContent.Nothing) }
        ChatNotifications.showMessage(context, data())
        val notification = shown()!!
        assertEquals("AINO", notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)!!
        assertEquals("AINO", style.messages.single().person!!.name.toString())
    }
}
