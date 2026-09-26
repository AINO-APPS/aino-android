package app.aino.mobile.feature.admin

import app.aino.mobile.core.common.LenientDoubleNullableSerializer
import app.aino.mobile.core.common.asLenientDouble
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/*
 * `server/routes/compensation.ts` (P10.3). Every route is bare JSON behind
 * `requireFeature("payroll")`. NUMERIC columns arrive as strings ("50000.00"),
 * so amounts use the lenient serializers; JSONB amount maps may hold numbers,
 * numeric strings or "" and are read through [amounts].
 */

/** One `compensation_templates.components` entry. */
@Serializable
data class CompComponent(
    val key: String = "",
    val label: String = "",
    val type: String = "earning",
    @SerialName("calc_type") val calcType: String = "fixed",
    val taxable: Boolean = false,
)

@Serializable
data class CompensationTemplate(
    val id: Long,
    val name: String = "",
    val description: String? = null,
    val components: List<CompComponent>? = null,
    @SerialName("is_default") val isDefault: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
)

/** `employee_compensation` row (+ user columns on the list, `template_name` on the history). */
@Serializable
data class EmployeeCompensation(
    val id: Long,
    @SerialName("user_id") val userId: Long = 0,
    @SerialName("template_id") val templateId: Long? = null,
    @SerialName("effective_from") val effectiveFrom: String = "",
    @SerialName("effective_to") val effectiveTo: String? = null,
    @SerialName("ctc_annual") @Serializable(LenientDoubleNullableSerializer::class) val ctcAnnual: Double? = null,
    @SerialName("base_salary") @Serializable(LenientDoubleNullableSerializer::class) val baseSalary: Double? = null,
    val components: Map<String, JsonElement>? = null,
    val currency: String? = null,
    @SerialName("payment_frequency") val paymentFrequency: String? = null,
    @SerialName("bank_account") val bankAccount: String? = null,
    val notes: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val role: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("team_name") val teamName: String? = null,
    @SerialName("template_name") val templateName: String? = null,
) {
    val componentAmounts: Map<String, Double> get() = amounts(components)
}

@Serializable
data class SalarySlip(
    val id: Long,
    @SerialName("user_id") val userId: Long = 0,
    @SerialName("pay_period_id") val payPeriodId: Long? = null,
    @SerialName("slip_month") val slipMonth: String = "",
    val earnings: Map<String, JsonElement>? = null,
    val deductions: Map<String, JsonElement>? = null,
    @SerialName("gross_earnings") @Serializable(LenientDoubleNullableSerializer::class) val grossEarnings: Double? = null,
    @SerialName("total_deductions") @Serializable(LenientDoubleNullableSerializer::class) val totalDeductions: Double? = null,
    @SerialName("net_pay") @Serializable(LenientDoubleNullableSerializer::class) val netPay: Double? = null,
    @SerialName("days_worked") @Serializable(LenientDoubleNullableSerializer::class) val daysWorked: Double? = null,
    @SerialName("days_absent") @Serializable(LenientDoubleNullableSerializer::class) val daysAbsent: Double? = null,
    @SerialName("leave_days") @Serializable(LenientDoubleNullableSerializer::class) val leaveDays: Double? = null,
    @SerialName("overtime_hours") @Serializable(LenientDoubleNullableSerializer::class) val overtimeHours: Double? = null,
    val status: String = "draft",
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    val role: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
    @SerialName("team_name") val teamName: String? = null,
    @SerialName("disbursement_status") val disbursementStatus: String? = null,
    val utr: String? = null,
) {
    val earningAmounts: Map<String, Double> get() = amounts(earnings)
    val deductionAmounts: Map<String, Double> get() = amounts(deductions)
}

/** JSONB `{key: amount}`: numbers, numeric strings or "" (read as 0), insertion order kept. */
internal fun amounts(map: Map<String, JsonElement>?): Map<String, Double> =
    map.orEmpty().mapValues { (_, v) -> v.asLenientDouble() ?: 0.0 }
