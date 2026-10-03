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
        assertNull(notificationLink(NotificationItem(1, type = "mention", linkTaskId = 0)))
        assertNull(notificationLink(NotificationItem(1, type = "agile_grant")))
    }

    @Test
    fun serverLinkWinsOverEveryFallback() {
        assertEquals("/notes?pageId=p9", notificationLink(NotificationItem(1, type = "note_mention", link = "/notes?pageId=p9")))
        assertEquals(
            "/manager?tab=approvals&request=3",
            notificationLink(NotificationItem(1, type = "approval", link = " /manager?tab=approvals&request=3 ", linkTaskId = 8)),
        )
        // Absolute / protocol-relative URLs are not app routes: fall back.
        assertEquals("/tasks?task=8", notificationLink(NotificationItem(1, type = "task", link = "https://x.test/tasks", linkTaskId = 8)))
        assertEquals("/attendance#leaves", notificationLink(NotificationItem(1, type = "leave", link = "//x.test")))
        assertEquals("/calendar", notificationLink(NotificationItem(1, type = "meeting_invite", link = "")))
    }

    @Test
    fun legacyRowsFallBackByTypeAndTitle() {
        assertEquals("/attendance#leaves", notificationLink(NotificationItem(1, type = "leave", title = "Leave Approved ✅")))
        assertEquals("/manager?tab=approvals", notificationLink(NotificationItem(1, type = "approval", title = "New Leave Request")))
        assertEquals("/attendance#manual-entry", notificationLink(NotificationItem(1, type = "approval", title = "Manual Entry Approved ✅")))
        assertEquals("/attendance#manual-entry", notificationLink(NotificationItem(1, type = "approval", title = "Overtime Rejected")))
    }

    @Test
    fun webOnlyAdminLinksFallBackInsteadOfDeadEnding() {
        // Agile / platform-access requests point at web-only Admin tabs: no Android route, so the tap stays on the list.
        assertNull(notificationLink(NotificationItem(1, type = "agile_request", link = "/admin?tab=agile")))
        assertNull(notificationLink(NotificationItem(1, type = "agile_grant", link = "/admin?tab=agile")))
        assertNull(notificationLink(NotificationItem(1, type = "platform_access_request", link = "/admin?tab=platform-access")))
        // An unroutable link still yields to the linked task / type fallback.
        assertEquals("/tasks?task=4", notificationLink(NotificationItem(1, type = "task", link = "/admin?tab=projects", linkTaskId = 4)))
        assertEquals("/manager?tab=approvals", notificationLink(NotificationItem(1, type = "approval", link = "/agile-settings")))
        assertEquals(NotificationIcon.AgileAccess, notificationIcon("platform_access_request"))
    }

    @Test
    fun serverDecisionLinksRoute() {
        // Multi-date leaves link to the queue without a request id.
        assertEquals("/manager?tab=approvals", notificationLink(NotificationItem(1, type = "approval", link = "/manager?tab=approvals")))
        assertEquals(
            "/attendance#manual-entry",
            notificationLink(NotificationItem(1, type = "approval", title = "Overtime Rejected", link = "/attendance#manual-entry")),
        )
    }

    @Test
    fun decodesTheLinkColumnAndToleratesItsAbsence() {
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val page = json.decodeFromString<NotificationsPage>(
            """{"notifications":[{"id":1,"type":"task","link":"/tasks?task=2","link_task_id":2},{"id":2,"type":"leave","link":null},{"id":3}],"unread":"1"}""",
        )
        assertEquals(listOf("/tasks?task=2", null, null), page.notifications.map { it.link })
    }

    @Test
    fun iconsAndBadge() {
        assertEquals(NotificationIcon.Mention, notificationIcon("mention"))
        assertEquals(NotificationIcon.Mention, notificationIcon("note_mention"))
        assertEquals(NotificationIcon.AgileAccess, notificationIcon("agile_request"))
        assertEquals(NotificationIcon.AgileAccess, notificationIcon("agile_grant"))
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
