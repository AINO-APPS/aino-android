package app.aino.mobile.core.settings

import android.app.Application
import android.content.Context
import app.aino.mobile.core.call.webrtc.BOTTOM_TIER_INDEX
import app.aino.mobile.core.call.webrtc.LOW_DATA_TIER_INDEX
import app.aino.mobile.core.call.webrtc.buildTiers
import app.aino.mobile.core.call.webrtc.cappedTier
import app.aino.mobile.core.notifications.NotificationSoundPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class ChatPreferencesTest {
    private lateinit var context: Context

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        ChatPreferences.resetForTest()
        context.getSharedPreferences("aino_chat_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("aino_notification_sounds", Context.MODE_PRIVATE).edit().clear().commit()
        AppLock.reset()
    }

    @After fun cleanup() {
        ChatPreferences.clear(context)
        AppLock.reset()
    }

    @Test fun defaultsMatchSignal() {
        val prefs = ChatPreferences.get(context)
        assertFalse(prefs.inChatSounds)
        assertTrue(prefs.readReceipts)
        assertTrue(prefs.typingIndicators)
        assertTrue(prefs.linkPreviews)
        assertFalse(prefs.highQualityMedia)
        assertEquals(NotificationContent.NameAndMessage, prefs.notificationContent)
        assertTrue(prefs.autoDownload(AutoDownloadKind.Videos, NetworkKind.Wifi))
        assertTrue(prefs.autoDownload(AutoDownloadKind.Photos, NetworkKind.Mobile))
        assertFalse(prefs.autoDownload(AutoDownloadKind.Videos, NetworkKind.Mobile))
        assertFalse(prefs.autoDownload(AutoDownloadKind.Photos, NetworkKind.Roaming))
    }

    @Test fun updatesPersistAcrossProcessRestarts() {
        ChatPreferences.update(context) { it.copy(inChatSounds = true, screenLock = true, autoDownloadRoaming = setOf(AutoDownloadKind.Photos)) }
        ChatPreferences.resetForTest()
        val reloaded = ChatPreferences.get(context)
        assertTrue(reloaded.inChatSounds)
        assertTrue(reloaded.screenLock)
        assertEquals(setOf(AutoDownloadKind.Photos), reloaded.autoDownloadRoaming)
    }

    @Test fun migratesMuteAndSendSoundFromTheOldSoundPrefs() {
        context.getSharedPreferences("aino_notification_sounds", Context.MODE_PRIVATE).edit()
            .putBoolean("muteAll", true).putBoolean("playOnSend", true).commit()
        val prefs = ChatPreferences.get(context)
        assertTrue(prefs.muteAll)
        assertTrue(prefs.playOnSend)
    }

    @Test fun clearForgetsThePreviousUser() {
        ChatPreferences.update(context) { it.copy(screenSecurity = true) }
        NotificationSoundPrefs.clear(context)
        assertFalse(ChatPreferences.get(context).screenSecurity)
    }

    @Test fun inThreadToneNeedsTheSwitchANormalRingerAndAGap() {
        val allowed = { inChat: Boolean, mute: Boolean, ringer: Boolean, now: Long, last: Long ->
            NotificationSoundPrefs.inThreadToneAllowed(inChat, mute, ringer, now, last)
        }
        assertFalse(allowed(false, false, true, 10_000, 0))
        assertTrue(allowed(true, false, true, 10_000, 0))
        assertFalse(allowed(true, true, true, 10_000, 0))
        assertFalse(allowed(true, false, false, 10_000, 0))
        assertFalse(allowed(true, false, true, 10_000, 9_000))
        assertTrue(allowed(true, false, true, 10_000, 8_000))
    }

    @Test fun sendToneHonoursTheToggleMuteAndRinger() {
        assertTrue(NotificationSoundPrefs.sendToneAllowed(playOnSend = true, muteAll = false, ringerNormal = true))
        assertFalse(NotificationSoundPrefs.sendToneAllowed(playOnSend = false, muteAll = false, ringerNormal = true))
        assertFalse(NotificationSoundPrefs.sendToneAllowed(playOnSend = true, muteAll = true, ringerNormal = true))
        assertFalse(NotificationSoundPrefs.sendToneAllowed(playOnSend = true, muteAll = false, ringerNormal = false))
    }

    @Test fun lowDataCallsCapsVideoOnTheChosenNetworks() {
        assertFalse(ChatPrefs().lowDataFor(NetworkKind.Mobile))
        assertTrue(ChatPrefs(lowDataCalls = LowDataCalls.MobileOnly).lowDataFor(NetworkKind.Mobile))
        assertFalse(ChatPrefs(lowDataCalls = LowDataCalls.MobileOnly).lowDataFor(NetworkKind.Wifi))
        assertTrue(ChatPrefs(lowDataCalls = LowDataCalls.Always).lowDataFor(NetworkKind.Wifi))
        val tiers = buildTiers()
        assertEquals(tiers[LOW_DATA_TIER_INDEX], cappedTier(0, LOW_DATA_TIER_INDEX))
        assertEquals(tiers[BOTTOM_TIER_INDEX], cappedTier(BOTTOM_TIER_INDEX, LOW_DATA_TIER_INDEX))
        assertEquals(tiers[0], cappedTier(0, 0))
    }

    @Test fun screenLockLocksAfterTheTimeoutButNeverDuringACall() {
        assertTrue(AppLock.shouldLock(true, 60_000, backgroundedAtMs = 0, nowMs = 60_000, inCall = false))
        assertFalse(AppLock.shouldLock(true, 60_000, backgroundedAtMs = 0, nowMs = 59_000, inCall = false))
        assertFalse(AppLock.shouldLock(true, 0, backgroundedAtMs = 0, nowMs = 1, inCall = true))
        assertFalse(AppLock.shouldLock(false, 0, backgroundedAtMs = null, nowMs = 1, inCall = false))
        assertTrue(AppLock.shouldLock(true, 3_600_000, backgroundedAtMs = null, nowMs = 1, inCall = false))
    }

    @Test fun appLockFollowsTheLifecycle() {
        val prefs = ChatPrefs(screenLock = true, screenLockTimeoutMs = 60_000)
        AppLock.onForeground(prefs, inCall = false, signedIn = true, nowMs = 0)
        AppLock.unlock()
        AppLock.onBackground(nowMs = 0)
        AppLock.onForeground(prefs, inCall = true, signedIn = true, nowMs = 100_000)
        assertFalse(AppLock.locked.value)
        AppLock.onBackground(nowMs = 100_000)
        AppLock.onForeground(prefs, inCall = false, signedIn = true, nowMs = 170_000)
        assertTrue(AppLock.locked.value)
        AppLock.unlock()
        AppLock.onBackground(nowMs = 1_000)
        AppLock.onForeground(prefs, inCall = false, signedIn = true, nowMs = 30_000)
        assertFalse(AppLock.locked.value)
        AppLock.onBackground(nowMs = 40_000)
        AppLock.onForeground(prefs, inCall = false, signedIn = true, nowMs = 200_000)
        assertTrue(AppLock.locked.value)
    }
}
