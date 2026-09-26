package app.aino.mobile.feature.organization

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MySalaryTest {
    private fun repository(captured: MutableList<ApiRequest>, body: (ApiRequest) -> ByteArray) =
        OrganizationRepository(ApiClient { request -> captured += request; ApiResponse(200, emptyMap(), body(request)) })

    @Test
    fun salaryTabIsFirstAndDefaultOnlyWithPayroll() {
        assertEquals(listOf(OrgTab.Salary, OrgTab.Departments, OrgTab.Teams, OrgTab.Chart), visibleTabs("employee", payroll = true))
        assertEquals(listOf(OrgTab.Departments, OrgTab.Teams, OrgTab.Chart), visibleTabs("employee"))
        assertEquals(OrgTab.entries.toList(), visibleTabs("manager", payroll = true))
        assertEquals(OrgTab.Salary, defaultTab("hr_admin", payroll = true))
        assertEquals(OrgTab.Departments, defaultTab("hr_admin", payroll = false))
        assertTrue(salaryTabEnabled(mapOf("payroll" to true), ungatedPlatformAdmin = false))
        assertFalse(salaryTabEnabled(emptyMap(), ungatedPlatformAdmin = false))
        assertTrue(salaryTabEnabled(emptyMap(), ungatedPlatformAdmin = true))
    }

    @Test
    fun mySalaryLoadsSlipsAndNullBankAndPdfBytes() {
        val captured = mutableListOf<ApiRequest>()
        val pdf = byteArrayOf(1, 2, 3)
        val repo = repository(captured) { request ->
            when (request.path) {
                "compensation/my-slips" ->
                    """[{"id":4,"slip_month":"2026-09","gross_earnings":"52000.00","net_pay":"50000.00","disbursement_status":null}]""".toByteArray()
                "compensation/my-bank-details" -> "null".toByteArray()
                else -> pdf
            }
        }
        val data = repo.mySalary()
        assertEquals(50000.0, data.slips.single().netPay!!, 0.0)
        assertNull(data.bank)
        assertArrayEquals(pdf, repo.mySlipPdf(4))
        assertEquals(listOf("compensation/my-slips", "compensation/my-bank-details", "compensation/my-slips/4/pdf"), captured.map { it.path })
    }

    @Test
    fun saveMyBankPostsTheWebForm() {
        val captured = mutableListOf<ApiRequest>()
        repository(captured) { """{"message":"Bank details saved successfully"}""".toByteArray() }
            .saveMyBankDetails(MyBankForm("Ann Lee", "123456789", "SBIN0001234"))
        val request = captured.single()
        assertEquals("POST compensation/my-bank-details", "${request.method} ${request.path}")
        val body = request.body!!.toString(Charsets.UTF_8)
        assertTrue(body.contains("\"ifsc_code\":\"SBIN0001234\"") && body.contains("\"account_type\":\"savings\"") && body.contains("\"bank_name\":\"\""))
    }

    @Test
    fun formAndLabelHelpers() {
        val bank = MyBankDetails("Ann", "****6789", "SBIN0001234", null, "current", isVerified = true)
        val form = myBankFormFor(bank)
        assertEquals("", form.accountNumber) // the masked number is never prefilled
        assertEquals("current", form.accountType)
        assertFalse(form.complete)
        assertTrue(form.copy(accountNumber = "1").complete)
        assertEquals(MyBankForm(), myBankFormFor(null))
        assertEquals("Pending", mySlipPaymentLabel(null))
        assertEquals("Paid", mySlipPaymentLabel("processed"))
        assertEquals("failed", mySlipPaymentLabel("failed"))
        assertEquals("salary_slip_2026-09.pdf", mySlipFileName("2026-09"))
    }
}
