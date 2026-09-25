package app.aino.mobile.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebLinkRouteTest {
    @Test
    fun mapsNotificationAndSearchLinks() {
        assertEquals("dashboard", webLinkToRoute("/"))
        assertEquals("calendar", webLinkToRoute("/calendar"))
        assertEquals("tasks/link?task=12&tab=&sprint_id=", webLinkToRoute("/tasks?task=12"))
        assertEquals("tasks/link?task=12&tab=&sprint_id=", webLinkToRoute("/tasks?taskId=12"))
        assertEquals("tasks/link?task=&tab=sprint&sprint_id=4", webLinkToRoute("/tasks?tab=sprint&sprint_id=4"))
        assertEquals("tasks", webLinkToRoute("/tasks"))
        assertEquals("sprint-insights", webLinkToRoute("/sprint-insights"))
        assertEquals("attendance?tab=leaves", webLinkToRoute("/attendance#leaves"))
        assertEquals("attendance", webLinkToRoute("/attendance"))
        assertEquals("attendance?tab=leaves", webLinkToRoute("/leaves"))
        assertEquals("chat/42", webLinkToRoute("/chat/42"))
        assertEquals("admin", webLinkToRoute("/admin?tab=users&userId=3"))
        assertEquals("meeting/abc-def", webLinkToRoute("/meeting/abc-def"))
        assertNull(webLinkToRoute("/unknown"))
    }

    @Test
    fun notesLinksUseTheEditorRouteWhenAPageIsGiven() {
        assertEquals("notes", webLinkToRoute("/notes"))
        assertEquals("notes/p1", webLinkToRoute("/notes?pageId=p1"))
        assertEquals("notes/page/p1", webLinkToRoute("/notes?pageId=p1") { "notes/page/$it" })
        assertTrue(isFullScreenRoute(NOTE_EDITOR_ROUTE))
        assertTrue(isFullScreenRoute(NOTE_HISTORY_ROUTE))
    }

    @Test
    fun adminSectionLinksOpenTheirFullScreenRoutes() {
        assertEquals(ADMIN_AGILE_ROUTE, webLinkToRoute("/admin?tab=agile"))
        assertEquals(ADMIN_AGILE_ROUTE, webLinkToRoute("/admin?tab=labels"))
        assertEquals(ADMIN_PROJECTS_ROUTE, webLinkToRoute("/admin?tab=projects"))
        assertEquals(ADMIN_AGILE_ROUTE, webLinkToRoute("/agile-settings"))
        assertEquals(ADMIN_PROJECTS_ROUTE, webLinkToRoute("/projects"))
        assertEquals("admin", webLinkToRoute("/admin"))
        assertEquals("admin", webLinkToRoute("/admin?tab=audit"))
        assertTrue(isFullScreenRoute(ADMIN_AGILE_ROUTE))
        assertTrue(isFullScreenRoute(ADMIN_PROJECTS_ROUTE))
        assertFalse(isFullScreenRoute(AinoDestination.Admin.route))
    }

    @Test
    fun profileMeetingAndSearchRoutesHideTheShellBars() {
        assertTrue(isFullScreenRoute(SEARCH_ROUTE))
        assertTrue(isFullScreenRoute(MEETING_ROUTE))
        assertTrue(isFullScreenRoute(AinoDestination.Notifications.route))
        assertFalse(isFullScreenRoute(AinoDestination.Calendar.route))
        assertFalse(isFullScreenRoute(null))
    }
}
