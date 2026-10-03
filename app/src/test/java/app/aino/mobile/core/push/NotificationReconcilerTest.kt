package app.aino.mobile.core.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationReconcilerTest {
    private fun chat(id: Long, at: Long = 100) = ActiveNotification(NotificationTags.CHAT, id.hashCode(), at)
    private fun alert(id: Long, at: Long = 100) = ActiveNotification(NotificationTags.ALERT, id.hashCode(), at)

    @Test
    fun readChatsAndAlertsAreStaleUnreadOnesStay() {
        val active = listOf(chat(4), chat(5), alert(3), alert(9))
        val truth = UnreadTruth(
            unreadConversationIds = setOf(5), chatSyncedAtMs = 200,
            unreadAlertIds = setOf(9), alertsSyncedAtMs = 200,
        )
        assertEquals(listOf(chat(4), alert(3)), staleNotifications(active, truth))
    }

    @Test
    fun aSixNotificationBacklogClearsOnceEverythingIsRead() {
        // The reported "6" on the Samsung icon: leftovers read on another device.
        val active = (1L..4L).map { chat(it) } + listOf(alert(10), alert(11))
        val truth = UnreadTruth(chatSyncedAtMs = 500, alertsSyncedAtMs = 500)
        assertEquals(6, staleNotifications(active, truth).size)
    }

    @Test
    fun notificationsPostedAfterTheFetchAreNeverRemoved() {
        val truth = UnreadTruth(chatSyncedAtMs = 100, alertsSyncedAtMs = 100)
        assertTrue(staleNotifications(listOf(chat(4, at = 150), alert(3, at = 101)), truth).isEmpty())
    }

    @Test
    fun aSourceThatWasNotFetchedYetIsLeftAlone() {
        val active = listOf(chat(4), alert(3))
        assertEquals(listOf(alert(3)), staleNotifications(active, UnreadTruth(alertsSyncedAtMs = 200)))
        assertEquals(listOf(chat(4)), staleNotifications(active, UnreadTruth(chatSyncedAtMs = 200)))
        assertTrue(staleNotifications(active, UnreadTruth()).isEmpty())
    }

    @Test
    fun alertsOlderThanTheLoadedPageAreUnknownAndKept() {
        val truth = UnreadTruth(alertWindowMinId = 50, alertsSyncedAtMs = 200)
        assertEquals(listOf(alert(60)), staleNotifications(listOf(alert(20), alert(60)), truth))
    }

    @Test
    fun callsAndOngoingNotificationsAreNeverTouched() {
        val truth = UnreadTruth(chatSyncedAtMs = 200, alertsSyncedAtMs = 200)
        val active = listOf(
            ActiveNotification(NotificationTags.CALL, 7, 100),
            ActiveNotification(null, 909090, 100),
            ActiveNotification(NotificationTags.CHAT, 4, 100, ongoing = true),
        )
        assertTrue(staleNotifications(active, truth).isEmpty())
    }

    @Test
    fun untaggedLegacyRowsGoOnlyWhenNeitherSourceStillHasTheId() {
        val legacy = ActiveNotification(null, 4, 100)
        val bothSynced = UnreadTruth(chatSyncedAtMs = 200, alertsSyncedAtMs = 200)
        assertEquals(listOf(legacy), staleNotifications(listOf(legacy), bothSynced))
        assertTrue(staleNotifications(listOf(legacy), bothSynced.copy(unreadAlertIds = setOf(4))).isEmpty())
        assertTrue(staleNotifications(listOf(legacy), bothSynced.copy(unreadConversationIds = setOf(4))).isEmpty())
        assertTrue(staleNotifications(listOf(legacy), UnreadTruth(chatSyncedAtMs = 200)).isEmpty())
    }

    @Test
    fun chatAndAlertTagsKeepEqualIdsApart() {
        val chatPush = ValidatedPush(PushKind.ChatMessage, mapOf("conversationId" to "4"), "msg:1", 1)
        val alertPush = ValidatedPush(PushKind.General, mapOf("notificationId" to "4"), "notif:4", 1)
        assertEquals(notificationId(chatPush), notificationId(alertPush))
        assertEquals(NotificationTags.CHAT, notificationTag(chatPush))
        assertEquals(NotificationTags.ALERT, notificationTag(alertPush))
    }

    @Test
    fun chatMentionsUseTheMentionsChannel() {
        assertEquals(PushNotifications.MENTIONS, generalPushChannel("chat_mention"))
    }
}
