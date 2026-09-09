package ch.overlandmap.map.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.data.GpsFormat
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.Waypoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ItineraryState(
    val loading: Boolean = true,
    val itinerary: Itinerary? = null,
    /** The itinerary's pack, for editor/name in the share link. */
    val trackPack: TrackPack? = null,
    val steps: List<ItineraryStep> = emptyList(),
    val tracks: List<Track> = emptyList(),
    val waypoints: List<Waypoint> = emptyList(),
    /** The pack's waypoints that don't belong to this itinerary (for the filter). */
    val otherWaypoints: List<Waypoint> = emptyList(),
)

/** A local itinerary, read from Room; comments cached from Firestore. */
class ItineraryViewModel(app: OverlandApp, private val itineraryId: String) : ViewModel() {

    private val library = app.libraryRepository

    val state = MutableStateFlow(ItineraryState())

    /** Index into [ItineraryState.steps] of the step shown in the Steps tab. */
    val selectedStepIndex = MutableStateFlow(0)

    fun selectStep(index: Int) {
        selectedStepIndex.value = index
    }

    val useMiles = app.userPreferences.useMiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val useFeet = app.userPreferences.useFeet
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    val gpsFormat = app.userPreferences.gpsFormat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GpsFormat.DD)

    val comments: StateFlow<List<Comment>> = library.observeComments(itineraryId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val itinerary = library.itinerary(itineraryId)
            val own = library.waypoints(itineraryId)
            // The pack's other waypoints (for the map's waypoint-category
            // filter): every pack waypoint that isn't one of this itinerary's.
            val ownIds = own.map { it.documentId }.toSet()
            val others = itinerary
                ?.let { library.waypointsOfPack(it.trackPackId) }
                ?.filter { it.documentId !in ownIds && it.itineraryId != itineraryId }
                ?: emptyList()
            state.value = ItineraryState(
                loading = false,
                itinerary = itinerary,
                trackPack = itinerary?.let { library.trackPack(it.trackPackId) },
                steps = library.steps(itineraryId).sortedBy { it.stepId },
                tracks = library.tracks(itineraryId),
                waypoints = own,
                otherWaypoints = others,
            )
            // Feeds the home screen's "Last used" section.
            library.markOpened(itineraryId)
        }
        // Refresh the comment cache; offline the cache keeps its content.
        viewModelScope.launch {
            runCatching { library.refreshComments(itineraryId) }
        }
    }
}
