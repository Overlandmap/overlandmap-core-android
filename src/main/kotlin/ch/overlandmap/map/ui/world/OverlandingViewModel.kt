package ch.overlandmap.map.ui.world

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.data.GeneralAssetState
import ch.overlandmap.map.data.InstalledAssetVersion
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.UniversalLink
import ch.overlandmap.map.ui.currentLanguage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State of the Overlanding tab (iOS `OverlandingScreen`): the dataset, the map selection, the
 * detail pane's drill-down path, focus requests for the map, and the preferences that persist
 * between launches (camera, selection, pane path, legend, border-post visibility, tab choices).
 */
class OverlandingViewModel(private val app: OverlandApp) : ViewModel() {

    private val prefs = app.userPreferences
    private val world = app.worldRepository
    private val dataManager = app.overlandDataManager

    val data: StateFlow<OverlandData> = combine(
        world.observeCountries(), world.observeBorders(), world.observeBorderPosts(), world.observeZones(),
    ) { countries, borders, posts, zones -> OverlandData(countries, borders, posts, zones) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, OverlandData())

    val advisoryTemplates: StateFlow<Map<String, String>> =
        app.globalSettingsRepository.advisoryUrlTemplates()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The download state of the dataset, for the "downloading…" placeholder. */
    val datasetState: StateFlow<GeneralAssetState> = dataManager.countries.state
    val tilesState: StateFlow<GeneralAssetState> = dataManager.borders.state
    val datasetVersion: StateFlow<InstalledAssetVersion?> = dataManager.countries.installed

    /** Changes when the tile archive is installed or replaced, so the map rebuilds its source. */
    private val _tilesRevision = MutableStateFlow(0)
    val tilesRevision: StateFlow<Int> = _tilesRevision.asStateFlow()

    private val _selection = MutableStateFlow(OverlandingSelection.decode(prefs.overlandingStringNow("selection")))
    val selection: StateFlow<OverlandingSelection?> = _selection.asStateFlow()

    private val _panePath = MutableStateFlow<List<PaneDestination>>(emptyList())
    val panePath: StateFlow<List<PaneDestination>> = _panePath.asStateFlow()

    val initialCamera: OverlandingCamera =
        OverlandingCamera.decode(prefs.overlandingStringNow("camera")) ?: OverlandingCamera.WORLD

    private val _showBorderPosts = MutableStateFlow(prefs.overlandingBooleanNow("borderPostsVisible", true))
    val showBorderPosts: StateFlow<Boolean> = _showBorderPosts.asStateFlow()

    private val _legendExpanded = MutableStateFlow(prefs.overlandingBooleanNow("legendExpanded", true))
    val legendExpanded: StateFlow<Boolean> = _legendExpanded.asStateFlow()

    /** A box the pane asked the map to frame; the map clears it once framed. */
    private val _zoomRequest = MutableStateFlow<GeoBounds?>(null)
    val zoomRequest: StateFlow<GeoBounds?> = _zoomRequest.asStateFlow()
    private val _pointZoomRequest = MutableStateFlow<Coordinate?>(null)
    val pointZoomRequest: StateFlow<Coordinate?> = _pointZoomRequest.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** The tab last used per object, remembered between launches. */
    private val _tabs = MutableStateFlow(decodeTabs(prefs.overlandingStringNow("tabs")))
    val tabs: StateFlow<Map<String, Int>> = _tabs.asStateFlow()

    /** The pane path is restored once the data first arrives. */
    private var paneRestored = false

    init {
        viewModelScope.launch {
            dataManager.borders.installed.collect { if (it != null) _tilesRevision.update { n -> n + 1 } }
        }
        // A link from elsewhere in the app (markup, search, universal link) asks for a selection.
        viewModelScope.launch {
            combine(app.overlandingLink, data) { link, d -> link to d }.collect { (link, d) ->
                if (link != null && !d.isEmpty()) {
                    app.overlandingLink.value = null
                    applyLink(link, d)
                }
            }
        }
        viewModelScope.launch {
            data.collect { d ->
                if (!paneRestored && !d.isEmpty()) {
                    paneRestored = true
                    restorePanePath(d)
                }
            }
        }
        viewModelScope.launch { app.overlandingReset.drop(1).collect { resetToDefault() } }
        // The Overlanding assets are checked when the tab opens, as on iOS.
        dataManager.ensure()
    }

