package app.aino.mobile.feature.admin

import app.aino.mobile.core.common.LenientDoubleNullableSerializer
import app.aino.mobile.core.common.LenientDoubleSerializer
import app.aino.mobile.core.common.LenientIntSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/* Disbursement, payment config, CTC config and bank-detail rows of `server/routes/compensation.ts`. */

/** `payroll_disbursements` row + `full_name`, `slip_month`. */
@Serializable
data class Disbursement(
    val id: Long,
    @SerialName("salary_slip_id") val salarySlipId: Long = 0,
    @Serializable(LenientDoubleNullableSerializer::class) val amount: Double? = null,
    val status: String = "",
    @SerialName("failure_reason") val failureReason: String? = null,
    val utr: String? = null,
    @SerialName("razorpay_payout_id") val razorpayPayoutId: String? = null,
)

/** `GET /payment-config`: secrets come back masked. */
@Serializable
data class PaymentConfig(
    @SerialName("api_key_id") val apiKeyId: String? = null,
    @SerialName("api_key_secret") val apiKeySecret: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
    @SerialName("webhook_secret") val webhookSecret: String? = null,
    @SerialName("default_transfer_mode") val defaultTransferMode: String? = null,
    @SerialName("is_active") val isActive: Boolean = false,
)

/** `org_ctc_config` (the route returns defaults when the org has no row). */
@Serializable
data class CtcConfig(
    @SerialName("basic_pct") @Serializable(LenientDoubleSerializer::class) val basicPct: Double = 40.0,
    @SerialName("hra_pct") @Serializable(LenientDoubleSerializer::class) val hraPct: Double = 50.0,
    @SerialName("conveyance_pct") @Serializable(LenientDoubleSerializer::class) val conveyancePct: Double = 5.0,
    @SerialName("pf_pct") @Serializable(LenientDoubleSerializer::class) val pfPct: Double = 12.0,
    @SerialName("pf_max") @Serializable(LenientDoubleSerializer::class) val pfMax: Double = 1800.0,
    @SerialName("pt_fixed") @Serializable(LenientDoubleSerializer::class) val ptFixed: Double = 200.0,
)

/** `employee_bank_details` row, account number masked (`****1234`). */
@Serializable
data class BankDetails(
    val id: Long = 0,
    @SerialName("user_id") val userId: Long = 0,
    @SerialName("account_holder_name") val accountHolderName: String? = null,
    @SerialName("account_number") val accountNumber: String? = null,
    @SerialName("ifsc_code") val ifscCode: String? = null,
    @SerialName("bank_name") val bankName: String? = null,
    @SerialName("account_type") val accountType: String? = null,
    @SerialName("is_verified") val isVerified: Boolean = false,
    @SerialName("verified_at") val verifiedAt: String? = null,
    @SerialName("razorpay_fund_account_id") val razorpayFundAccountId: String? = null,
    @SerialName("full_name") val fullName: String? = null,
    val email: String? = null,
    @SerialName("department_name") val departmentName: String? = null,
)

/** `org/members` row for the Assign employee picker. */
@Serializable
data class PayrollMember(
    val id: Long,
    @SerialName("full_name") val fullName: String? = null,
    val name: String? = null,
    val email: String? = null,
    @SerialName("is_active") val isActive: Boolean? = null,
)

@Serializable
data class PayrollMemberPage(val data: List<PayrollMember> = emptyList(), @Serializable(LenientIntSerializer::class) val total: Int = 0)

/** `payroll-run` / `bulk-publish`. */
@Serializable
data class CountResult(val message: String? = null, @Serializable(LenientIntSerializer::class) val count: Int = 0)

@Serializable
data class DisburseResult(
    val message: String? = null,
    @Serializable(LenientIntSerializer::class) val disbursed: Int = 0,
    @Serializable(LenientIntSerializer::class) val failed: Int = 0,
    @Serializable(LenientIntSerializer::class) val total: Int = 0,
)

@Serializable
data class PayoutResult(val message: String? = null, @SerialName("payout_id") val payoutId: String? = null)

/** `payment-config/test`: `balance` is in paise. */
@Serializable
data class PaymentTestResult(val success: Boolean = false, @Serializable(LenientDoubleNullableSerializer::class) val balance: Double? = null)

/** A bank-detail form (`MySalarySlips` bank form, Android-only admin form). */
data class BankForm(
    val accountHolderName: String = "",
    val accountNumber: String = "",
    val ifscCode: String = "",
    val bankName: String = "",
    val accountType: String = "savings",
)

/** `PaymentSettings.tsx` form. */
data class PaymentConfigForm(
    val apiKeyId: String = "",
    val apiKeySecret: String = "",
    val accountNumber: String = "",
    val webhookSecret: String = "",
    val defaultTransferMode: String = "NEFT",
    val isActive: Boolean = false,
)
