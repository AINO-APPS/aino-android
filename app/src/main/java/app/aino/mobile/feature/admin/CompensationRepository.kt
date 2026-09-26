package app.aino.mobile.feature.admin

import app.aino.mobile.core.network.ApiClient
import app.aino.mobile.core.network.ApiError
import app.aino.mobile.core.network.ApiRequest
import app.aino.mobile.core.network.ApiResponse
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * `client/src/api/compensation.ts`, admin half (P10.3). The self-service
 * calls (`my-slips`, `my-bank-details`) live in the Organization feature.
 * Responses are bare JSON; `payment-config` and `bank-details/:userId`
 * answer `null` when nothing is saved.
 */
class CompensationRepository(
    private val api: ApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true },
) {
    // ── Templates ───────────────────────────────────────────────────────────

    fun templates(): List<CompensationTemplate> = load {
        // @api GET compensation/templates
        decode(api.execute(ApiRequest(path = "compensation/templates")))
    }

    /** Create (`POST`) or update (`PUT …/:id`) with the web's `templateForm` body. */
    fun saveTemplate(form: TemplateForm): CompensationTemplate {
        val body = buildJsonObject {
            put("name", form.name.trim())
            put("description", form.description)
            put("components", json.encodeToJsonElement(ListSerializer(CompComponent.serializer()), form.components))
            put("is_default", form.isDefault)
        }
        val id = form.editingId
        return if (id == null) {
            // @api POST compensation/templates
            mutate("compensation/templates", body, "POST")
        } else {
            // @api PUT compensation/templates/:id
            mutate("compensation/templates/$id", body, "PUT")
        }
    }

    fun deleteTemplate(id: Long): MessageResponse {
        // @api DELETE compensation/templates/:id
        return mutate("compensation/templates/$id", Unit, "DELETE")
    }

    // ── Employee compensation ───────────────────────────────────────────────

    fun employees(): List<EmployeeCompensation> = load {
        // @api GET compensation/employees
        decode(api.execute(ApiRequest(path = "compensation/employees")))
    }

    /** Android-only: full history for one employee (newest first). */
    fun history(userId: Long): List<EmployeeCompensation> = load {
        // @api GET compensation/employees/:userId
        decode(api.execute(ApiRequest(path = "compensation/employees/$userId")))
    }

    /** Web `assignCompensation`: closes the active record and inserts a new one. */
    fun assign(form: AssignForm): EmployeeCompensation {
        val userId = requireNotNull(form.userId)
        val body = buildJsonObject {
            put("effective_from", form.effectiveFrom)
            put("ctc_annual", parseAmount(form.ctcAnnual))
            put("base_salary", parseAmount(form.baseSalary))
            put("components", JsonObject(form.components.mapValues { JsonPrimitive(parseAmount(it.value)) }))
            put("template_id", form.templateId?.let(::JsonPrimitive) ?: JsonNull)
        }
        // @api POST compensation/employees/:userId
        return mutate("compensation/employees/$userId", body, "POST")
    }

    /** Android-only in-place correction; blank fields go as null (server COALESCE keeps them). */
    fun updateRecord(userId: Long, id: Long, edit: RecordEdit): EmployeeCompensation {
        val body = buildJsonObject {
            put("base_salary", edit.baseSalary.trim().toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("ctc_annual", edit.ctcAnnual.trim().toDoubleOrNull()?.let(::JsonPrimitive) ?: JsonNull)
            put("currency", edit.currency.orNull())
            put("payment_frequency", edit.paymentFrequency.orNull())
            put("bank_account", edit.bankAccount.orNull())
            put("notes", edit.notes.orNull())
        }
        // @api PUT compensation/employees/:userId/:id
        return mutate("compensation/employees/$userId/$id", body, "PUT")
    }

    // ── Payroll run + salary slips ──────────────────────────────────────────

    fun runPayroll(payPeriodId: Long): CountResult {
        // @api POST compensation/payroll-run
        return mutate("compensation/payroll-run", buildJsonObject { put("pay_period_id", payPeriodId) }, "POST")
    }

    fun slips(payPeriodId: Long): List<SalarySlip> = load {
        val q = "?pay_period_id=$payPeriodId"
        // @api GET compensation/salary-slips
        decode(api.execute(ApiRequest(path = "compensation/salary-slips" + q)))
    }

    /** Android-only slip page. */
    fun slip(id: Long): SalarySlip = load {
        // @api GET compensation/salary-slips/:id
        decode(api.execute(ApiRequest(path = "compensation/salary-slips/$id")))
    }

    fun publish(id: Long): SalarySlip {
        // @api PUT compensation/salary-slips/:id/publish
        return mutate("compensation/salary-slips/$id/publish", Unit, "PUT")
    }

    fun bulkPublish(payPeriodId: Long): CountResult {
        // @api POST compensation/salary-slips/bulk-publish
        return mutate("compensation/salary-slips/bulk-publish", buildJsonObject { put("pay_period_id", payPeriodId) }, "POST")
    }

    /** `application/pdf` bytes (web `responseType: "blob"`). */
    fun slipPdf(id: Long): ByteArray = load {
        // @api GET compensation/salary-slips/:id/pdf
        api.execute(ApiRequest(path = "compensation/salary-slips/$id/pdf", headers = mapOf("Accept" to "application/pdf"))).body
    }

    // ── Disbursement ────────────────────────────────────────────────────────

    fun disburse(payPeriodId: Long): DisburseResult {
        // @api POST compensation/disburse
        return mutate("compensation/disburse", buildJsonObject { put("pay_period_id", payPeriodId) }, "POST")
    }

    /** Android-only: pay one published slip. */
    fun disburseOne(slipId: Long): PayoutResult {
        // @api POST compensation/disburse/:slipId
        return mutate("compensation/disburse/$slipId", Unit, "POST")
    }

    fun disbursements(payPeriodId: Long): List<Disbursement> = load {
        val q = "?pay_period_id=$payPeriodId"
        // @api GET compensation/disbursements
        decode(api.execute(ApiRequest(path = "compensation/disbursements" + q)))
    }

    fun retry(disbursementId: Long): PayoutResult {
        // @api POST compensation/disburse/retry/:id
        return mutate("compensation/disburse/retry/$disbursementId", Unit, "POST")
    }

    // ── Payment + CTC config ────────────────────────────────────────────────

    fun paymentConfig(): PaymentConfig? = load {
        // @api GET compensation/payment-config
        decodeOrNull(api.execute(ApiRequest(path = "compensation/payment-config")))
    }

    fun savePaymentConfig(form: PaymentConfigForm): MessageResponse {
        val body = buildJsonObject {
            put("api_key_id", form.apiKeyId)
            put("api_key_secret", form.apiKeySecret)
            put("account_number", form.accountNumber)
            put("webhook_secret", form.webhookSecret)
            put("default_transfer_mode", form.defaultTransferMode)
            put("is_active", form.isActive)
        }
        // @api PUT compensation/payment-config
        return mutate("compensation/payment-config", body, "PUT")
    }

    fun testPaymentConfig(): PaymentTestResult {
        // @api POST compensation/payment-config/test
        return mutate("compensation/payment-config/test", Unit, "POST")
    }

    fun ctcConfig(): CtcConfig = load {
        // @api GET compensation/ctc-config
        decode(api.execute(ApiRequest(path = "compensation/ctc-config")))
    }

    fun saveCtcConfig(config: CtcConfig): CtcConfig {
        // @api PUT compensation/ctc-config
        return mutate("compensation/ctc-config", config, "PUT")
    }

    // ── Bank details ────────────────────────────────────────────────────────

    fun bankVerifications(): List<BankDetails> = load {
        // @api GET compensation/bank-verifications
        decode(api.execute(ApiRequest(path = "compensation/bank-verifications")))
    }

    /** Android-only: every saved account, by name. */
    fun orgBankDetails(): List<BankDetails> = load {
        // @api GET compensation/bank-details
        decode(api.execute(ApiRequest(path = "compensation/bank-details")))
    }

    fun employeeBankDetails(userId: Long): BankDetails? = load {
        // @api GET compensation/bank-details/:userId
        decodeOrNull(api.execute(ApiRequest(path = "compensation/bank-details/$userId")))
    }

    /** Android-only admin entry; also registers the payee with Razorpay when payouts are configured. */
    fun saveEmployeeBankDetails(userId: Long, form: BankForm): BankDetails {
        // @api POST compensation/bank-details/:userId
        return mutate("compensation/bank-details/$userId", bankBody(form), "POST")
    }

    /** Android-only penny-drop check. */
    fun verifyBankDetails(userId: Long): MessageResponse {
        // @api POST compensation/bank-details/:userId/verify
        return mutate("compensation/bank-details/$userId/verify", Unit, "POST")
    }

    fun approveBankDetails(userId: Long): MessageResponse {
        // @api POST compensation/bank-details/:userId/approve
        return mutate("compensation/bank-details/$userId/approve", Unit, "POST")
    }

    fun rejectBankDetails(userId: Long): MessageResponse {
        // @api POST compensation/bank-details/:userId/reject
        return mutate("compensation/bank-details/$userId/reject", Unit, "POST")
    }

    /** Assign-modal employee picker (web `getOrgMembers({ perPage: 500 })`; the route caps pages at 100). */
    fun members(): List<PayrollMember> = load {
        val all = mutableListOf<PayrollMember>()
        var page = 1
        while (page <= MAX_PAGES) {
            // @api GET org/members
            val result: PayrollMemberPage = decode(api.execute(ApiRequest(path = "org/members?per_page=100&page=$page")))
            all += result.data
            if (result.data.isEmpty() || all.size >= result.total) break
            page++
        }
        all
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun bankBody(form: BankForm): JsonObject = buildJsonObject {
        put("account_holder_name", form.accountHolderName)
        put("account_number", form.accountNumber)
        put("ifsc_code", form.ifscCode)
        put("bank_name", form.bankName)
        put("account_type", form.accountType)
    }

    private fun String.orNull(): JsonElement = trim().takeIf { it.isNotEmpty() }?.let(::JsonPrimitive) ?: JsonNull

    private inline fun <T> load(block: () -> T): T {
        try {
            return block()
        } catch (error: ApiError.Http) {
            throw AdminFailure(serverMessage(error), error.statusCode, error)
        }
    }

    private inline fun <reified T, reified R> mutate(path: String, body: T, method: String): R {
        try {
            // OkHttp requires a body for POST/PUT; DELETE may go without one.
            val bytes = if (body is Unit) ByteArray(0).takeIf { method != "DELETE" } else json.encodeToString(body).toByteArray()
            return decode(api.execute(ApiRequest(method, path, body = bytes)))
        } catch (error: ApiError.Http) {
            throw AdminFailure(serverMessage(error), error.statusCode, error)
        }
    }

    private fun serverMessage(error: ApiError.Http): String? = runCatching {
        val obj = json.parseToJsonElement(error.responseBody).jsonObject
        (obj["error"] ?: obj["message"])?.jsonPrimitive?.content
    }.getOrNull()

    private inline fun <reified T> decode(response: ApiResponse): T =
        json.decodeFromString(response.bodyAsString().ifBlank { "{}" })

    /** `res.json(null)` means "not configured yet". */
    private inline fun <reified T> decodeOrNull(response: ApiResponse): T? =
        json.decodeFromString<T?>(response.bodyAsString().ifBlank { "null" })

    private companion object {
        const val MAX_PAGES = 50
    }
}

/** Android-only record correction (`PUT employees/:userId/:id`). */
data class RecordEdit(
    val baseSalary: String = "",
    val ctcAnnual: String = "",
    val currency: String = "",
    val paymentFrequency: String = "",
    val bankAccount: String = "",
    val notes: String = "",
)

fun recordEditFor(r: EmployeeCompensation) = RecordEdit(
    baseSalary = plainAmount(r.baseSalary),
    ctcAnnual = plainAmount(r.ctcAnnual),
    currency = r.currency.orEmpty(),
    paymentFrequency = r.paymentFrequency.orEmpty(),
    bankAccount = r.bankAccount.orEmpty(),
    notes = r.notes.orEmpty(),
)
