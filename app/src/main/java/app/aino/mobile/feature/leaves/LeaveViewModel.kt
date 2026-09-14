package app.aino.mobile.feature.leaves

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.aino.mobile.core.auth.KeystoreTokenStore
import app.aino.mobile.core.network.OkHttpApiClient
import app.aino.mobile.core.network.RefreshingApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

data class LeaveUiState(
    val loading: Boolean = false,
    val tab: LeaveTab = LeaveTab.Apply,
    val month: YearMonth = YearMonth.now(),
    val leaves: List<Leave> = emptyList(),
    val balances: List<LeaveBalance> = emptyList(),
    val policies: List<LeavePolicy> = emptyList(),
    val holidays: List<Holiday> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    val applyType: String = "",
    val applyStart: String = LocalDate.now().toString(),
    val applyEnd: String = LocalDate.now().toString(),
    val applyDuration: String = "full",
    val applyReason: String = "",
    val error: String? = null,
    val message: String? = null,
) {
    val selectedPolicy: LeavePolicy? get() = policies.firstOrNull { it.leaveType == applyType }
    val requestedDates: List<String> get() = expandDateRange(applyStart, applyEnd)
}

class LeaveViewModel(private val repository: LeaveRepository) : ViewModel() {
    private val _ui = MutableStateFlow(LeaveUiState())
    val ui: StateFlow<LeaveUiState> = _ui.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            val month = _ui.value.month
            runCatching {
                // Each source fails independently: a role or org without
                // policies, holidays or calendar events must still see leaves.
                val leaves = repository.loadLeaves(month.atDay(1).toString(), month.atEndOfMonth().toString())
                val balances = runCatching { repository.loadBalances(month.year) }.getOrDefault(emptyList())
                val policies = runCatching(repository::loadPolicies).getOrDefault(emptyList())
                val holidays = runCatching { repository.loadHolidays(month.year) }.getOrDefault(emptyList())
                val events = runCatching {
                    repository.loadEvents(
                        startOfDayIso(month.atDay(1)),
                        startOfDayIso(month.atEndOfMonth().plusDays(1)),
                    )
                }.getOrDefault(emptyList())
                Loaded(leaves, balances, policies, holidays, events)
            }.fold(
                onSuccess = { loaded ->
                    _ui.value = _ui.value.copy(
                        loading = false,
                        leaves = loaded.leaves,
                        balances = loaded.balances,
                        policies = loaded.policies,
                        holidays = loaded.holidays,
                        events = loaded.events,
                        applyType = _ui.value.applyType.ifBlank { loaded.policies.firstOrNull()?.leaveType.orEmpty() },
                    )
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not load leaves") },
            )
        }
    }

    /** The calendar route compares timestamptz, so local midnight is sent as an instant. */
    private fun startOfDayIso(date: LocalDate): String =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toString()

    fun selectTab(tab: LeaveTab) { _ui.value = _ui.value.copy(tab = tab, error = null, message = null) }

    fun changeMonth(delta: Long) {
        _ui.value = _ui.value.copy(month = _ui.value.month.plusMonths(delta))
        refresh()
    }

    fun currentMonth() {
        _ui.value = _ui.value.copy(month = YearMonth.now())
        refresh()
    }

    fun updateApplyForm(
        type: String? = null,
        start: String? = null,
        end: String? = null,
        duration: String? = null,
        reason: String? = null,
    ) {
        val nextStart = start ?: _ui.value.applyStart
        _ui.value = _ui.value.copy(
            applyType = type ?: _ui.value.applyType,
            applyStart = nextStart,
            // Keep the range coherent: moving the start past the end drags the
            // end with it instead of producing an empty selection.
            applyEnd = end ?: (if (start != null && _ui.value.applyEnd < nextStart) nextStart else _ui.value.applyEnd),
            applyDuration = duration ?: _ui.value.applyDuration,
            applyReason = reason ?: _ui.value.applyReason,
            error = null,
        )
    }

    fun apply() {
        val current = _ui.value
        val dates = current.requestedDates
        validateLeaveApplication(
            current.applyType,
            dates,
            current.applyDuration,
            current.applyReason,
            current.selectedPolicy,
            current.policies.isNotEmpty(),
        )?.let {
            _ui.value = current.copy(error = it)
            return
        }
        _ui.value = current.copy(loading = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                repository.apply(
                    ApplyLeavePayload(
                        leaveType = current.applyType,
                        dates = dates,
                        duration = current.applyDuration,
                        reason = current.applyReason.trim().ifBlank { null },
                    ),
                )
            }.fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(loading = false, applyReason = "", message = it.message.ifBlank { "Leave submitted" })
                    refresh()
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not submit the leave") },
            )
        }
    }

    fun act(leave: Leave) {
        val action = availableLeaveAction(leave)
        if (action == LeaveAction.None) return
        _ui.value = _ui.value.copy(loading = true, error = null, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                // Cancel deletes a pending row; withdraw on an approved leave
                // raises the manager approval request the server expects.
                if (action == LeaveAction.Cancel) repository.cancel(leave.id) else repository.withdraw(leave.id)
            }.fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(loading = false, message = it.message.ifBlank { "Leave updated" })
                    refresh()
                },
                onFailure = { _ui.value = _ui.value.copy(loading = false, error = it.message ?: "Could not update the leave") },
            )
        }
    }

    fun clearNotices() { _ui.value = _ui.value.copy(error = null, message = null) }

    private data class Loaded(
        val leaves: List<Leave>,
        val balances: List<LeaveBalance>,
        val policies: List<LeavePolicy>,
        val holidays: List<Holiday>,
        val events: List<CalendarEvent>,
    )

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val tokens = KeystoreTokenStore(context)
                val api = RefreshingApiClient(OkHttpApiClient(tokenProvider = tokens), tokens)
                return LeaveViewModel(LeaveRepository(api)) as T
            }
        }
    }
}
