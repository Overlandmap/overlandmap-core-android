package ch.overlandmap.map.navigation

import ch.overlandmap.map.data.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The navigation to resume after a restart (iOS `SavedNavigationState` in UserDefaults), kept in
 * DataStore and mirrored in [state] so the UI knows synchronously whether the Nav tab applies.
 */
class SavedNavigationStore(private val prefs: UserPreferences, private val scope: CoroutineScope) : NavigationPersistence {

    private val _state = MutableStateFlow(decode(prefs.savedNavigationNow()))
    val state: StateFlow<SavedNavigationState?> = _state.asStateFlow()

    override fun load(): SavedNavigationState? = _state.value

    override fun save(state: SavedNavigationState) {
        if (_state.value == state) return
        _state.value = state
        scope.launch { prefs.setSavedNavigation(encode(state)) }
    }

    override fun clear() {
        if (_state.value == null) return
        _state.value = null
        scope.launch { prefs.setSavedNavigation(null) }
    }

    companion object {
        fun encode(state: SavedNavigationState): String =
            JSONObject().put("itineraryID", state.itineraryId).put("isReverse", state.isReverse)
                .put("targetIndex", state.targetIndex).toString()

        fun decode(json: String?): SavedNavigationState? = runCatching {
            val o = JSONObject(json ?: return null)
            SavedNavigationState(o.getString("itineraryID"), o.getBoolean("isReverse"), o.getInt("targetIndex"))
        }.getOrNull()
    }
}
