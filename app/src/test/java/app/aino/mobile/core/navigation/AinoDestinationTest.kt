package app.aino.mobile.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AinoDestinationTest {
    @Test
    fun destinationFor_matchesLabelsIgnoringCase() {
        assertEquals(AinoDestination.Dashboard, destinationFor("home"))
        assertEquals(AinoDestination.Calendar, destinationFor("CALENDAR"))
        assertEquals(AinoDestination.Dashboard, destinationFor("dashboard"))
    }

    @Test
    fun destinationFor_returnsNullForUnknownLabel() {
        assertNull(destinationFor("unknown"))
    }

    @Test
    fun chatThreadBuildsTypedRouteAndKeepsChatTabSelected() {
        assertEquals("chat/{conversationId}", AinoDestination.ChatThread.route)
        assertEquals("chat/42", chatThreadRoute(42))
        assertEquals("aino://chat/{conversationId}", CHAT_DEEP_LINK_PATTERN)
        assertEquals(AinoDestination.Chat.route, bottomBarRoute(AinoDestination.ChatThread.route))
        assertEquals(AinoDestination.Tasks.route, bottomBarRoute(AinoDestination.Tasks.route))
        assertThrows(IllegalArgumentException::class.java) { chatThreadRoute(0) }
    }

    @Test
    fun bottomBarIsHomeCalendarTasksChatMore() {
        // Web §2: the mobile tab bar is exactly Home·Calendar·Tasks·Chat·More.
        // Attendance is demoted out of the bar to the More sheet.
        assertEquals(
            listOf(
                AinoDestination.Dashboard,
                AinoDestination.Calendar,
                AinoDestination.Tasks,
                AinoDestination.Chat,
                AinoDestination.More,
            ),
            bottomDestinations,
        )
        assertFalse(AinoDestination.Attendance in bottomDestinations)
    }

    @Test
    fun moreDestinationsMatchWebOrderAndConditions() {
        // Web §2 order: Notes, Attendance, Organization, My Team, Admin, Tenants.
        val full = availableMoreDestinations(
            role = "hr_admin",
            hasReports = true,
            features = mapOf("notes" to true, "attendance" to true),
            orgId = 3,
        )
        assertEquals(
            listOf(
                AinoDestination.Notes,
                AinoDestination.Attendance,
                AinoDestination.Organization,
                AinoDestination.Manager,
                AinoDestination.Admin,
            ),
            full,
        )
        assertFalse(AinoDestination.Tenants in full)

        val employee = availableMoreDestinations("employee", hasReports = false)
        assertFalse(AinoDestination.Notes in employee)
        assertFalse(AinoDestination.Attendance in employee)
        assertFalse(AinoDestination.Manager in employee)
        assertFalse(AinoDestination.Admin in employee)
        assertFalse(AinoDestination.Tenants in employee)
        // Organization requires org_id or platform_admin.
        assertFalse(AinoDestination.Organization in employee)
        val withOrg = availableMoreDestinations("employee", hasReports = false, orgId = 7)
        assertTrue(AinoDestination.Organization in withOrg)

        val platformAdmin = availableMoreDestinations("platform_admin", hasReports = false)
        assertTrue(AinoDestination.Tenants in platformAdmin)
        assertTrue(AinoDestination.Admin in platformAdmin)
        assertTrue(AinoDestination.Organization in platformAdmin)
    }

    @Test
    fun bottomDestinations_failClosedByFeature() {
        val bare = visibleBottomDestinations(emptyMap())
        assertEquals(listOf(AinoDestination.Dashboard, AinoDestination.More), bare)

        val subscribed = visibleBottomDestinations(mapOf("calendar" to true, "tasks" to true, "chat" to false))
        assertEquals(
            listOf(AinoDestination.Dashboard, AinoDestination.Calendar, AinoDestination.Tasks, AinoDestination.More),
            subscribed,
        )
        assertEquals(bottomDestinations, visibleBottomDestinations(emptyMap(), ungatedPlatformAdmin = true))
    }

    @Test
    fun adminSubRoutesAreFullScreenAndDistinctFromTheAdminTab() {
        assertEquals("admin/agile", ADMIN_AGILE_ROUTE)
        assertEquals("admin/projects", ADMIN_PROJECTS_ROUTE)
        assertTrue(isFullScreenRoute(ADMIN_AGILE_ROUTE))
        assertTrue(isFullScreenRoute(ADMIN_PROJECTS_ROUTE))
        assertEquals(ADMIN_PROJECTS_ROUTE, bottomBarRoute(ADMIN_PROJECTS_ROUTE))
        assertNull(destinationFor(ADMIN_AGILE_ROUTE))
    }
}