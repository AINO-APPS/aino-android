package app.aino.mobile.feature.admin

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P10.3 payroll pages (`CompensationSetup`, `SalarySlips`, `PaymentSettings`)
 * plus the Android-only employee / slip pages. Shared by every payroll
 * route through the Admin hub entry, like [AdminViewModel].
 */
class PayrollViewModel(
    private val repository: CompensationRepository,
    private val admin: AdminRepository,
    private val background: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow(PayrollUiState())
    val ui: StateFlow<PayrollUiState> = _ui.asStateFlow()
    private var noticeJob: Job? = null

    fun bind(role: String, userId: Long, orgId: Long?) {
        val s = _ui.value
        if (s.userId != 0L && (s.userId != userId || s.orgId != orgId)) _ui.value = PayrollUiState()
        _ui.update { it.copy(role = role, userId = userId, orgId = orgId) }
    }

    fun loadSection(key: String, force: Boolean = false) {
        when (key) {
            // Web: one Promise.allSettled for the whole page.
            PayrollSectionKeys.COMPENSATION -> {
                loadTemplates(force); loadEmployees(force); loadMembers(force); loadBank(force); loadCtc(force)
            }
            PayrollSectionKeys.SALARY_SLIPS -> { loadPeriods(force); if (_ui.value.selectedPeriodId != null) loadSlips(force) }
            PayrollSectionKeys.PAYMENT_CONFIG -> loadPaymentConfig(force)
        }
    }

    fun selectCompTab(tab: CompTab) = _ui.update { it.copy(compTab = tab) }

    fun loadTemplates(force: Boolean = false) =
        query({ it.templates }, { s, v -> s.copy(templates = v) }, "Failed to fetch templates", force) { repository.templates() }

    fun loadEmployees(force: Boolean = false) =
        query({ it.employees }, { s, v -> s.copy(employees = v) }, "Failed to fetch employee compensations", force) { repository.employees() }

    private fun loadMembers(force: Boolean) =
        query({ it.members }, { s, v -> s.copy(members = v) }, "Failed to load members", force) { repository.members() }

    fun loadBank(force: Boolean = false) {
        query({ it.bankVerifications }, { s, v -> s.copy(bankVerifications = v) }, "Failed to fetch bank verifications", force) { repository.bankVerifications() }
        if (_ui.value.bankShowAll) {
            query({ it.bankAccounts }, { s, v -> s.copy(bankAccounts = v) }, "Failed to fetch bank details", force) { repository.orgBankDetails() }
        }
    }

    /** Android-only toggle: `bank-verifications` (pending first) vs `bank-details` (by name). */
    fun setBankShowAll(all: Boolean) {
        _ui.update { it.copy(bankShowAll = all) }
        loadBank(force = true)
    }

    private fun loadCtc(force: Boolean) =
        query({ it.ctc }, { s, v -> s.copy(ctc = v) }, "Failed to fetch CTC config", force) { repository.ctcConfig() }

    private fun loadPeriods(force: Boolean) =
        query({ it.periods }, { s, v -> s.copy(periods = v) }, "Failed to load pay periods", force) { admin.payPeriods() }

    fun selectPeriod(id: Long?) {
        _ui.update { it.copy(selectedPeriodId = id, slips = Load(), disbursements = Load()) }
        if (id != null) loadSlips(force = true)
    }

    /** Web `slipData`: slips first, then disbursements for the same period. */
    fun loadSlips(force: Boolean = false) {
        val period = _ui.value.selectedPeriodId ?: return
        query({ it.slips }, { s, v -> s.copy(slips = v) }, "Failed to fetch salary slips", force) { repository.slips(period) }
        query({ it.disbursements }, { s, v -> s.copy(disbursements = v) }, "Failed to fetch disbursements", force) { repository.disbursements(period) }
    }

    fun loadPaymentConfig(force: Boolean = false) =
        query({ it.paymentConfig }, { s, v -> s.copy(paymentConfig = v) }, "Failed to fetch payment config", force) { Saved(repository.paymentConfig()) }

    // ── Templates ───────────────────────────────────────────────────────────

    fun saveTemplate(form: TemplateForm, onDone: () -> Unit) {
        if (!form.canSave) return notify(false, "Name, and a key and label for every component, are required")
        mutation({ repository.saveTemplate(form) }, "Failed to save template") {
            onDone()
            loadTemplates(force = true)
        }
    }

    fun deleteTemplate(id: Long) = mutation({ repository.deleteTemplate(id) }, "Cannot delete template") {
        loadTemplates(force = true)
    }

    // ── Employee compensation ───────────────────────────────────────────────

    fun assign(form: AssignForm, onDone: () -> Unit) {
        if (form.userId == null) return notify(false, "Please select an employee")
        if (!form.canSave) return notify(false, "effective_from and base_salary are required")
        mutation({ repository.assign(form) }, "Failed to assign compensation") {
            onDone()
            loadEmployees(force = true)
            _ui.value.employeePage?.takeIf { it.userId == form.userId }?.let { loadEmployeePage(it.userId, it.name) }
        }
    }

    fun saveCtc(form: CtcForm, onDone: (CtcConfig) -> Unit) {
        val config = form.toConfig() ?: return notify(false, "Every CTC field is required")
        mutation({ repository.saveCtcConfig(config) }, "Failed to save CTC config") { saved ->
            // The web flashes "Saved!" on the button for 2s instead of a banner.
            _ui.update { it.copy(ctc = Load(saved)) }
            onDone(saved)
        }
    }

    // ── Bank details ────────────────────────────────────────────────────────

    fun approveBank(userId: Long) = mutation({ repository.approveBankDetails(userId) }, "Failed to approve") {
        loadBank(force = true); refreshEmployeeBank(userId)
    }

    fun rejectBank(userId: Long) = mutation({ repository.rejectBankDetails(userId) }, "Failed to reject") {
        loadBank(force = true); refreshEmployeeBank(userId)
    }

    fun verifyBank(userId: Long) = mutation({ repository.verifyBankDetails(userId) }, "Verification failed") { r ->
        notify(true, r.message ?: "Verification initiated (penny drop)")
        loadBank(force = true); refreshEmployeeBank(userId)
    }

    fun saveEmployeeBank(userId: Long, form: BankForm, onDone: () -> Unit) {
        if (form.accountHolderName.isBlank() || form.accountNumber.isBlank() || form.ifscCode.isBlank()) {
            return notify(false, "Please fill in all required fields")
        }
        mutation({ repository.saveEmployeeBankDetails(userId, form) }, "Failed to save bank details") {
            notify(true, "Bank details saved")
            onDone()
            loadBank(force = true); refreshEmployeeBank(userId)
        }
    }

    // ── Employee page (Android-only) ────────────────────────────────────────

    fun openEmployee(userId: Long, name: String) {
        if (_ui.value.employeePage?.userId != userId) _ui.update { it.copy(employeePage = EmployeePage(userId, name)) }
        loadEmployeePage(userId, name)
    }

    private fun loadEmployeePage(userId: Long, name: String) {
        pageQuery(userId, name, { it.history }, { p, v -> p.copy(history = v) }, "Failed to fetch compensation history") { repository.history(userId) }
        refreshEmployeeBank(userId)
    }

    private fun refreshEmployeeBank(userId: Long) {
        val page = _ui.value.employeePage?.takeIf { it.userId == userId } ?: return
        pageQuery(userId, page.name, { it.bank }, { p, v -> p.copy(bank = v) }, "Failed to fetch bank details") { Saved(repository.employeeBankDetails(userId)) }
    }

    fun updateRecord(userId: Long, id: Long, edit: RecordEdit, onDone: () -> Unit) =
        mutation({ repository.updateRecord(userId, id, edit) }, "Failed to update compensation") {
            notify(true, "Compensation updated")
            onDone()
            loadEmployees(force = true)
            _ui.value.employeePage?.let { loadEmployeePage(it.userId, it.name) }
        }

    // ── Salary slips ────────────────────────────────────────────────────────

    fun runPayroll() {
        val period = _ui.value.selectedPeriodId ?: return
        mutation({ repository.runPayroll(period) }, "Payroll run failed") { r ->
            notify(true, r.message ?: "Generated ${r.count} salary slips"); loadSlips(force = true)
        }
    }

    fun publish(id: Long) = mutation({ repository.publish(id) }, "Failed to publish") {
        loadSlips(force = true); if (_ui.value.slipPageId == id) openSlip(id)
    }

    fun bulkPublish() {
        val period = _ui.value.selectedPeriodId ?: return
        mutation({ repository.bulkPublish(period) }, "Bulk publish failed") { r ->
            notify(true, r.message ?: "Published ${r.count} salary slips"); loadSlips(force = true)
        }
    }

    fun disburseAll() {
        val period = _ui.value.selectedPeriodId ?: return
        mutation({ repository.disburse(period) }, "Disbursement failed") { r -> notify(true, disburseMessage(r)); loadSlips(force = true) }
    }

    fun disburseOne(slipId: Long) = mutation({ repository.disburseOne(slipId) }, "Disbursement failed") { r ->
        notify(true, r.message ?: "Disbursement initiated"); loadSlips(force = true)
    }

    fun retry(disbursementId: Long) = mutation({ repository.retry(disbursementId) }, "Retry failed") { r ->
        notify(true, r.message ?: "Retry initiated"); loadSlips(force = true)
    }

    /** PDF bytes for [onReady]; a failure shows the web's "Failed to download PDF". */
    fun downloadPdf(slipId: Long, onReady: (ByteArray) -> Unit) =
        mutation({ repository.slipPdf(slipId) }, "Failed to download PDF", useServerMessage = false, onSuccess = onReady)

    fun openSlip(id: Long) {
        if (_ui.value.slipPageId != id) _ui.update { it.copy(slipPageId = id, slipPage = Load()) }
        query({ it.slipPage }, { s, v -> s.copy(slipPage = v) }, "Failed to fetch salary slip", force = true) { repository.slip(id) }
    }

    // ── Payment config ──────────────────────────────────────────────────────

    fun savePaymentConfig(form: PaymentConfigForm) {
        if (form.apiKeyId.isBlank() || form.apiKeySecret.isBlank() || form.accountNumber.isBlank()) return notify(false, "All fields are required")
        mutation({ repository.savePaymentConfig(form) }, "Failed to save") {
            notify(true, "Payment configuration saved successfully"); loadPaymentConfig(force = true)
        }
    }

    fun testPaymentConfig() = mutation({ repository.testPaymentConfig() }, "Connection test failed") { r ->
        notify(true, paymentTestMessage(r.balance))
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private fun <T> query(
        get: (PayrollUiState) -> Load<T>,
        set: (PayrollUiState, Load<T>) -> PayrollUiState,
        fallback: String,
        force: Boolean = false,
        fetch: () -> T,
    ) {
        if (get(_ui.value).loading && !force) return
        _ui.update { set(it, get(it).copy(loading = true, error = null)) }
        viewModelScope.launch {
            val result = withContext(background) { runCatching(fetch) }
            _ui.update { s ->
                result.fold(
                    onSuccess = { set(s, Load(it)) },
                    onFailure = { e -> set(s, get(s).copy(loading = false, error = e.adminMessage(fallback))) },
                )
            }
        }
    }

    /** A query scoped to the employee page; dropped if the page moved to someone else meanwhile. */
    private fun <T> pageQuery(
        userId: Long,
        name: String,
        get: (EmployeePage) -> Load<T>,
        set: (EmployeePage, Load<T>) -> EmployeePage,
        fallback: String,
        fetch: () -> T,
    ) {
        _ui.update { s ->
            val page = s.employeePage?.takeIf { it.userId == userId } ?: EmployeePage(userId, name)
            s.copy(employeePage = set(page, get(page).copy(loading = true, error = null)))
        }
        viewModelScope.launch {
            val result = withContext(background) { runCatching(fetch) }
            _ui.update { s ->
                val page = s.employeePage?.takeIf { it.userId == userId } ?: return@update s
                val next = result.fold(
                    onSuccess = { set(page, Load(it)) },
                    onFailure = { e -> set(page, get(page).copy(loading = false, error = e.adminMessage(fallback))) },
                )
                s.copy(employeePage = next)
            }
        }
    }

    private fun <T> mutation(action: () -> T, fallback: String, useServerMessage: Boolean = true, onSuccess: (T) -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true) }
        viewModelScope.launch {
            val result = withContext(background) { runCatching(action) }
            _ui.update { it.copy(busy = false) }
            result.fold(onSuccess = onSuccess, onFailure = { notify(false, if (useServerMessage) it.adminMessage(fallback) else fallback) })
        }
    }

    fun dismissNotice() {
        noticeJob?.cancel()
        _ui.update { it.copy(notice = null) }
    }

    private fun notify(ok: Boolean, text: String) {
        _ui.update { it.copy(notice = AdminNotice(ok, text)) }
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(NOTICE_DISMISS_MS)
            _ui.update { it.copy(notice = null) }
        }
    }

    companion object {
        /** `useAutoDismiss` default delay. */
        private const val NOTICE_DISMISS_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val container = app.aino.mobile.core.AppContainer.get(context)
                return PayrollViewModel(CompensationRepository(container.api), AdminRepository(container.api)) as T
            }
        }
    }
}
