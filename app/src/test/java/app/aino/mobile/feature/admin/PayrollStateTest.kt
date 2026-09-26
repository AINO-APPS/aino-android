package app.aino.mobile.feature.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PayrollStateTest {
    @Test
    fun ctcFormRequiresEveryNumber() {
        assertEquals(CtcConfig(), CtcForm().toConfig())
        assertNull(CtcForm(pfMax = "").toConfig())
        assertNull(CtcForm(hraPct = "x").toConfig())
        assertEquals("40", ctcFormFor(CtcConfig()).basicPct)
        assertEquals("12.5", ctcFormFor(CtcConfig(pfPct = 12.5)).pfPct)
    }

    @Test
    fun salarySlipsPageHelpers() {
        val state = PayrollUiState(
            periods = Load(listOf(PayPeriod(1, "Aug", lockedBy = 3), PayPeriod(2, "Sep"))),
            slips = Load(listOf(SalarySlip(1, status = "draft"), SalarySlip(2, status = "published"), SalarySlip(3, status = "published"))),
            disbursements = Load(listOf(Disbursement(9, salarySlipId = 2, status = "processed", utr = "UTR1"))),
            employees = Load(listOf(EmployeeCompensation(1, userId = 10))),
            members = Load(listOf(PayrollMember(10), PayrollMember(11), PayrollMember(12, isActive = false))),
            bankVerifications = Load(listOf(BankDetails(isVerified = false), BankDetails(isVerified = true))),
        )
        assertEquals(listOf(1L), state.lockedPeriods.map { it.id })
        assertEquals(1, state.draftCount)
        assertEquals(2, state.publishedCount)
        assertEquals(1, state.pendingBankCount)
        // Active members without an active record.
        assertEquals(listOf(11L), state.assignableMembers.map { it.id })
        assertEquals("Paid \u00B7 UTR: UTR1", paymentCell(state.disbursementFor(2)))
        assertEquals("\u2014", paymentCell(state.disbursementFor(1)))
        assertEquals("\u2014", paymentCell(Disbursement(1, status = "queued")))
    }

    @Test
    fun webCopyAndFileNames() {
        assertEquals("salary_slip_Ann_Lee_Kumar_2026-09.pdf", adminSlipFileName("Ann  Lee\tKumar", "2026-09"))
        assertEquals("Connection successful! Balance: \u20B912,345", paymentTestMessage(1234500.0))
        assertEquals("Disbursement initiated (2 sent, 1 failed)", disburseMessage(DisburseResult("Disbursement initiated", 2, 1, 3)))
        assertEquals("Salary Slips", payrollSectionTitle(PayrollSectionKeys.SALARY_SLIPS))
        assertEquals("Paid", disbursementLabel("processed"))
        assertNull(disbursementLabel("reversed"))
        assertEquals("50000", plainAmount(50000.0))
        assertEquals("", plainAmount(null))
    }
}
