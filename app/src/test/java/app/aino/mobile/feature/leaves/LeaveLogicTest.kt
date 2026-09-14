package app.aino.mobile.feature.leaves

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaveLogicTest {
    private val casual = LeavePolicy(leaveType = "casual", name = "Casual", halfDayAllowed = true, quarterDayAllowed = false)

    @Test
    fun expandsAnInclusiveRangeAndRejectsAnInvertedOne() {
        assertEquals(
            listOf("2026-09-14", "2026-09-15", "2026-09-16"),
            expandDateRange("2026-09-14", "2026-09-16"),
        )
        assertEquals(listOf("2026-09-14"), expandDateRange("2026-09-14", "2026-09-14"))
        assertTrue(expandDateRange("2026-09-16", "2026-09-14").isEmpty())
        assertTrue(expandDateRange("not-a-date", "2026-09-14").isEmpty())
    }

    @Test
    fun mirrorsTheServerApplicationRules() {
        assertNull(validateLeaveApplication("casual", listOf("2026-09-14"), "full", "", casual, true))
        assertEquals(
            "Leave type required",
            validateLeaveApplication("", listOf("2026-09-14"), "full", "", null, false),
        )
        assertEquals(
            "Date(s) required",
            validateLeaveApplication("casual", emptyList(), "full", "", casual, true),
        )
        assertEquals(
            "Cannot apply for more than 60 days at once",
            validateLeaveApplication("casual", List(61) { "2026-09-14" }, "full", "", casual, true),
        )
        assertEquals(
            "Reason must be 500 characters or less",
            validateLeaveApplication("casual", listOf("2026-09-14"), "full", "x".repeat(501), casual, true),
        )
    }

    @Test
    fun enforcesPolicyCoverageOnlyWhenTheOrgConfiguredPolicies() {
        // No policies at all: the server accepts any type, so the client must too.
        assertNull(validateLeaveApplication("sabbatical", listOf("2026-09-14"), "full", "", null, false))
        assertEquals(
            "'sabbatical' leave is not allowed by your organization's policy",
            validateLeaveApplication("sabbatical", listOf("2026-09-14"), "full", "", null, true),
        )
    }

    @Test
    fun gatesHalfAndQuarterDaysOnThePolicyFlags() {
        assertNull(validateLeaveApplication("casual", listOf("2026-09-14"), "half", "", casual, true))
        assertEquals(
            "Quarter-day leave is not allowed for this leave type",
            validateLeaveApplication("casual", listOf("2026-09-14"), "quarter", "", casual, true),
        )
        val strict = casual.copy(halfDayAllowed = false)
        assertEquals(
            "Half-day leave is not allowed for this leave type",
            validateLeaveApplication("casual", listOf("2026-09-14"), "half", "", strict, true),
        )
    }

    @Test
    fun offersOnlyTheSelfServiceActionTheServerAccepts() {
        assertEquals(LeaveAction.Cancel, availableLeaveAction(Leave(1, "2026-09-14", "casual", status = "pending")))
        assertEquals(LeaveAction.RequestWithdrawal, availableLeaveAction(Leave(2, "2026-09-14", "casual", status = "approved")))
        assertEquals(LeaveAction.None, availableLeaveAction(Leave(3, "2026-09-14", "casual", status = "rejected")))
        assertEquals(LeaveAction.None, availableLeaveAction(Leave(4, "2026-09-14", "casual", status = "withdraw_pending")))
        // Auto-booked public holidays are org-wide and cannot be withdrawn.
        assertEquals(
            LeaveAction.None,
            availableLeaveAction(Leave(5, "2026-09-14", "holiday", status = "approved", reason = "Public holiday: Onam")),
        )
    }

    @Test
    fun usesTheServerDurationWeightsForTotals() {
        assertEquals(1.0, durationDays("full"), 0.0)
        assertEquals(0.5, durationDays("half"), 0.0)
        assertEquals(0.25, durationDays("quarter"), 0.0)
        val leaves = listOf(
            Leave(1, "2026-09-14", "casual", duration = "full"),
            Leave(2, "2026-09-15", "casual", duration = "half"),
            Leave(3, "2026-09-16", "casual", duration = "quarter"),
        )
        assertEquals(1.75, totalLeaveDays(leaves), 0.0)
        assertEquals("1.75", formatDays(1.75))
        assertEquals("2", formatDays(2.0))
    }

    @Test
    fun balanceAvailableAddsCarryForwardAndNeverGoesNegative() {
        val balance = LeaveBalance(leaveType = "casual", quota = 12.0, used = 3.5, carriedForward = 2.0)
        assertEquals(10.5, balance.available, 0.0)
        assertEquals(0.0, balance.copy(used = 99.0).available, 0.0)
        assertEquals("Casual", balance.copy(policyName = "Casual").label())
        assertEquals("Sick leave", LeaveBalance(leaveType = "sick_leave").label())
    }

    @Test
    fun labelsTheWithdrawPendingStateReadably() {
        assertEquals("Withdrawal pending", leaveStatusLabel("withdraw_pending"))
        assertEquals("Approved", leaveStatusLabel("approved"))
    }
}
