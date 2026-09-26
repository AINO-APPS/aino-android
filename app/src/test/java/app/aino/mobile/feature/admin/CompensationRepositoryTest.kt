package app.aino.mobile.feature.admin

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompensationRepositoryTest {
    @Test
    fun decodesNumericStringsAndJsonbAmountMaps() {
        val repository = CompensationRepository(capturingClient(mutableListOf()) {
            """[{"id":7,"user_id":4,"effective_from":"2026-04-01","ctc_annual":"600000.00","base_salary":"50000.00",
               "components":{"basic":20000,"hra":"10000.00","_ded_pf":""},"full_name":"Ann Lee"}]"""
        })
        val emp = repository.employees().single()
        assertEquals(600000.0, emp.ctcAnnual!!, 0.0)
        assertEquals(50000.0, emp.baseSalary!!, 0.0)
        assertEquals(listOf("basic", "hra", "_ded_pf"), emp.componentAmounts.keys.toList())
        assertEquals(mapOf("basic" to 20000.0, "hra" to 10000.0, "_ded_pf" to 0.0), emp.componentAmounts)
    }

    @Test
    fun templatesAndAssignmentSendWebBodies() {
        val captured = mutableListOf<ApiRequest>()
        val repository = CompensationRepository(capturingClient(captured) { """{"id":3,"name":"Std","message":"Template deleted"}""" })

        repository.saveTemplate(TemplateForm(name = " Std ", components = listOf(CompComponent("basic", "Basic"))))
        repository.saveTemplate(TemplateForm(editingId = 3, name = "Std", isDefault = true))
        repository.deleteTemplate(3)
        repository.assign(
            AssignForm(isNew = true, userId = 9, effectiveFrom = "2026-05-01", baseSalary = "42000", components = mapOf("hra" to "8000", "_ded_tds" to "")),
        )

        assertEquals(
            listOf("POST compensation/templates", "PUT compensation/templates/3", "DELETE compensation/templates/3", "POST compensation/employees/9"),
            captured.calls(),
        )
        assertTrue(captured[0].text().contains("\"name\":\"Std\""))
        assertTrue(captured[0].text().contains("\"calc_type\":\"fixed\""))
        assertTrue(captured[1].text().contains("\"is_default\":true"))
        assertNull(captured[2].body)
        val assign = captured[3].text()
        assertTrue(assign.contains("\"ctc_annual\":0.0"))
        assertTrue(assign.contains("\"base_salary\":42000.0"))
        assertTrue(assign.contains("\"components\":{\"hra\":8000.0,\"_ded_tds\":0.0}"))
        assertTrue(assign.contains("\"template_id\":null"))
    }

    @Test
    fun slipsDisbursementsAndPdfHitTheirRoutes() {
        val captured = mutableListOf<ApiRequest>()
        val pdf = byteArrayOf(0x25, 0x50, 0x44, 0x46)
        val repository = CompensationRepository(ApiClient { request ->
            captured += request
            if (request.path.endsWith("/pdf")) return@ApiClient ApiResponse(200, emptyMap(), pdf)
            val body = when {
                request.path.startsWith("compensation/salary-slips?") ->
                    """[{"id":1,"slip_month":"2026-09","net_pay":"41000.50","status":"draft","days_worked":"21.00"}]"""
                request.path.startsWith("compensation/disbursements") -> """[{"id":5,"salary_slip_id":1,"status":"failed"}]"""
                request.path == "compensation/disburse" -> """{"message":"Disbursement initiated","disbursed":2,"failed":1,"total":3}"""
                else -> """{"id":1,"message":"ok","count":2,"payout_id":"pout_1"}"""
            }
            ApiResponse(200, emptyMap(), body.toByteArray())
        })

        val slip = repository.slips(12).single()
        assertEquals(41000.5, slip.netPay!!, 0.0)
        assertEquals(21.0, slip.daysWorked!!, 0.0)
        assertEquals("failed", repository.disbursements(12).single().status)
        assertEquals(2, repository.runPayroll(12).count)
        repository.publish(1)
        repository.bulkPublish(12)
        assertEquals(1, repository.disburse(12).failed)
        assertEquals("pout_1", repository.disburseOne(1).payoutId)
        repository.retry(5)
        repository.slip(1)
        assertArrayEquals(pdf, repository.slipPdf(1))

        assertEquals(
            listOf(
                "GET compensation/salary-slips?pay_period_id=12", "GET compensation/disbursements?pay_period_id=12",
                "POST compensation/payroll-run", "PUT compensation/salary-slips/1/publish", "POST compensation/salary-slips/bulk-publish",
                "POST compensation/disburse", "POST compensation/disburse/1", "POST compensation/disburse/retry/5",
                "GET compensation/salary-slips/1", "GET compensation/salary-slips/1/pdf",
            ),
            captured.calls(),
        )
        assertEquals("""{"pay_period_id":12}""", captured[2].text())
        assertEquals("application/pdf", captured.last().headers["Accept"])
    }
}
