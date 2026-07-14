package ch.overlandmap.map.ui.world

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the user tapped on the world map (or picked in the country search). */
sealed interface WorldSelection {
    data class OfCountry(val country: Country) : WorldSelection
    data class OfBorder(val border: CountryBorder) : WorldSelection
    data class OfBorderPost(val post: BorderPost) : WorldSelection
}

class WorldViewModel(private val app: OverlandApp) : ViewModel() {

    private val world = app.worldRepository

    val countries = world.observeCountries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val borders = world.observeBorders()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val borderPosts = world.observeBorderPosts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selection = MutableStateFlow<WorldSelection?>(null)

    init {
        // First launch: fill the cache. Later launches refresh silently; when
        // offline the cached world data keeps working.
        viewModelScope.launch {
            try {
                world.refresh()
            } catch (_: Exception) {
            }
        }
    }

    fun select(selection: WorldSelection?) {
        this.selection.value = selection
    }

    fun selectBorderId(documentId: String) {
        borders.value.firstOrNull { it.documentId == documentId }?.let {
            selection.value = WorldSelection.OfBorder(it)
        }
    }

    fun selectBorderPostId(documentId: String) {
        borderPosts.value.firstOrNull { it.documentId == documentId }?.let {
            selection.value = WorldSelection.OfBorderPost(it)
        }
    }

    fun selectCountryCode(isoA2: String) {
        viewModelScope.launch {
            app.worldRepository.countryWithCode(isoA2)?.let {
                selection.value = WorldSelection.OfCountry(it)
            }
        }
    }
}