    /** Retries the downloads after a failure. */
    fun retryDownload() = dataManager.ensure()

    private fun OverlandData.isEmpty() = countries.isEmpty() && borders.isEmpty() && posts.isEmpty() && zones.isEmpty()

    // ── Selection and pane ──────────────────────────────────────────────────

    /** A tap on the map, a search pick or a link; clears the drill-down, as on iOS. */
    fun select(selection: OverlandingSelection?) {
        _selection.value = selection
        _panePath.value = emptyList()
        savePanePath()
        persistSelection(selection)
        if (selection is OverlandingSelection.OfBorderPost && !_showBorderPosts.value) {
            // keep the pick visible
            setShowBorderPosts(true)
        }
    }

    fun openBorder(borderId: String, backTitle: String) {
        _panePath.update { it + PaneDestination.Border(borderId, backTitle) }
        savePanePath()
    }

    fun openPost(postId: String, borderId: String?, backTitle: String) {
        _panePath.update { it + PaneDestination.BorderPostOf(postId, borderId, backTitle) }
        savePanePath()
    }

    /** Back inside the pane; false when already at its root. */
    fun popPane(): Boolean {
        if (_panePath.value.isEmpty()) return false
        _panePath.update { it.dropLast(1) }
        savePanePath()
        return true
    }

    fun resetToDefault() {
        _panePath.value = emptyList()
        savePanePath()
        _selection.value = null
        persistSelection(null)
        _zoomRequest.value = null
        _pointZoomRequest.value = null
    }

    fun zoomTo(bounds: GeoBounds) { _zoomRequest.value = bounds }
    fun zoomToPoint(point: Coordinate) {
        setShowBorderPosts(true)
        _pointZoomRequest.value = point
    }
    fun zoomHandled() { _zoomRequest.value = null }
    fun pointZoomHandled() { _pointZoomRequest.value = null }

    fun setShowBorderPosts(visible: Boolean) {
        _showBorderPosts.value = visible
        viewModelScope.launch { prefs.setOverlandingBoolean("borderPostsVisible", visible) }
        // A hidden post can't stay selected.
        if (!visible && _selection.value is OverlandingSelection.OfBorderPost) select(null)
    }

    fun setLegendExpanded(expanded: Boolean) {
        _legendExpanded.value = expanded
        viewModelScope.launch { prefs.setOverlandingBoolean("legendExpanded", expanded) }
    }

    fun saveCamera(camera: OverlandingCamera) {
        viewModelScope.launch { prefs.setOverlandingString("camera", camera.encoded()) }
    }

    // ── Search ──────────────────────────────────────────────────────────────

    fun setSearching(on: Boolean) {
        _searching.value = on
        if (!on) _query.value = ""
    }

    fun setQuery(q: String) { _query.value = q }

    // ── Tabs ────────────────────────────────────────────────────────────────

    fun tabFor(key: String): Int = _tabs.value[key] ?: 0

    fun setTab(key: String, tab: Int) {
        _tabs.update { it + (key to tab) }
        viewModelScope.launch { prefs.setOverlandingString("tabs", encodeTabs(_tabs.value)) }
    }

    // ── Sharing / zoom targets ─────────────────────────────────────────────

    /** What the toolbar's zoom button frames for the current pane: bounds, a point, or nothing. */
    fun zoomTarget(d: OverlandData): ZoomTarget? {
        val top = _panePath.value.lastOrNull()
        if (top != null) return when (top) {
            is PaneDestination.Border -> d.border(top.borderId)?.boundingBox?.let { ZoomTarget.Bounds(it) }
            is PaneDestination.BorderPostOf -> d.post(top.postId)?.coordinate()?.let { ZoomTarget.Point(it) }
        }
        return when (val s = _selection.value) {
            is OverlandingSelection.OfCountry -> d.country(s.id)?.boundingBox?.let { ZoomTarget.Bounds(it) }
            is OverlandingSelection.OfBorder -> d.border(s.id)?.boundingBox?.let { ZoomTarget.Bounds(it) }
            is OverlandingSelection.OfZone -> d.zone(s.id)?.boundingBox?.let { ZoomTarget.Bounds(it) }
            is OverlandingSelection.OfBorderPost -> d.post(s.id)?.coordinate()?.let { ZoomTarget.Point(it) }
            null -> null
        }
    }

