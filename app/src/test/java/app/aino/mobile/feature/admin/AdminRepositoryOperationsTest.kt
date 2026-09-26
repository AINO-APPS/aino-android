package app.aino.mobile.feature.admin

import app.aino.mobile.core.network.ApiRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminRepositoryOperationsTest {
    @Test
    fun roleRequestsAndPayPeriods() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AdminRepository(
            capturingClient(captured) { request ->
                when {
                    request.method == "GET" ->
                        """[{"id":5,"status":"pending","current_role":"employee","requested_role":"manager",
                        "approvals":{"hr_admin":{"status":"approved"},"super_admin":{"status":null}}}]"""
                    request.path == "admin/pay-periods" ->
                        """{"id":2,"label":"Sep","start_date":"2026-09-01","end_date":"2026-09-30"}"""
                    else -> """{"message":"ok","fully_approved":true}"""
                }
            },
        )

        val request = repository.roleRequests("pending").single()
        assertEquals("admin/role-requests?status=pending", captured[0].path)
        assertEquals(listOf("hr_admin" to "approved", "super_admin" to "pending"), request.chain)
        assertEquals("employee" to "manager", request.shownFrom to request.shownTo)

        assertEquals(true, repository.approveRoleRequest(5).fullyApproved)
        repository.rejectRoleRequest(5, "no")
        repository.cancelRoleRequest(5)
        repository.lockPayPeriod(" Sep ", "2026-09-01", "2026-09-30")
        repository.unlockPayPeriod(2)
        assertEquals(
            listOf(
                "POST admin/role-requests/5/approve", "POST admin/role-requests/5/reject",
                "POST admin/role-requests/5/cancel", "POST admin/pay-periods", "DELETE admin/pay-periods/2",
            ),
            captured.drop(1).calls(),
        )
        assertTrue(captured[4].text().contains("\"label\":\"Sep\""))
    }

    @Test
    fun announcementsUnwrapDataEnvelopeAndSendOnlyProvidedFields() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AdminRepository(
            capturingClient(captured) { request ->
                if (request.method == "GET") """{"data":[{"id":1,"message":"Hi","type":"warning"}]}"""
                else """{"data":{"id":1,"message":"Hi","is_active":false}}"""
            },
        )

        assertEquals("warning", repository.announcements().single().type)
        repository.createAnnouncement(" Hi ", "info", "")
        val updated = repository.updateAnnouncement(1, isActive = false)
        repository.deleteAnnouncement(1)

        assertFalse(captured[1].text().contains("duration"))
        assertEquals("""{"is_active":false}""", captured[2].text())
        assertFalse(updated.isActive)
        assertEquals("DELETE admin/announcements/1", listOf(captured[3]).calls().single())
    }

    @Test
    fun taskLabelsInviteCodesAndRegistration() {
        val captured = mutableListOf<ApiRequest>()
        val repository = AdminRepository(capturingClient(captured) { """{"id":1,"name":"Bug","color":"#f00","code":"ABC","mode":"invite_only"}""" })

        repository.createTaskLabel(" Bug ", "#f00")
        repository.updateTaskLabel(1, "Bug", "#0f0")
        repository.deleteTaskLabel(1)
        repository.createInviteCode("employee", 0, null)
        repository.deactivateInviteCode(1)
        repository.updateRegistrationMode("invite_only")

        assertEquals(
            listOf(
                "POST admin/task-labels", "PUT admin/task-labels/1", "DELETE admin/task-labels/1",
                "POST admin/invite-codes", "DELETE admin/invite-codes/1", "PUT admin/registration-settings",
            ),
            captured.calls(),
        )
        assertTrue(captured[0].text().contains("\"name\":\"Bug\""))
        assertTrue(captured[3].text().contains("\"expires_days\":null"))
        assertEquals("""{"mode":"invite_only"}""", captured[5].text())
    }
}
