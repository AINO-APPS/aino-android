package app.aino.mobile.feature.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileLogicTest {
    @Test
    fun mirrorsTheServerProfileEditRules() {
        assertNull(validateProfileEdit("Vishnu V R", "vvronline"))
        assertEquals("Name and username are required", validateProfileEdit("  ", "vvronline"))
        assertEquals("Name and username are required", validateProfileEdit("Vishnu", "   "))
        assertEquals("Full name must be 100 characters or less", validateProfileEdit("x".repeat(101), "vvronline"))
        assertEquals("Username must be between 3 and 30 characters", validateProfileEdit("Vishnu", "vv"))
        assertEquals(
            "Username may only contain letters, numbers, dots, underscores and hyphens",
            validateProfileEdit("Vishnu", "vv online"),
        )
        assertNull(validateProfileEdit("Vishnu", "v.v_r-1"))
    }

    @Test
    fun mirrorsTheServerEmailRule() {
        assertNull(validateEmail("vishnu@aino.org.in"))
        assertEquals("Email is required", validateEmail("   "))
        assertEquals("Invalid email address", validateEmail("vishnu@aino"))
        assertEquals("Invalid email address", validateEmail("vishnu aino.org.in"))
    }

    @Test
    fun suppressesSearchBelowTheServerMinimum() {
        assertFalse(searchable("a"))
        assertFalse(searchable(" "))
        assertTrue(searchable("ai"))
        assertTrue(searchable("  release  "))
    }

    @Test
    fun truncatesTheTermToTheServerLimit() {
        assertEquals("release", normalizeSearchTerm("  release  "))
        assertEquals(100, normalizeSearchTerm("x".repeat(150)).length)
    }

    @Test
    fun stripsTsHeadlineMarkupFromSnippets() {
        assertEquals("Ship the release", plainSnippet("Ship the <b>release</b>"))
        assertEquals("", plainSnippet(null))
    }

    @Test
    fun labelsUnderscoredRolesReadably() {
        assertEquals("HR admin", roleLabel("hr_admin"))
        assertEquals("Super admin", roleLabel("super_admin"))
        assertEquals("Platform admin", roleLabel("platform_admin"))
        assertEquals("Team lead", roleLabel("team_lead"))
        assertEquals("Employee", roleLabel("employee"))
    }

    @Test
    fun searchResultsReportEmptinessAcrossEveryBucket() {
        assertTrue(SearchResults().isEmpty)
        val populated = SearchResults(logs = listOf(SearchLogHit(id = 1, action = "update")))
        assertFalse(populated.isEmpty)
        assertEquals(1, populated.total)
    }
}