    /** The shareable universal link of what the pane shows. */
    fun shareUrl(d: OverlandData): String? {
        val top = _panePath.value.lastOrNull()
        val link: UniversalLink? = if (top != null) when (top) {
            is PaneDestination.Border -> UniversalLink.Border(top.borderId)
            is PaneDestination.BorderPostOf -> UniversalLink.BorderPost(top.postId)
        } else when (val s = _selection.value) {
            is OverlandingSelection.OfCountry -> UniversalLink.Country((d.country(s.id)?.adm0A3 ?: s.id))
            is OverlandingSelection.OfBorder -> UniversalLink.Border(s.id)
            is OverlandingSelection.OfBorderPost -> UniversalLink.BorderPost(s.id)
            is OverlandingSelection.OfZone -> UniversalLink.Zone(s.id)
            null -> null
        }
        return link?.url()
    }

    // ── Links ───────────────────────────────────────────────────────────────

    /** Opens [link] in the shared map/pane container and frames its object. */
    private fun applyLink(link: OverlandingSelection, d: OverlandData) {
        select(link)
        when (link) {
            is OverlandingSelection.OfCountry -> d.country(link.id)?.boundingBox?.let(::zoomTo)
            is OverlandingSelection.OfBorder -> d.border(link.id)?.boundingBox?.let(::zoomTo)
            is OverlandingSelection.OfZone -> d.zone(link.id)?.boundingBox?.let(::zoomTo)
            is OverlandingSelection.OfBorderPost -> d.post(link.id)?.coordinate()?.let(::zoomToPoint)
        }
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    private fun persistSelection(selection: OverlandingSelection?) {
        viewModelScope.launch { prefs.setOverlandingString("selection", selection?.encoded()) }
    }

    private fun savePanePath() {
        var borderId: String? = null
        var postId: String? = null
        _panePath.value.forEach {
            when (it) {
                is PaneDestination.Border -> borderId = it.borderId
                is PaneDestination.BorderPostOf -> postId = it.postId
            }
        }
        viewModelScope.launch {
            prefs.setOverlandingString("paneBorderID", borderId)
            prefs.setOverlandingString("paneBorderPostID", postId)
        }
    }

    private fun restorePanePath(d: OverlandData) {
        if (_panePath.value.isNotEmpty()) return
        val borderId = prefs.overlandingStringNow("paneBorderID") ?: return
        val border = d.border(borderId) ?: return
        val selected = _selection.value as? OverlandingSelection.OfCountry
        val lang = currentLanguage()
        val backTitle = selected?.let { d.country(it.id)?.name(lang) } ?: border.name
        val path = mutableListOf<PaneDestination>(PaneDestination.Border(borderId, backTitle))
        prefs.overlandingStringNow("paneBorderPostID")?.let { postId ->
            if (d.post(postId) != null) {
                path += PaneDestination.BorderPostOf(postId, borderId, d.borderTitle(border, lang, border.name))
            }
        }
        _panePath.value = path
    }

    private fun encodeTabs(map: Map<String, Int>) = map.entries.joinToString("\n") { "${it.key}=${it.value}" }

    private fun decodeTabs(stored: String?): Map<String, Int> =
        stored?.lines()?.mapNotNull { line ->
            val i = line.lastIndexOf('=')
            if (i <= 0) null else line.substring(0, i) to (line.substring(i + 1).toIntOrNull() ?: return@mapNotNull null)
        }?.toMap() ?: emptyMap()

    sealed interface ZoomTarget {
        data class Bounds(val bounds: GeoBounds) : ZoomTarget
        data class Point(val point: Coordinate) : ZoomTarget
    }
}

fun BorderPost.coordinate(): Coordinate? {
    val la = lat ?: return null
    val lo = lon ?: return null
    return Coordinate(la, lo)
}
