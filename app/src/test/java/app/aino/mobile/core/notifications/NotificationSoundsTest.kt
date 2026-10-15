package app.aino.mobile.core.notifications

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import app.aino.mobile.core.push.PushNotifications
import app.aino.mobile.core.settings.ChatPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Sound slots shared with web/desktop, device overrides, and Signal-style versioned channels. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [30])
class NotificationSoundsTest {
    private lateinit var context: Context
    private lateinit var manager: NotificationManager

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
        ChatPreferences.resetForTest()
        ChatPreferences.clear(context)
        NotificationSounds.clear(context)
        SoundChannels.resetForTest()
    }

    @After fun cleanup() {
        ChatPreferences.clear(context)
        NotificationSounds.clear(context)
        SoundChannels.resetForTest()
    }

    private fun messagesChannel() = manager.getNotificationChannel(PushNotifications.channel(context, PushNotifications.MESSAGES))

    @Test fun everyDesktopToneIdHasABundledSoundAndDefaultsMatchDesktop() {
        val desktop = mapOf(
            SoundCategory.Ringtone to listOf("classic", "calm", "dynamic", "urgent", "boop", "marimba", "crystal", "vapor", "none"),
            SoundCategory.Outgoing to listOf("ringback", "pulse", "soft", "echo", "drift", "none"),
            SoundCategory.Message to listOf("ding", "pop", "chime", "knock", "subtle", "glassy", "ripple", "none"),
            SoundCategory.Mention to listOf("mention", "chime", "urgent", "spark", "none"),
            SoundCategory.Reaction to listOf("subtle", "pop", "click", "none"),
        )
        desktop.forEach { (category, ids) ->
            assertEquals(ids, category.presets.map { it.id })
            category.presets.forEach { preset ->
                val uri = NotificationSounds.presetUri(context, preset)
                if (preset.id == "none") assertNull(uri) else assertTrue(uri.toString().startsWith("android.resource://${context.packageName}/raw/"))
            }
        }
        assertEquals(listOf("classic", "ringback", "ding", "mention", "subtle"), SoundCategory.entries.map { it.defaultId })
    }

    @Test fun syncedToneIsUsedAndUnknownIdsFallBackToTheDefault() {
        assertEquals(SoundChoice.Preset("ding"), NotificationSounds.choice(context, SoundCategory.Message))
        ChatPreferences.update(context) { it.copy(messageTone = "glassy") }
        assertTrue(NotificationSounds.uri(context, SoundCategory.Message).toString().endsWith("/raw/tone_message_glassy"))
        ChatPreferences.update(context) { it.copy(messageTone = "from-a-newer-desktop") }
        assertEquals(SoundChoice.Preset("ding"), NotificationSounds.choice(context, SoundCategory.Message))
        ChatPreferences.update(context) { it.copy(mentionTone = "none") }
        assertNull(NotificationSounds.uri(context, SoundCategory.Mention))
    }

    @Test fun deviceChoicesOverrideTheSyncedToneUntilClearedAndLegacyRingtoneMigrates() {
        NotificationSounds.setOverride(context, SoundCategory.Message, SoundChoice.SystemDefault)
        assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, NotificationSounds.uri(context, SoundCategory.Message))
        assertEquals("System default", NotificationSounds.title(context, SoundCategory.Message))
        NotificationSounds.setOverride(context, SoundCategory.Message, null)
        assertEquals(SoundChoice.Preset("ding"), NotificationSounds.choice(context, SoundCategory.Message))

        val picked = Uri.parse("content://media/internal/audio/media/42")
        context.getSharedPreferences("aino_notification_sounds", Context.MODE_PRIVATE).edit().putString("ringtoneUri", picked.toString()).commit()
        assertEquals(SoundChoice.Device(picked), NotificationSounds.choice(context, SoundCategory.Ringtone))
        assertEquals(Settings.System.DEFAULT_RINGTONE_URI, NotificationSounds.uri(context, SoundCategory.Outgoing, SoundChoice.SystemDefault))
    }

    @Test fun pickerResultMapsToSilenceSystemDefaultOrDeviceSound() {
        val default = Settings.System.DEFAULT_NOTIFICATION_URI
        assertEquals(SoundChoice.Preset("none"), NotificationSettingsLinks.pickedChoice(null, default))
        assertEquals(SoundChoice.SystemDefault, NotificationSettingsLinks.pickedChoice(default, default))
        val device = Uri.parse("content://media/internal/audio/media/7")
        assertEquals(SoundChoice.Device(device), NotificationSettingsLinks.pickedChoice(device, default))
    }

    @Test fun legacyChannelMovesToTheChosenToneKeepingTheUsersChannelSettings() {
        manager.createNotificationChannel(NotificationChannel(PushNotifications.MESSAGES, "Messages", NotificationManager.IMPORTANCE_LOW).apply {
            enableVibration(false)
        })
        PushNotifications.createChannels(context)
        val moved = messagesChannel()
        assertEquals("aino_messages_s1", moved.id)
        assertTrue(moved.sound.toString().endsWith("/raw/tone_message_ding"))
        assertEquals(NotificationManager.IMPORTANCE_LOW, moved.importance)
        assertEquals(false, moved.shouldVibrate())
        assertNull(manager.getNotificationChannel(PushNotifications.MESSAGES))

        // Nothing changed: no new version.
        PushNotifications.createChannels(context)
        assertEquals("aino_messages_s1", messagesChannel().id)
    }

    @Test fun aNewToneCreatesTheNextVersionAndSilenceHasNoSound() {
        PushNotifications.createChannels(context)
        val first = messagesChannel().id
        ChatPreferences.update(context) { it.copy(messageTone = "pop") }
        PushNotifications.createChannels(context)
        val second = messagesChannel()
        assertTrue(second.id != first)
        assertTrue(second.sound.toString().endsWith("/raw/tone_message_pop"))
        assertNull(manager.getNotificationChannel(first))

        ChatPreferences.update(context) { it.copy(reactionTone = "none") }
        PushNotifications.createChannels(context)
        assertNull(manager.getNotificationChannel(PushNotifications.channel(context, PushNotifications.REACTIONS)).sound)
    }

    @Test fun conversationChannelsFollowTheirParentAndRememberASwitchedOffChat() {
        PushNotifications.createChannels(context)
        val result = ConversationNotifications.ensureConversation(context, mapOf(
            "conversationId" to "9", "title" to "Asha", "senderId" to "3", "senderName" to "Asha",
            "parentChannelId" to PushNotifications.channel(context, PushNotifications.MESSAGES),
        ))
        assertNotNull(result)
        val child = manager.getNotificationChannel(result!!.getValue("channelId"))
        child.importance = NotificationManager.IMPORTANCE_NONE
        manager.createNotificationChannel(child)

        ChatPreferences.update(context) { it.copy(messageTone = "chime") }
        PushNotifications.createChannels(context)
        assertNull(manager.getNotificationChannel(child.id))
        assertEquals(NotificationManager.IMPORTANCE_NONE, SoundChannels.conversationImportance(context, result.getValue("shortcutId")))

        val again = ConversationNotifications.ensureConversation(context, mapOf(
            "conversationId" to "9", "title" to "Asha", "senderId" to "3", "senderName" to "Asha",
            "parentChannelId" to PushNotifications.channel(context, PushNotifications.MESSAGES),
        ))!!
        val recreated = manager.getNotificationChannel(again.getValue("channelId"))
        assertEquals(messagesChannel().id, recreated.parentChannelId)
        assertEquals(NotificationManager.IMPORTANCE_NONE, recreated.importance)
    }

    @Test fun channelIdsAndMigrationDecisions() {
        assertEquals("aino_messages", SoundChannels.versionedId("aino_messages", 0))
        assertEquals("aino_messages_s3", SoundChannels.versionedId("aino_messages", 3))
        assertEquals("messages_v3_conversation_x", SoundChannels.conversationChannelId("aino_messages", "x"))
        assertEquals("aino_messages_s2_conversation_x", SoundChannels.conversationChannelId("aino_messages_s2", "x"))
        assertEquals("none", SoundChannels.soundKey(null))
        assertTrue(SoundChannels.needsMigration(null, "a", channelExists = true))
        assertTrue(!SoundChannels.needsMigration("a", "a", channelExists = true))
        assertTrue(!SoundChannels.needsMigration(null, "a", channelExists = false))
    }
}
