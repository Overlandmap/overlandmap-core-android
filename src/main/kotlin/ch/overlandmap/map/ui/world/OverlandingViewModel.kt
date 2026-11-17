package ch.overlandmap.map.ui.world

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.data.GeneralAssetState
import ch.overlandmap.map.data.InstalledAssetVersion
import ch.overlandmap.map.data.routes.TripPlanner
import ch.overlandmap.map.data.routes.TripPlannerState
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.UniversalLink
import ch.overlandmap.map.planning.RoutePlanner
import ch.overlandmap.map.planning.TripLeg
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
import java.util.UUID

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

    // The map's colour scheme and the climate choices are not remembered between launches (as in Flutter).
    private val _scheme = MutableStateFlow(OverlandMapScheme.OVERLANDING)
    val scheme: StateFlow<OverlandMapScheme> = _scheme.asStateFlow()
    private val _climateKind = MutableStateFlow(ClimateKind.TEMPERATURE)
    val climateKind: StateFlow<ClimateKind> = _climateKind.asStateFlow()
    private val _climateMonth = MutableStateFlow(1)
    val climateMonth: StateFlow<Int> = _climateMonth.asStateFlow()
    // ── Routes and trip planning ────────────────────────────────────────────

    /** Trips, the routing graph and every edit on them. */
    val planner: TripPlanner = app.tripPlanner
    val plannerState: StateFlow<TripPlannerState> = planner.state

    /** Whether the device has a network: planning needs it for the geometry of unseen segments. */
    val isOnline: StateFlow<Boolean> = app.networkMonitor.isConnected

    /** The route graph is on the device. */
    val plannerDataInstalled: StateFlow<Boolean> = app.routeDataManager.plannerDataInstalled

    /** A text field of the planner has focus: a tap on the map only dismisses the keyboard. */
    private val _textEditing = MutableStateFlow(false)
    val textEditing: StateFlow<Boolean> = _textEditing.asStateFlow()
    fun setTextEditing(editing: Boolean) { _textEditing.value = editing }

    private val _showRoutes = MutableStateFlow(prefs.overlandingBooleanNow("routesVisible", true))
    val showRoutes: StateFlow<Boolean> = _showRoutes.asStateFlow()

    private val _showTrips = MutableStateFlow(prefs.overlandingBooleanNow("tripsVisible", true))
    val showTrips: StateFlow<Boolean> = _showTrips.asStateFlow()

    private val _planningBoxExpanded = MutableStateFlow(prefs.overlandingBooleanNow("planningBoxExpanded", true))
    val planningBoxExpanded: StateFlow<Boolean> = _planningBoxExpanded.asStateFlow()

    /** Planning mode: taps on the map place flags on the active trip. */
    private val _planning = MutableStateFlow(false)
    val planning: StateFlow<Boolean> = _planning.asStateFlow()

    /** The route segment tapped on the map, shown in the detail pane. */
    private val _routeSelection = MutableStateFlow<RouteSegmentSelection?>(null)
    val routeSelection: StateFlow<RouteSegmentSelection?> = _routeSelection.asStateFlow()

    /** The trip tapped on the map in normal mode, shown in the detail pane. */
    private val _tripDetailId = MutableStateFlow<String?>(null)
    val tripDetailId: StateFlow<String?> = _tripDetailId.asStateFlow()

    /**
     * A leg's country or ferry / shipping segment is shown in the pane over the trip or the planner
     * (opened with the arrow of the leg); the pane then offers a way back.
     */
    private val _viewingLeg = MutableStateFlow(false)
    val viewingLeg: StateFlow<Boolean> = _viewingLeg.asStateFlow()

    /** The leg tapped in the planner list: highlighted on the map and zoomed to. */
    private val _highlightedLeg = MutableStateFlow<TripLeg?>(null)
    val highlightedLeg: StateFlow<TripLeg?> = _highlightedLeg.asStateFlow()

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
        // The route network switches from the online tiles to the installed ones.
        viewModelScope.launch {
            dataManager.routeTiles.installed.collect { if (it != null) _tilesRevision.update { n -> n + 1 } }
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
        // The saved trips, and the routing graph as soon as it is installed (or replaced).
        viewModelScope.launch { planner.loadTrips() }
        viewModelScope.launch { app.routeDataManager.installedVersion.collect { planner.loadGraphIfNeeded() } }
        // Country statuses and border posts decide which segments the planner may use.
        viewModelScope.launch { data.collect { if (!it.isEmpty()) planner.reconfigure() } }
        // The Overlanding assets are checked when the tab opens, as on iOS.
        dataManager.ensure()
    }

    /** Retries the downloads after a failure. */
    fun retryDownload() = dataManager.ensure()

    private fun OverlandData.isEmpty() = countries.isEmpty() && borders.isEmpty() && posts.isEmpty() && zones.isEmpty()

    // ── Selection and pane ──────────────────────────────────────────────────

    /** A tap on the map, a search pick or a link; clears the drill-down, as on iOS. */
    fun select(selection: OverlandingSelection?) {
        _viewingLeg.value = false
        _selection.value = selection
        _panePath.value = emptyList()
        savePanePath()
        persistSelection(selection)
        if (selection != null) {
            _routeSelection.value = null
            _tripDetailId.value = null
        }
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
        clearRouteDetails()
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

    fun setScheme(scheme: OverlandMapScheme) { _scheme.value = scheme }
    fun setClimateKind(kind: ClimateKind) { _climateKind.value = kind }
    fun setClimateMonth(month: Int) { _climateMonth.value = month.coerceIn(1, 12) }
    fun setLegendExpanded(expanded: Boolean) {
        _legendExpanded.value = expanded
        viewModelScope.launch { prefs.setOverlandingBoolean("legendExpanded", expanded) }
    }

    fun setShowRoutes(visible: Boolean) {
        _showRoutes.value = visible
        viewModelScope.launch { prefs.setOverlandingBoolean("routesVisible", visible) }
        if (!visible) _routeSelection.value = null
    }

    fun setShowTrips(visible: Boolean) {
        _showTrips.value = visible
        viewModelScope.launch { prefs.setOverlandingBoolean("tripsVisible", visible) }
        if (!visible) _tripDetailId.value = null
    }

    fun setPlanningBoxExpanded(expanded: Boolean) {
        _planningBoxExpanded.value = expanded
        viewModelScope.launch { prefs.setOverlandingBoolean("planningBoxExpanded", expanded) }
    }

    fun saveCamera(camera: OverlandingCamera) {
        viewModelScope.launch { prefs.setOverlandingString("camera", camera.encoded()) }
    }

    // ── Routes and planning ─────────────────────────────────────────────────

    /** Leaves the segment or trip detail for the pane's own content. */
    fun clearRouteDetails() {
        _viewingLeg.value = false
        _routeSelection.value = null
        _tripDetailId.value = null
    }

    /** A route segment was tapped on the map. */
    fun selectSegment(segment: RouteSegmentSelection) {
        _viewingLeg.value = false
        _panePath.value = emptyList()
        savePanePath()
        _tripDetailId.value = null
        _routeSelection.value = segment
    }

    /** A trip was tapped on the map in normal mode. */
    fun selectTrip(id: String) {
        _viewingLeg.value = false
        _panePath.value = emptyList()
        savePanePath()
        _routeSelection.value = null
        planner.select(id)
        _tripDetailId.value = id
    }

    /**
     * Shows what a leg of the trip is in the pane: its country for a land leg, its segment for a
     * ferry or shipping one. The planning mode and the trip stay as they are underneath.
     */
    fun showLeg(leg: TripLeg) {
        if (leg.mode == ch.overlandmap.map.planning.RouteMode.LAND) {
            val country = leg.countries.firstOrNull()?.let { data.value.country(it) } ?: return
            _routeSelection.value = null
            _selection.value = OverlandingSelection.OfCountry(country.adm0A3 ?: country.documentId)
        } else {
            val segment = leg.segmentIds.firstOrNull()?.let { planner.graph?.segments?.get(it) } ?: return
            _selection.value = null
            _routeSelection.value = RouteSegmentSelection(
                id = segment.id,
                name = segment.name,
                mode = segment.effectiveMode,
                countries = segment.countries.orEmpty(),
                overlanding = segment.overlanding,
                lengthKilometers = segment.lengthMeters / 1000.0,
            )
        }
        _panePath.value = emptyList()
        savePanePath()
        _viewingLeg.value = true
    }

    /** Back from a leg's pane to the planner or the trip. */
    fun closeLeg() {
        _viewingLeg.value = false
        _routeSelection.value = null
        _selection.value = null
        persistSelection(null)
        _panePath.value = emptyList()
        savePanePath()
    }

    fun startPlanning() {
        _viewingLeg.value = false
        _selection.value = null
        persistSelection(null)
        clearRouteDetails()
        _panePath.value = emptyList()
        savePanePath()
        setShowRoutes(true)
        setShowTrips(true)
        // Segment geometry is read from Firestore, so check the connection now; the planner pane
        // shows a warning while there is none.
        app.networkMonitor.refresh()
        _planning.value = true
    }

    fun stopPlanning() {
        _textEditing.value = false
        _highlightedLeg.value = null
        _planning.value = false
    }

    /** A tap on a leg of the planner list highlights it, a second tap clears the highlight. */
    fun selectLeg(leg: TripLeg) {
        if (_highlightedLeg.value?.id == leg.id) {
            _highlightedLeg.value = null
            return
        }
        _highlightedLeg.value = leg
        planner.bounds(leg.segmentIds)?.let(::zoomTo)
    }

    /**
     * A tap in planning mode: on the trip's own line it inserts a flag there, elsewhere it appends
     * one. [nameOf] gives a new trip its default name from its number.
     */
    fun planTap(tap: PlanTap, nameOf: (Int) -> String) {
        val index = if (tap.onTrip) planner.activeTrip?.segmentIds?.indexOf(tap.segmentId)?.takeIf { it >= 0 } else null
        val edit = RoutePlanner.WaypointEdit(
            kind = if (index == null) RoutePlanner.WaypointEdit.Kind.APPEND else RoutePlanner.WaypointEdit.Kind.INSERT,
            waypointId = UUID.randomUUID().toString(),
            coordinate = tap.coordinate,
            segmentId = tap.segmentId,
            segmentIndex = index,
        )
        planner.apply(edit, nameOf(planner.state.value.trips.size + 1))
    }

    /** Inserts a flag on the trip's line for a drag that starts there; returns its id. */
    fun insertFlag(tap: PlanTap, nameOf: (Int) -> String): String? {
        val index = planner.activeTrip?.segmentIds?.indexOf(tap.segmentId)?.takeIf { it >= 0 } ?: return null
        val id = UUID.randomUUID().toString()
        val edit = RoutePlanner.WaypointEdit(
            kind = RoutePlanner.WaypointEdit.Kind.INSERT,
            waypointId = id,
            coordinate = tap.coordinate,
            segmentId = tap.segmentId,
            segmentIndex = index,
        )
        val result = planner.apply(edit, nameOf(planner.state.value.trips.size + 1))
        return if (result == ch.overlandmap.map.data.routes.EditResult.Applied) id else null
    }

    /** Normal mode: a long press on a route starts a new trip with its first flag there. */
    fun startTrip(tap: PlanTap, nameOf: (Int) -> String) {
        startPlanning()
        if (planner.graph == null) return
        planner.newTrip(nameOf(planner.state.value.trips.size + 1))
        planTap(tap, nameOf)
    }

    fun moveFlag(flagId: String, tap: PlanTap, nameOf: (Int) -> String) {
        val edit = RoutePlanner.WaypointEdit(
            kind = RoutePlanner.WaypointEdit.Kind.MOVE,
            waypointId = flagId,
            coordinate = tap.coordinate,
            segmentId = tap.segmentId,
        )
        if (planner.apply(edit, nameOf(planner.state.value.trips.size + 1)) != ch.overlandmap.map.data.routes.EditResult.Applied) {
            planner.cancelPreview()
        }
    }

    fun previewFlag(flagId: String, tap: PlanTap) {
        planner.previewEdit(
            RoutePlanner.WaypointEdit(
                kind = RoutePlanner.WaypointEdit.Kind.MOVE,
                waypointId = flagId,
                coordinate = tap.coordinate,
                segmentId = tap.segmentId,
            )
        )
    }

    /** A border post listed in a trip's breakdown: shown on the map, with its details in the pane. */
    fun openBorderPostOfTrip(post: BorderPost) {
        select(OverlandingSelection.OfBorderPost(post.documentId))
        post.coordinate()?.let(::zoomToPoint)
    }

    /** Downloads the route graph again when it is missing (the panel's button). */
    fun downloadRouteGraph() = app.routeDataManager.ensure()

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
