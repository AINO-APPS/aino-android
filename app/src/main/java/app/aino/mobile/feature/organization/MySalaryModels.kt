package app.aino.mobile.feature.organization

import app.aino.mobile.core.common.LenientDoubleNullableSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Self-service half of `server/routes/compensation.ts` (P10.3): the
 * Organization page's "Salary Slips" tab (`pages/attendance/MySalarySlips.tsx`).
 * NUMERIC amounts arrive as strings, hence the lenient serializers.
 */

/** `GET /compensation/my-slips` row: own published slips + payout status. */
@Serializable
data class MySlip(
    val id: Long,
    @SerialName("slip_month") val slipMonth: String = "",
    @SerialName("gross_earnings") @Serializable(LenientDoubleNullableSerializer::class) val grossEarnings: Double? = null,
    @SerialName("total_deductions") @Serializable(LenientDoubleNullableSerializer::class) val totalDeductions: Double? = null,
    @SerialName("net_pay") @Serializable(LenientDoubleNullableSerializer::class) val netPay: Double? = null,
    @SerialName("disbursement_status") val disbursementStatus: String? = null,
    val utr: String? = null,
    @SerialName("paid_at") val paidAt: String? = null,
)

/** `GET /compensation/my-bank-details` (account number masked), or `null`. */
@Serializable
data class MyBankDetails(
    @SerialName("account_holder_name") val accountHolderName: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
    @SerialName("ifsc_code") val ifscCode: String? = null,
    @SerialName("bank_name") val bankName: String? = null,
    @SerialName("account_type") val accountType: String? = null,
    @SerialName("is_verified") val isVerified: Boolean = false,
)

data class MySalaryData(val slips: List<MySlip>, val bank: MyBankDetails?)

/** `{ message }` success bodies. */
@Serializable
data class MessageResult(val message: String? = null)

/** The bank form; the account number is never prefilled (the server only returns a mask). */
data class MyBankForm(
    val accountHolderName: String = "",
    val accountNumber: String = "",
    val ifscCode: String = "",
    val bankName: String = "",
    val accountType: String = "savings",
) {
    /** `handleSaveBank` required fields. */
    val complete: Boolean get() = accountHolderName.isNotEmpty() && accountNumber.isNotEmpty() && ifscCode.isNotEmpty()
}

fun myBankFormFor(b: MyBankDetails?) = MyBankForm(
    accountHolderName = b?.accountHolderName.orEmpty(),
    ifscCode = b?.ifscCode.orEmpty(),
    bankName = b?.bankName.orEmpty(),
    accountType = b?.accountType ?: "savings",
)

/** Payment column: "Paid" for processed, the raw status otherwise, "Pending" with no payout. */
fun mySlipPaymentLabel(status: String?): String = when {
    status.isNullOrEmpty() -> "Pending"
    status == "processed" -> "Paid"
    else -> status
}

/** `a.download = salary_slip_${month}.pdf`. */
fun mySlipFileName(month: String): String = "salary_slip_$month.pdf"

/** The tab shows only when the tenant has payroll (every compensation route is `requireFeature("payroll")`). */
fun salaryTabEnabled(features: Map<String, Boolean>, ungatedPlatformAdmin: Boolean): Boolean =
    ungatedPlatformAdmin || features["payroll"] == true
