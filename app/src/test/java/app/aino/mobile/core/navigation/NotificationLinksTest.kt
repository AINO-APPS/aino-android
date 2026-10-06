package app.aino.mobile.core.navigation

import app.aino.mobile.core.push.PushTap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationLinksTest {
    private fun tap(
        type: String,
        link: String? = null,
        taskId: Long? = null,
        conversationId: Long? = null,
        title: String? = null,
    ) = PushTap(type, "notif:1", conversationId, null, tappedAtMs = 0, link = link, taskId = taskId, notificationId = 1, title = title)

    @Test
    fun chatTapsOpenTheirThread() {
        assertEquals("chat/42", pushTapRoute(tap("chat_message", conversationId = 42)))
        // A chat payload without a conversation falls through to the list.
        assertEquals("notifications", pushTapRoute(tap("chat_message")))
        // A missed-call notification opens the conversation with the caller.
        assertEquals("chat/42", pushTapRoute(tap("missed_call", conversationId = 42)))
    }

    @Test
    fun theServerLinkWinsThenTheLinkedTask() {
        assertEquals("tasks/link?task=7&tab=&sprint_id=", pushTapRoute(tap("mention", link = "/tasks?task=7", taskId = 99)))
        assertEquals("notes/p1", pushTapRoute(tap("note_mention", link = "/notes?pageId=p1")))
        assertEquals("manager?tab=approvals&request=5", pushTapRoute(tap("approval", link = "/manager?tab=approvals&request=5")))
        assertEquals("attendance?tab=leaves", pushTapRoute(tap("leave", link = "/attendance#leaves")))
        assertEquals("attendance?tab=manual-entry", pushTapRoute(tap("approval", link = "/attendance#manual-entry")))
        assertEquals("tasks/link?task=99&tab=&sprint_id=", pushTapRoute(tap("task", taskId = 99)))
        // An unknown web path falls back to the task, then the type.
        assertEquals("tasks/link?task=3&tab=&sprint_id=", pushTapRoute(tap("task", link = "/admin?tab=users", taskId = 3)))
        assertEquals("manager?tab=approvals&request=", pushTapRoute(tap("approval", link = "/somewhere-new")))
    }

    @Test
    fun webOnlyAdminTargetsLandOnTheNotificationsList() {
        assertEquals("notifications", pushTapRoute(tap("agile_request", link = "/admin?tab=agile")))
        assertEquals("notifications", pushTapRoute(tap("agile_grant", link = "/admin?tab=agile")))
        assertEquals("notifications", pushTapRoute(tap("platform_access_request", link = "/admin?tab=platform-access")))
    }

    @Test
    fun serverDecisionLinksRoute() {
        // Multi-date leave: the queue, no request sheet.
        assertEquals("manager?tab=approvals&request=", pushTapRoute(tap("approval", link = "/manager?tab=approvals")))
        assertEquals("attendance?tab=manual-entry", pushTapRoute(tap("approval", link = "/attendance#manual-entry", title = "Overtime Rejected")))
    }

    @Test
    fun legacyAlertsFallBackByType() {
        assertEquals("manager?tab=approvals&request=", pushTapRoute(tap("approval", title = "New Leave Request")))
        assertEquals("attendance?tab=manual-entry", pushTapRoute(tap("approval", title = "Manual Entry Approved ✅")))
        assertEquals("attendance?tab=leaves", pushTapRoute(tap("leave", title = "Leave Approved ✅")))
        assertEquals("notifications", pushTapRoute(tap("mention")))
        assertEquals("notifications", pushTapRoute(tap("task")))
        assertEquals("notifications", pushTapRoute(tap("meeting_invite")))
    }

    @Test
    fun legacyLinksDistinguishApproverAndRequesterApprovals() {
        assertEquals("/manager?tab=approvals", legacyNotificationLink("approval", "Overtime Request"))
        assertEquals("/manager?tab=approvals", legacyNotificationLink("approval", "New Manual Entry Request"))
        assertEquals("/manager?tab=approvals", legacyNotificationLink("approval", "Leave Withdrawal Request"))
        assertEquals("/manager?tab=approvals", legacyNotificationLink("approval", null))
        assertEquals("/attendance#manual-entry", legacyNotificationLink("approval", "Manual Entry Rejected"))
        assertEquals("/attendance#manual-entry", legacyNotificationLink("approval", "Overtime Approved ✅"))
        assertEquals("/attendance#leaves", legacyNotificationLink("leave", "Leave Revoked"))
        assertNull(legacyNotificationLink("mention", "Mentioned"))
        assertNull(legacyNotificationLink(null, null))
    }
}
