package app.aino.mobile.feature.attendance

import app.aino.mobile.core.common.TrackerStatus
import app.aino.mobile.core.common.WorkModeRequestState
import app.aino.mobile.core.common.preferredWorkMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkModeLockTest {
    private val loggedOut = TrackerStatus(state = "logged_out", workMode = "office", lockedWorkMode = "office")

    @Test fun `status decodes the lock fields`() {
        val status = Json { ignoreUnknownKeys = true }.decodeFromString<TrackerStatus>(
            """{"state":"logged_out","workMode":"office","lockedWorkMode":"office",
               "workModeRequest":{"id":5,"status":"pending","workMode":"remote","rejectReason":null}}""",
        )
        assertEquals("office", status.lockedWorkMode)
        assertEquals(WorkModeRequestState(5, "pending", "remote", null), status.workModeRequest)
    }

    @Test fun `no lock before the first clock-in`() {
        val lock = workModeLock(TrackerStatus(), WorkMode.Remote)
        assertNull(lock.hint)
        assertFalse(lock.needsRequest)
    }

    @Test fun `same mode clocks in normally`() {
        val lock = workModeLock(loggedOut, WorkMode.Office)
        assertFalse(lock.needsRequest)
        assertEquals("Today: Office.", lock.hint)
    }

    @Test fun `another mode needs a request`() {
        val lock = workModeLock(loggedOut, WorkMode.Remote)
        assertTrue(lock.needsRequest)
        assertFalse(lock.pending)
    }

    @Test fun `pending request blocks a second request`() {
        val status = loggedOut.copy(workModeRequest = WorkModeRequestState(1, "pending", "remote"))
        val lock = workModeLock(status, WorkMode.Remote)
        assertTrue(lock.needsRequest)
        assertTrue(lock.pending)
    }

    @Test fun `approved request lets the other mode clock in and is preselected`() {
        val status = loggedOut.copy(workModeRequest = WorkModeRequestState(1, "approved", "remote"))
        assertFalse(workModeLock(status, WorkMode.Remote).needsRequest)
        assertEquals("remote", status.preferredWorkMode())
    }

    @Test fun `rejected request shows the reason and allows asking again`() {
        val status = loggedOut.copy(workModeRequest = WorkModeRequestState(1, "rejected", "remote", "Team offsite"))
        val lock = workModeLock(status, WorkMode.Remote)
        assertTrue(lock.needsRequest)
        assertTrue(lock.hint!!.endsWith("Team offsite"))
    }

    @Test fun `logged out preselects the locked mode, a running session keeps its own`() {
        assertEquals("office", loggedOut.copy(workMode = "remote").preferredWorkMode())
        assertEquals("remote", TrackerStatus(state = "on_floor", workMode = "remote", lockedWorkMode = "office").preferredWorkMode())
    }
}
