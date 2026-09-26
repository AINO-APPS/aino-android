package app.aino.mobile.feature.admin

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PayrollConfigRepositoryTest {
    @Test
    fun configAndBankRoutesTreatJsonNullAsNotSaved() {
        val captured = mutableListOf<ApiRequest>()
        val repository = CompensationRepository(capturingClient(captured) { request ->
            when (request.path) {
                "compensation/payment-config", "compensation/bank-details/4" -> if (request.method == "GET") "null" else """{"id":1,"message":"saved"}"""
                "compensation/ctc-config" -> if (request.method == "GET") """{"org_id":1,"basic_pct":"45.00","pf_max":"1800.00"}""" else """{"basic_pct":45,"hra_pct":50}"""
                "compensation/payment-config/test" -> """{"success":true,"balance":1234500}"""
                else -> if (request.method == "POST") """{"id":2,"message":"ok","account_number":"****3456"}"""
                else """[{"id":2,"user_id":4,"account_number":"****6789","is_verified":false}]"""
            }
        })

        assertNull(repository.paymentConfig())
        assertNull(repository.employeeBankDetails(4))
        val ctc = repository.ctcConfig()
        assertEquals(45.0, ctc.basicPct, 0.0)
        assertEquals(12.0, ctc.pfPct, 0.0)
        repository.saveCtcConfig(CtcConfig(basicPct = 45.0))
        repository.savePaymentConfig(PaymentConfigForm(apiKeyId = "rzp", apiKeySecret = "s", accountNumber = "123"))
        assertEquals(1234500.0, repository.testPaymentConfig().balance!!, 0.0)
        assertEquals("****6789", repository.bankVerifications().single().accountNumber)
        repository.orgBankDetails()
        repository.saveEmployeeBankDetails(4, BankForm("Ann", "123456", "SBIN0001234"))
        repository.verifyBankDetails(4)
        repository.approveBankDetails(4)
        repository.rejectBankDetails(4)

        val calls = captured.calls()
        assertTrue("PUT compensation/ctc-config" in calls && "PUT compensation/payment-config" in calls)
        assertTrue(
            listOf(
                "POST compensation/bank-details/4", "POST compensation/bank-details/4/verify",
                "POST compensation/bank-details/4/approve", "POST compensation/bank-details/4/reject",
            ).all { it in calls },
        )
        assertTrue("GET compensation/bank-details" in calls && "GET compensation/bank-verifications" in calls)
        val ctcBody = captured.first { it.method == "PUT" && it.path == "compensation/ctc-config" }.text()
        assertTrue(ctcBody.contains("\"basic_pct\":45.0") && ctcBody.contains("\"pt_fixed\":200.0"))
        val paymentBody = captured.first { it.method == "PUT" && it.path == "compensation/payment-config" }.text()
        assertTrue(paymentBody.contains("\"default_transfer_mode\":\"NEFT\"") && paymentBody.contains("\"is_active\":false"))
        assertTrue(captured.first { it.path == "compensation/bank-details/4" && it.method == "POST" }.text().contains("\"account_type\":\"savings\""))
    }

    @Test
    fun recordCorrectionSendsNullForBlankFieldsAndHistoryHitsTheUser() {
        val captured = mutableListOf<ApiRequest>()
        val repository = CompensationRepository(capturingClient(captured) { request ->
            if (request.method == "GET") """[{"id":7,"user_id":4,"template_name":"Std","effective_to":"2026-05-01"}]""" else """{"id":7}"""
        })
        assertEquals("Std", repository.history(4).single().templateName)
        repository.updateRecord(4, 7, RecordEdit(baseSalary = "51000", notes = " raise "))
        assertEquals(listOf("GET compensation/employees/4", "PUT compensation/employees/4/7"), captured.calls())
        val body = captured[1].text()
        assertTrue(body.contains("\"base_salary\":51000.0") && body.contains("\"notes\":\"raise\""))
        assertTrue(body.contains("\"ctc_annual\":null") && body.contains("\"currency\":null"))
    }

    @Test
    fun membersPageUntilTotalAndErrorsSurfaceServerMessage() {
        val captured = mutableListOf<ApiRequest>()
        val members = CompensationRepository(capturingClient(captured) { request ->
            if (request.path.endsWith("page=1")) """{"data":[{"id":1,"full_name":"A"}],"total":2}"""
            else """{"data":[{"id":2,"name":"B","is_active":false}],"total":2}"""
        }).members()
        assertEquals(listOf(1L, 2L), members.map { it.id })
        assertEquals("org/members?per_page=100&page=2", captured[1].path)

        val gated = "The Payroll & Compensation feature is not enabled for your subscription plan."
        val failing = CompensationRepository(ApiClient { r -> throw ApiError.Http(403, """{"error":"$gated","feature":"payroll"}""", r.method, r.path) })
        try {
            failing.templates()
            fail("expected failure")
        } catch (e: AdminFailure) {
            assertEquals(403, e.statusCode)
            assertEquals(gated, e.adminMessage("Failed to fetch templates"))
        }
    }
}
