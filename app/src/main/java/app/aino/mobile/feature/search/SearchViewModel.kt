package app.aino.mobile.feature.search

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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchUiState(
    val query: String = "",
    val results: SearchResults? = null,
    val navResults: List<NavItem> = emptyList(),
    val loading: Boolean = false,
    /** A keystroke is waiting out the 350 ms debounce. */
    val pending: Boolean = false,
    val error: String = "",
) {
    val hasResults: Boolean get() = navResults.isNotEmpty() || results?.isEmpty() == false

    /** `No results for "{query}"`, suppressed while a search is still due. */
    val showNoResults: Boolean get() = !hasResults && !loading && !pending && isSearchable(query)
}

/** useGlobalSearch: debounced server search plus the client-side page index. */
class SearchViewModel(
    private val repository: SearchRepository,
    role: String? = null,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _ui = MutableStateFlow(SearchUiState())
    val ui: StateFlow<SearchUiState> = _ui.asStateFlow()

    private var role: String? = role
    private var debounceJob: Job? = null
    private var searchJob: Job? = null

    /** Role gates the "Pages & Features" index (`minRole` / `excludeRole`). */
    fun setRole(role: String?) {
        if (this.role == role) return
        this.role = role
        _ui.update { it.copy(navResults = navResults(it.query, role)) }
    }

    fun onQueryChange(value: String) {
        _ui.update { it.copy(query = value, navResults = navResults(value, role), pending = true) }
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            doSearch(value)
        }
    }

    /** IME "search" action: skip the remaining debounce. */
    fun submit() {
        debounceJob?.cancel()
        doSearch(_ui.value.query)
    }

    fun clear() = onQueryChange("")

    private fun doSearch(query: String) {
        searchJob?.cancel()
        if (!isSearchable(query)) {
            _ui.update { it.copy(results = null, error = "", loading = false, pending = false) }
            return
        }
        _ui.update { it.copy(loading = true, pending = false, error = "") }
        searchJob = viewModelScope.launch {
            try {
                val results = withContext(io) { repository.search(query) }
                _ui.update { it.copy(results = results, loading = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _ui.update { it.copy(results = null, loading = false, error = "Search failed. Please try again.") }
            }
        }
    }

    companion object {
        fun factory(context: Context, role: String? = null): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SearchViewModel(SearchRepository(app.aino.mobile.core.AppContainer.get(context).api), role) as T
        }
    }
}
