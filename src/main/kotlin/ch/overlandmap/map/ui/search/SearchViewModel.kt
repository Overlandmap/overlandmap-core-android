package ch.overlandmap.map.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.data.SearchResult
import java.util.Locale
import ch.overlandmap.map.data.NearbySearchRepository
import ch.overlandmap.map.geo.Coordinate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.transformLatest
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
class SearchViewModel(private val app: OverlandApp) : ViewModel() {

    private val repo = app.searchRepository

    val query = MutableStateFlow("")
    val error = MutableStateFlow<String?>(null)

    val minQueryLength = repo.minQueryLength

    val results: StateFlow<List<SearchResult>> = query
        .map { it.trim() }
        .debounce(200)
        .distinctUntilChanged()
        .mapLatest { text ->
            error.value = null
            if (text.length < repo.minQueryLength) emptyList()
            else try {
                ch.overlandmap.map.diagnostics.CrashReports.note("Text search start; length=${text.length}; language=${Locale.getDefault().language}")
                val found = repo.search(text, Locale.getDefault().language)
                ch.overlandmap.map.diagnostics.CrashReports.note("Text search complete; results=${found.size}")
                found
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("Search", "Text search failed", e)
                ch.overlandmap.map.diagnostics.CrashReports.note("Text search failure: ${e.javaClass.name}")
                error.value = e.localizedMessage ?: e.javaClass.simpleName
                emptyList()
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    enum class Mode { TEXT, NEARBY }
    val mode = MutableStateFlow(Mode.TEXT)
    data class NearbyRequest(val location: Coordinate, val radiusKm: Double, val type: NearbySearchRepository.Type)
    data class NearbyState(val results: List<SearchResult> = emptyList(), val loading: Boolean = false, val error: String? = null)
    private val nearbyRequest = MutableStateFlow<NearbyRequest?>(null)
    private var lastNearby: Pair<NearbyRequest, List<SearchResult>>? = null
    val nearby: StateFlow<NearbyState> = nearbyRequest.debounce(250).transformLatest { request ->
        if (request == null) { lastNearby = null; emit(NearbyState()); return@transformLatest }
        // Retain the list during position-only refreshes, so GPS updates don't flash a spinner.
        val previous = lastNearby?.takeIf { it.first.type == request.type && it.first.radiusKm == request.radiusKm }?.second.orEmpty()
        emit(NearbyState(results = previous, loading = true))
        try {
            ch.overlandmap.map.diagnostics.CrashReports.note("Nearby search start; type=${request.type}; radiusKm=${request.radiusKm}")
            val found = app.nearbySearchRepository.search(request.location, request.radiusKm, request.type, Locale.getDefault().language)
            ch.overlandmap.map.diagnostics.CrashReports.note("Nearby search complete; results=${found.size}")
            lastNearby = request to found
            emit(NearbyState(results = found))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emit(NearbyState(error = e.localizedMessage ?: e.javaClass.simpleName))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NearbyState())

    fun searchNearby(location: Coordinate?, radiusKm: Double?, type: NearbySearchRepository.Type) {
        nearbyRequest.value = if (location == null || radiusKm == null) null else NearbyRequest(location, radiusKm, type)
    }

    fun setQuery(value: String) {
        query.value = value
    }

    suspend fun waypoint(documentId: String) = repo.waypoint(documentId)
}
