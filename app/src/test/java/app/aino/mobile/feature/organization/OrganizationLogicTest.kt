package app.aino.mobile.feature.organization

import app.aino.mobile.core.common.roleLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrganizationLogicTest {
    private fun member(
        id: Long,
        name: String,
        role: String = "employee",
        managerId: Long? = null,
        dept: String? = null,
        team: String? = null,
        manager: String? = null,
        email: String? = null,
    ) = ChartMember(
        id = id, fullName = name, email = email, role = role, managerId = managerId,
        departmentName = dept, teamName = team, managerName = manager,
    )

    @Test
    fun roleGating() {
        listOf("hr_admin", "super_admin", "platform_admin").forEach {
            assertTrue(isOrgAdmin(it))
            assertFalse(canManageLabels(it))
            assertEquals(listOf(OrgTab.Departments, OrgTab.Teams, OrgTab.Chart), visibleTabs(it))
        }
        assertTrue(canManageLabels("manager"))
        assertEquals(OrgTab.entries.toList(), visibleTabs("manager"))
        assertFalse(isOrgAdmin("manager"))
        assertFalse(canManageLabels("team_lead"))
        assertEquals(listOf("My Department", "My Team", "Org Chart", "Task Labels"), OrgTab.entries.map { it.label })
    }

    @Test
    fun sprintConfigCell() {
        assertEquals("Not set", sprintConfigLabel(null, null))
        assertEquals("Not set", sprintConfigLabel(0, null))
        assertEquals("1 week", sprintConfigLabel(1, null))
        assertEquals("2 weeks (from 2026-01-05)", sprintConfigLabel(2, "2026-01-05"))
        assertEquals("Not set (from 2026-01-05)", sprintConfigLabel(null, "2026-01-05T00:00:00.000Z"))
        assertNull(normalizeDate(""))
    }

    @Test
    fun filterMatchesNameEmailRoleManagerDeptTeam() {
        val members = listOf(
            member(1, "Ann Lee", role = "manager", email = "ann@x.io"),
            member(2, "Bob", dept = "Engineering", manager = "Ann Lee"),
            member(3, "Cy", team = "Platform"),
        )
        assertEquals(members, filterChartMembers(members, "  "))
        assertEquals(listOf(1L), filterChartMembers(members, "MANAGER").map { it.id })
        assertEquals(listOf(1L, 2L), filterChartMembers(members, "ann").map { it.id })
        assertEquals(listOf(2L), filterChartMembers(members, "engin").map { it.id })
        assertEquals(listOf(3L), filterChartMembers(members, "plat").map { it.id })
        assertEquals(listOf(1L), filterChartMembers(members, "x.io").map { it.id })
    }

    @Test
    fun reportingTreeRootsChildrenAndSorting() {
        val members = listOf(
            member(1, "Zed"),
            member(2, "bea", managerId = 1),
            member(3, "Al", managerId = 1),
            member(4, "Orphan", managerId = 99),
            member(5, "Self", managerId = 5),
        )
        val tree = buildReportingTree(members)
        assertEquals(listOf("Orphan", "Self", "Zed"), tree.roots.map { it.name })
        assertEquals(listOf("Al", "bea"), tree.childrenOf(1).map { it.name })
        assertTrue(tree.childrenOf(2).isEmpty())
    }

    @Test
    fun labelsAndMeta() {
        val m = member(1, "Ann", role = "team_lead", dept = "Eng", team = "Core", manager = "Bo")
        assertEquals("Eng › Core", deptTeamLabel(m))
        assertNull(deptTeamLabel(member(2, "X")))
        assertEquals("Team Lead · Eng › Core · reports to Bo", treeMeta(m))
        assertEquals("Ann\nTeam Lead\nDepartment: Eng\nTeam: Core\nReports to: Bo", memberTooltip(m))
        assertEquals("custom_role", roleLabel("custom_role"))
        assertEquals("1 member", plural(1, "member"))
        assertEquals("0 direct reports", plural(0, "direct report"))
    }

    @Test
    fun highlightAndPickers() {
        assertEquals(listOf(0..1, 5..6), highlightRanges("Anna An", "an"))
        assertTrue(highlightRanges("Ann", "").isEmpty())
        assertTrue(isHexColor("#0ea5E9"))
        assertFalse(isHexColor("#0ea5e"))

        val options = memberOptions("No Head", listOf(OrgMember(1, "ann", "Ann"), OrgMember(2, "bob", null)))
        assertEquals(listOf<Pair<Long?, String>>(null to "No Head", 1L to "Ann", 2L to "bob"), options)
        assertEquals(null to "No department", departmentOptions(emptyList()).single())
    }
}
