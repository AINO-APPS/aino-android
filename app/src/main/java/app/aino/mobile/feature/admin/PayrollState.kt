package app.aino.mobile.feature.admin

import app.aino.mobile.core.common.formatIndianNumber

/** A query whose success may legitimately be "nothing saved" (`res.json(null)`). */
data class Saved<T>(val value: T?)

/** Web `/admin?tab=` keys of the payroll pages (`SECTIONS` "Operations", feature `payroll`). */
object PayrollSectionKeys {
    const val COMPENSATION = "compensation"
    const val SALARY_SLIPS = "salary-slips"
    const val PAYMENT_CONFIG = "payment-config"
    val ALL = setOf(COMPENSATION, SALARY_SLIPS, PAYMENT_CONFIG)
}

fun payrollSectionTitle(key: String): String = when (key) {
    PayrollSectionKeys.COMPENSATION -> "Compensation"
    PayrollSectionKeys.SALARY_SLIPS -> "Salary Slips"
    PayrollSectionKeys.PAYMENT_CONFIG -> "Payment Settings"
    else -> "Payroll"
}

/** Android-only employee page (history, record correction, bank details). */
data class EmployeePage(
    val userId: Long,
    val name: String,
    val history: Load<List<EmployeeCompensation>> = Load(),
    val bank: Load<Saved<BankDetails>> = Load(),
)

data class PayrollUiState(
    val role: String = "",
    val userId: Long = 0,
    val orgId: Long? = null,
    val compTab: CompTab = CompTab.Templates,
    val templates: Load<List<CompensationTemplate>> = Load(),
    val employees: Load<List<EmployeeCompensation>> = Load(),
    val members: Load<List<PayrollMember>> = Load(),
    val bankVerifications: Load<List<BankDetails>> = Load(),
    val bankAccounts: Load<List<BankDetails>> = Load(),
    val bankShowAll: Boolean = false,
    val ctc: Load<CtcConfig> = Load(),
    val periods: Load<List<PayPeriod>> = Load(),
    val selectedPeriodId: Long? = null,
    val slips: Load<List<SalarySlip>> = Load(),
    val disbursements: Load<List<Disbursement>> = Load(),
    val paymentConfig: Load<Saved<PaymentConfig>> = Load(),
    val employeePage: EmployeePage? = null,
    val slipPageId: Long? = null,
    val slipPage: Load<SalarySlip> = Load(),
    val notice: AdminNotice? = null,
    val busy: Boolean = false,
) {
    /** `periods.filter((p) => p.locked_by)`. */
    val lockedPeriods: List<PayPeriod> get() = periods.data.orEmpty().filter { it.lockedBy != null }
    val draftCount: Int get() = slips.data.orEmpty().count { it.status == "draft" }
    val publishedCount: Int get() = slips.data.orEmpty().count { it.status == "published" }
    val pendingBankCount: Int get() = bankVerifications.data.orEmpty().count { !it.isVerified }

    /** Web `disbursements.find((d) => d.salary_slip_id === slip.id)`. */
    fun disbursementFor(slipId: Long): Disbursement? = disbursements.data.orEmpty().firstOrNull { it.salarySlipId == slipId }

    /** Assign-modal picker: active members without an active compensation record. */
    val assignableMembers: List<PayrollMember>
        get() {
            val assigned = employees.data.orEmpty().map { it.userId }.toSet()
            return members.data.orEmpty().filter { it.isActive != false && it.id !in assigned }
        }
}

/** `CompensationSetup.tsx` CTC form: raw inputs, all required. */
data class CtcForm(
    val basicPct: String = "40",
    val hraPct: String = "50",
    val conveyancePct: String = "5",
    val pfPct: String = "12",
    val pfMax: String = "1800",
    val ptFixed: String = "200",
) {
    /** Null when a field is blank or not a number (the web's `required` inputs). */
    fun toConfig(): CtcConfig? {
        val values = listOf(basicPct, hraPct, conveyancePct, pfPct, pfMax, ptFixed).map { it.trim().toDoubleOrNull() ?: return null }
        return CtcConfig(values[0], values[1], values[2], values[3], values[4], values[5])
    }
}

fun ctcFormFor(c: CtcConfig) = CtcForm(
    plainAmount(c.basicPct), plainAmount(c.hraPct), plainAmount(c.conveyancePct),
    plainAmount(c.pfPct), plainAmount(c.pfMax), plainAmount(c.ptFixed),
)

/** Web download name: `salary_slip_${name.replace(/\s+/g, "_")}_${month}.pdf`. */
fun adminSlipFileName(fullName: String?, month: String): String =
    "salary_slip_${fullName.orEmpty().replace(Regex("\\s+"), "_")}_$month.pdf"

/** `PaymentSettings` test banner; `balance` is paise. */
fun paymentTestMessage(balancePaise: Double?): String =
    "Connection successful! Balance: \u20B9" + formatIndianNumber((balancePaise ?: 0.0) / 100)

/** `SalarySlips` Disburse All banner. */
fun disburseMessage(r: DisburseResult): String = "${r.message ?: "Disbursement initiated"} (${r.disbursed} sent, ${r.failed} failed)"

/** Payment cell label; the web shows only these three states (others render blank). */
fun disbursementLabel(status: String): String? = when (status) {
    "processed" -> "Paid"
    "processing" -> "Processing"
    "failed" -> "Failed"
    else -> null
}

val TRANSFER_MODES = listOf("NEFT" to "NEFT (1-2 hours)", "IMPS" to "IMPS (Instant, higher fee)", "UPI" to "UPI (Instant)")
val ACCOUNT_TYPES = listOf("savings" to "Savings", "current" to "Current")
