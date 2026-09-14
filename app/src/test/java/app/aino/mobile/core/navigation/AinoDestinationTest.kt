package app.aino.mobile.core.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AinoDestinationTest {
    @Test
    fun destinationFor_matchesLabelsIgnoringCase() {
        assertEquals(AinoDestination.Dashboard, destinationFor("home"))
        assertEquals(AinoDestination.Attendance, destinationFor("ATTENDANCE"))
        assertEquals(AinoDestination.Dashboard, destinationFor("dashboard"))
    }

    @Test
    fun destinationFor_returnsNullForUnknownLabel() {
        assertNull(destinationFor("unknown"))
    }

    @Test
    fun moreDestinations_alwaysIncludeTheEmployeeSurfaces() {
        val employee = availableMoreDestinations("employee", hasReports = false)
        assertEquals(false, AinoDestination.Calendar in employee)
        assertEquals(false, AinoDestination.Notes in employee)
        assertEquals(true, AinoDestination.Organization in employee)

        val subscribed = availableMoreDestinations(
            "employee",
            hasReports = false,
            features = mapOf("calendar" to true, "notes" to true),
        )
        assertEquals(true, AinoDestination.Calendar in subscribed)
        assertEquals(true, AinoDestination.Notes in subscribed)
    }

    @Test
    fun moreDestinations_failClosedByRole() {
        val employee = availableMoreDestinations("employee", hasReports = false)
        assertEquals(false, AinoDestination.Admin in employee)
        assertEquals(false, AinoDestination.Manager in employee)
        assertEquals(false, AinoDestination.Tenants in employee)

        val manager = availableMoreDestinations("employee", hasReports = true)
        assertEquals(true, AinoDestination.Manager in manager)

        val platformAdmin = availableMoreDestinations("platform_admin", hasReports = false)
        assertEquals(true, AinoDestination.Tenants in platformAdmin)
        assertEquals(false, AinoDestination.Admin in platformAdmin)
    }

    @Test
    fun bottomDestinations_failClosedByFeature() {
        val bare = visibleBottomDestinations(emptyMap())
        assertEquals(listOf(AinoDestination.Dashboard, AinoDestination.More), bare)

        val subscribed = visibleBottomDestinations(mapOf("attendance" to true, "tasks" to true, "chat" to false))
        assertEquals(
            listOf(AinoDestination.Dashboard, AinoDestination.Attendance, AinoDestination.Tasks, AinoDestination.More),
            subscribed,
        )
        assertEquals(bottomDestinations, visibleBottomDestinations(emptyMap(), ungatedPlatformAdmin = true))
    }
}
