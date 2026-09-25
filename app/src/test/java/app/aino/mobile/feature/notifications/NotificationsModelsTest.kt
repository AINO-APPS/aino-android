package app.aino.mobile.feature.notifications

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationsModelsTest {
    private val now = Instant.parse("2026-09-25T12:00:00Z").toEpochMilli()

    @Test
    fun timeAgoMatchesTheBellBuckets() {
        assertEquals("just now", notificationTimeAgo("2026-09-25T11:59:30.000Z", now))
        assertEquals("just now", notificationTimeAgo("2026-09-25T12:05:00Z", now)) // future clock skew
        assertEquals("1m ago", notificationTimeAgo("2026-09-25T11:59:00Z", now))
        assertEquals("59m ago", notificationTimeAgo("2026-09-25T11:00:01Z", now))
        assertEquals("1h ago", notificationTimeAgo("2026-09-25T11:00:00Z", now))
        assertEquals("23h ago", notificationTimeAgo("2026-09-24T12:00:01Z", now))
        assertEquals("1d ago", notificationTimeAgo("2026-09-24T12:00:00Z", now))
        assertEquals("10d ago", notificationTimeAgo("2026-09-15T12:00:00Z", now))
    }

    @Test
    fun zoneLessTimestampsAreUtcAndOffsetsAreHonoured() {
        assertEquals("5m ago", notificationTimeAgo("2026-09-25 11:55:00", now))
        assertEquals("5m ago", notificationTimeAgo("2026-09-25T17:25:00+05:30", now))
        assertEquals("", notificationTimeAgo(null, now))
        assertEquals("", notificationTimeAgo("garbage", now))
    }

    @Test
    fun linksMirrorHandleClick() {
        assertEquals("/tasks?task=44", notificationLink(NotificationItem(1, type = "task", linkTaskId = 44)))
        assertEquals("/tasks?task=44", notificationLink(NotificationItem(1, type = "meeting_invite", linkTaskId = 44)))
        assertEquals("/calendar", notificationLink(NotificationItem(1, type = "meeting_invite")))
        assertNull(notificationLink(NotificationItem(1, type = "leave")))
        assertNull(notificationLink(NotificationItem(1, type = "mention", linkTaskId = 0)))
    }

    @Test
    fun iconsAndBadge() {
        assertEquals(NotificationIcon.Mention, notificationIcon("mention"))
        assertEquals(NotificationIcon.Leave, notificationIcon("leave"))
        assertEquals(NotificationIcon.Task, notificationIcon("task"))
        assertEquals(NotificationIcon.Approval, notificationIcon("approval"))
        assertEquals(NotificationIcon.MeetingInvite, notificationIcon("meeting_invite"))
        assertEquals(NotificationIcon.Default, notificationIcon("leave_update"))
        assertEquals(NotificationIcon.Default, notificationIcon(null))
        assertNull(unreadBadgeLabel(0))
        assertEquals("7", unreadBadgeLabel(7))
        assertEquals("99", unreadBadgeLabel(99))
        assertEquals("99+", unreadBadgeLabel(100))
    }
}
