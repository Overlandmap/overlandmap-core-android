package ch.overlandmap.map.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.TrackPack
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(app: OverlandApp) : ViewModel() {

    private val library = app.libraryRepository

    /** Downloaded track packs (the "Local Library" section). */
    val packs: StateFlow<List<TrackPack>> = library.observeTrackPacks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The five most recently opened itineraries (the "Last used" section). */
    val lastUsed: StateFlow<List<Itinerary>> = library.observeLastOpened(5)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun deletePack(trackPackId: String) {
        viewModelScope.launch { library.deletePack(trackPackId) }
    }
}
