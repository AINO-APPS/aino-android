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
        assertEquals(true, AinoDestination.Leaves in employee)
        assertEquals(true, AinoDestination.Calendar in employee)
        assertEquals(true, AinoDestination.Profile in employee)
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
}
