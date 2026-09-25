package app.aino.mobile.feature.notifications

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement

data class NotificationsUiState(
    val notifications: List<NotificationItem> = emptyList(),
    val unread: Int = 0,
    /** At least one fetch has succeeded since the last [NotificationsViewModel.reset]. */
    val loaded: Boolean = false,
    val loading: Boolean = false,
    /** Pull-to-refresh indicator; only the pull gesture turns it on. */
    val refreshing: Boolean = false,
    /** Shown only while nothing has loaded yet (the web ignores polling errors). */
    val error: String? = null,
)

/**
 * NotificationBell state, Activity-scoped. Idle cost is one GET every
 * [NOTIFICATION_POLL_INTERVAL_MS] between [start] and [stop]; realtime events
 * in [NOTIFICATION_REFRESH_EVENTS] trigger an immediate (coalesced) refetch.
 */
class NotificationsViewModel(
    private val repository: NotificationsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow(NotificationsUiState())
    val ui: StateFlow<NotificationsUiState> = _ui.asStateFlow()

    private val _unreadCount = MutableStateFlow(0)
    /** Bell badge count (`unread` from the server, adjusted by local read/delete). */
    val unreadCount: StateFlow<Int> = _unreadCount.asStateFlow()

    private var pollJob: Job? = null
    private var fetchJob: Job? = null
    private var fetchAgain = false
    /** Bumped by [reset] so in-flight responses from a previous session are dropped. */
    private var generation = 0L

    /** Starts the 30 s poll (fetches immediately). Idempotent. */
    fun start() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                fetch()
                delay(NOTIFICATION_POLL_INTERVAL_MS)
            }
        }
    }

    /** Pauses polling (e.g. app backgrounded); keeps the loaded list. */
    fun stop() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Sign-out: stop polling, drop in-flight work and clear all state. */
    fun reset() {
        stop()
        generation++
        fetchJob?.cancel()
        fetchJob = null
        fetchAgain = false
        set(NotificationsUiState())
    }

    /** Silent refetch (bell open, shell resume). */
    fun refresh() = fetch()

    /** Pull-to-refresh gesture on the notifications page. */
    fun pullRefresh() {
        set(_ui.value.copy(refreshing = true))
        fetch()
    }

    /** Realtime frame from the shell; returns true when it triggered a refetch. */
    fun onRealtimeEvent(type: String, @Suppress("UNUSED_PARAMETER") data: JsonElement?): Boolean {
        if (type !in NOTIFICATION_REFRESH_EVENTS) return false
        fetch()
        return true
    }

    /**
     * Row tap: marks it read (in the background) and returns the web link to
     * open, if any. The caller navigates immediately.
     */
    fun open(item: NotificationItem): String? {
        if (!item.isRead) {
            val gen = generation
            viewModelScope.launch {
                val ok = runIo { repository.markRead(item.id) }
                if (ok && gen == generation) markLocallyRead(item.id)
            }
        }
        return notificationLink(item)
    }

    fun markAllRead() {
        val gen = generation
        viewModelScope.launch {
            val ok = runIo { repository.markAllRead() }
            if (ok && gen == generation) {
                set(_ui.value.copy(notifications = _ui.value.notifications.map { it.copy(isRead = true) }, unread = 0))
            }
        }
    }

    /** Optimistic removal (swipe/dismiss); a failed delete restores the list by refetching. */
    fun delete(id: Long) {
        val current = _ui.value
        val removed = current.notifications.firstOrNull { it.id == id } ?: return
        set(
            current.copy(
                notifications = current.notifications.filterNot { it.id == id },
                unread = if (!removed.isRead) (current.unread - 1).coerceAtLeast(0) else current.unread,
            ),
        )
        val gen = generation
        viewModelScope.launch {
            val ok = runIo { repository.delete(id) }
            if (!ok && gen == generation) fetch()
        }
    }

    private fun markLocallyRead(id: Long) {
        val current = _ui.value
        val target = current.notifications.firstOrNull { it.id == id }
        if (target == null || target.isRead) return
        set(
            current.copy(
                notifications = current.notifications.map { if (it.id == id) it.copy(isRead = true) else it },
                unread = (current.unread - 1).coerceAtLeast(0),
            ),
        )
    }

    /** Coalesces concurrent requests: one in flight, at most one queued behind it. */
    private fun fetch() {
        if (fetchJob?.isActive == true) {
            fetchAgain = true
            return
        }
        val gen = generation
        fetchJob = viewModelScope.launch {
            set(_ui.value.copy(loading = true))
            do {
                fetchAgain = false
                val result = try {
                    Result.success(withContext(io) { repository.list() })
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
                if (gen != generation) return@launch
                result.fold(
                    onSuccess = { page ->
                        set(_ui.value.copy(notifications = page.notifications, unread = page.unread, loaded = true, error = null))
                    },
                    onFailure = { error ->
                        if (!_ui.value.loaded) set(_ui.value.copy(error = error.message ?: "Failed to fetch notifications"))
                    },
                )
            } while (fetchAgain)
            set(_ui.value.copy(loading = false, refreshing = false))
        }
    }

    private suspend fun runIo(block: () -> Unit): Boolean = try {
        withContext(io) { block() }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun set(state: NotificationsUiState) {
        _ui.value = state
        _unreadCount.value = state.unread
    }

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                NotificationsViewModel(NotificationsRepository(app.aino.mobile.core.AppContainer.get(context).api)) as T
        }
    }
}
