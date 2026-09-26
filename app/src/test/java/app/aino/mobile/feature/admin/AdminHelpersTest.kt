package app.aino.mobile.feature.admin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminHelpersTest {
    @Test
    fun pasteDetectsDelimiterHeaderAndInfersUsername() {
        val parsed = parsePastedPeople(
            "full_name;email;role\nAnn Lee;Ann.Lee@x.io;manager\nBob;bad-email;\n;c@x.io;\n",
            defaultRole = "employee",
        )
        val row = parsed.rows.single()
        assertEquals("annlee", row.username)
        assertEquals("manager", row.role)
        assertNull(row.teamName)
        assertEquals(listOf(3 to "invalid email", 4 to "missing name"), parsed.errors.map { it.line to it.errors })
    }

    @Test
    fun pasteWithoutHeaderUsesDefaultColumnOrderAndDefaultRole() {
        val parsed = parsePastedPeople("Ann\tann@x.io\t\t\tEng\tCore", defaultRole = "hr_admin")
        val row = parsed.rows.single()
        assertEquals("ann", row.username)
        assertEquals("hr_admin", row.role)
        assertEquals("Eng" to "Core", row.departmentName to row.teamName)
        assertTrue(parsePastedPeople("  \n ").rows.isEmpty())
    }

    @Test
    fun savedViewsMatchWebDefinitions() {
        val u = AdminUser(id = 1, role = "employee", isActive = true, teamId = null, managerId = null)
        assertTrue(UserView.NoTeam.matches(u, emptySet()))
        assertTrue(UserView.NoManager.matches(u, emptySet()))
        assertFalse(UserView.NoManager.matches(u.copy(role = "platform_admin"), emptySet()))
        assertFalse(UserView.Inactive.matches(u, emptySet()))
        assertTrue(UserView.RolePending.matches(u, setOf(1L)))
        assertTrue(UserView.Admins.matches(u.copy(role = "hr_admin"), emptySet()))
        assertFalse(UserView.Admins.matches(u, emptySet()))
    }

    @Test
    fun auditRangeAndPresets() {
        assertEquals("0 of 0", auditRange(0, 0, 0))
        assertEquals("51–100 of 120", auditRange(50, 50, 120))
        assertEquals("", auditPresetFrom(null))
        assertEquals("2026-09-19", auditPresetFrom(7, java.time.LocalDate.of(2026, 9, 26)))
        assertEquals(2, AuditFilters(action = "x", to = "y").activeCount)
    }

    @Test
    fun toggleDayKeepsSortedIsoDays() {
        assertEquals("1,2,3,5", toggleDay("5,1, 3", 2))
        assertEquals("1,5", toggleDay("1,3,5", 3))
        assertEquals("7", toggleDay("", 7))
    }

    @Test
    fun roleRequestActionsFollowApprovalChain() {
        val approvals = Json.parseToJsonElement("""{"hr_admin":{"status":"approved"},"super_admin":{"status":"pending"}}""").jsonObject
        val r = RoleRequest(id = 1, approvals = approvals)
        assertTrue(canApproveRoleRequest(r, "super_admin"))
        assertFalse(canApproveRoleRequest(r, "hr_admin"))
        assertTrue(canApproveRoleRequest(r, "platform_admin"))
        assertTrue(canRejectRoleRequest(r, "hr_admin"))
        assertFalse(canRejectRoleRequest(r, "manager"))
    }

    @Test
    fun onlyPlatformAdminsMayAssignSuperAdmin() {
        assertTrue("super_admin" in assignableRoles("platform_admin"))
        assertFalse("super_admin" in assignableRoles("super_admin"))
        assertTrue("employee" in assignableRoles("hr_admin"))
    }

    @Test
    fun payPeriodValidation() {
        assertEquals("Label is required", validatePayPeriod(" ", "2026-09-01", "2026-09-30"))
        assertEquals("End date must be on or after start date", validatePayPeriod("Sep", "2026-09-30", "2026-09-01"))
        assertNull(validatePayPeriod("Sep", "2026-09-01", "2026-09-30"))
    }
}
