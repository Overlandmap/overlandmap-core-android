package ch.overlandmap.map.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.data.SearchResult
import java.util.Locale
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

/**
 * Drives the full-text search screen: the query text and, debounced, its
 * results in the current app language. Searches run only once the query
 * reaches [SearchRepository.minQueryLength] characters.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(app: OverlandApp) : ViewModel() {

    private val repo = app.searchRepository

    val query = MutableStateFlow("")

    val minQueryLength = repo.minQueryLength

    val results: StateFlow<List<SearchResult>> = query
        .map { it.trim() }
        .debounce(200)
        .distinctUntilChanged()
        .mapLatest { text ->
            if (text.length < repo.minQueryLength) emptyList()
            else runCatching { repo.search(text, Locale.getDefault().language) }
                .getOrDefault(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(value: String) {
        query.value = value
    }

    suspend fun waypoint(documentId: String) = repo.waypoint(documentId)
}
