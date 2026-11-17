package ch.overlandmap.map.data.routes

import android.util.Log
import ch.overlandmap.map.data.InstalledAssetVersion
import ch.overlandmap.map.data.local.TripDao
import ch.overlandmap.map.data.local.WorldDao
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.planning.BlockedCountryReason
import ch.overlandmap.map.planning.PlannedTrip
import ch.overlandmap.map.planning.PlanWaypoint
import ch.overlandmap.map.planning.RouteFragment
import ch.overlandmap.map.planning.RouteGeometryBuilder
import ch.overlandmap.map.planning.RouteGraph
import ch.overlandmap.map.planning.RoutePlanner
import ch.overlandmap.map.planning.RoutePoint
import ch.overlandmap.map.planning.RoutingRestrictions
import ch.overlandmap.map.planning.TripSummary
import ch.overlandmap.map.planning.TripSummaryBuilder
import ch.overlandmap.map.planning.routeMeters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/** What the map draws for a trip. */
data class TripDisplay(
    /**
     * Middle segments, highlighted in the map's own route tiles by ID. Only the first and last
     * segment need a polyline: they are drawn from [fragments], cut at the first and last flag.
     * Until those are available both are drawn whole from the tiles instead.
     */
    val fullSegmentIds: List<String>,
    val fragments: List<RouteFragment>,
)

/** A message the planner panel shows; the UI picks the text. */
sealed interface PlanMessage {
    data object NoConnection : PlanMessage
    data object GeometryUnavailable : PlanMessage
    data class Failure(val text: String) : PlanMessage
}

sealed interface EditResult {
    data object Applied : EditResult

    /** The segment belongs to a country the restrictions exclude; [TripPlanner.confirmBlocked] lifts it. */
    data class Blocked(val reason: BlockedCountryReason) : EditResult
    data object NoConnection : EditResult
    data object Unavailable : EditResult
}

/** Everything the planner UI observes. */
data class TripPlannerState(
    val trips: List<PlannedTrip> = emptyList(),
    val activeId: String? = null,
    val graphLoaded: Boolean = false,
    val loadingGraph: Boolean = false,
    val message: PlanMessage? = null,
    val canUndo: Boolean = false,
    /** What the map draws for each visible trip. */
    val displays: Map<String, TripDisplay> = emptyMap(),
    /**
     * While a flag is dragged: what the active trip would look like if the flag were dropped now.
     * Replaces the active trip's display on the map.
     */
    val previewDisplay: TripDisplay? = null,
    val restrictions: RoutingRestrictions = RoutingRestrictions.DEFAULT,
    val blocked: BlockedCountryReason? = null,
    /** Kilometres covered in a day by the trips that have no value of their own. */
    val defaultKmPerDay: Double = PlannedTrip.DEFAULT_KM_PER_DAY,
    /** Bumped when polylines arrive, so summaries (which depend on them) refresh. */
    val geometryEpoch: Int = 0,
) {
    val activeTrip: PlannedTrip? get() = trips.firstOrNull { it.id == activeId }

    /** Kilometres per day for [trip]: its own, else the default. */
    fun kmPerDay(trip: PlannedTrip): Double = trip.kmPerDay?.takeIf { it > 0 } ?: defaultKmPerDay
}

/**
 * Planning mode: the user's trips, the routing graph, and every edit on them (iOS `TripPlanner`).
 *
 * The algorithm itself lives in [RoutePlanner]; this class adds persistence in the `trip` table,
 * undo, and the routing restrictions, like `useRoutePlanner` in the web app.
 *
 * It is driven from the main thread (the same thread-confined design as iOS's `@MainActor`):
 * [scope] must run on it. Routing is cheap on the 1000-segment graph; the graph is parsed, the
 * database read and the polylines fetched off the main thread.
 */
class TripPlanner(
    private val dao: TripDao,
    private val world: WorldDao,
    private val data: RouteDataManager,
    private val geometry: RouteGeometryStore,
    private val scope: CoroutineScope,
    defaultKmPerDay: Double = PlannedTrip.DEFAULT_KM_PER_DAY,
    private val saveDefaultKmPerDay: (Double) -> Unit = {},
) {
    private class Snapshot(val trips: List<PlannedTrip>, val activeId: String?)
    private data class SummaryKey(val segmentIds: List<String>, val waypoints: List<PlanWaypoint>, val geometryEpoch: Int)

    private val _state = MutableStateFlow(TripPlannerState(defaultKmPerDay = defaultKmPerDay))
    val state: StateFlow<TripPlannerState> = _state.asStateFlow()

    /** The loaded routing graph, or null while it is not installed or parsed. */
    var graph: RouteGraph? = null
        private set

    private var loadedVersion: InstalledAssetVersion? = null
    private var tripsLoaded = false
    private val history = ArrayDeque<Snapshot>()
    private var pendingEdit: RoutePlanner.WaypointEdit? = null
    private var pendingName: String = ""
    private val summaries = HashMap<String, Pair<SummaryKey, TripSummary>>()

    /** Database writes run one after the other, in the order the edits were made. */
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch(Dispatchers.IO) {
            for (write in writes) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Could not save trip: ${e.localizedMessage}")
                    _state.update { it.copy(message = PlanMessage.Failure(e.localizedMessage ?: "Could not save trip")) }
                }
            }
        }
    }

    val activeTrip: PlannedTrip? get() = _state.value.activeTrip

    // ── Loading ─────────────────────────────────────────────────────────────

    /** Reads the saved trips the first time; afterwards the in-memory trips are authoritative. */
    suspend fun loadTrips() {
        if (tripsLoaded) return
        val stored = try {
            dao.allTrips()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not read trips: ${e.localizedMessage}")
            emptyList()
        }
        tripsLoaded = true
        _state.update { current ->
            // A trip planned while the read was running stays.
            val merged = stored.filter { s -> current.trips.none { it.id == s.id } } + current.trips
            current.copy(
                trips = merged,
                activeId = current.activeId?.takeIf { id -> merged.any { it.id == id } } ?: merged.firstOrNull()?.id,
            )
        }
        refreshDisplays()
    }

    /** Parses the routing graph once the planner file is installed (and again when it is replaced). */
    suspend fun loadGraphIfNeeded() {
        data.refreshState()
        if (!data.plannerDataInstalled.value || _state.value.loadingGraph) return
        val version = data.installedVersion.value
        if (graph != null && loadedVersion == version) return
        _state.update { it.copy(loadingGraph = true, message = null) }
        try {
            val loaded = data.loadGraph()
            configure(loaded)
            loaded.geometryProvider = { geometry.peek(it.id) }
            graph = loaded
            loadedVersion = version
            summaries.clear()
            _state.update { it.copy(graphLoaded = true, geometryEpoch = it.geometryEpoch + 1) }
            refreshDisplays()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Could not load the route graph: ${e.localizedMessage}")
            _state.update { it.copy(message = PlanMessage.Failure(e.localizedMessage ?: "Could not load the route graph")) }
        } finally {
            _state.update { it.copy(loadingGraph = false) }
        }
    }

    /** Reads the country statuses and border posts again, e.g. after the Overlanding dataset changed. */
    suspend fun reconfigure() {
        graph?.let { configure(it) }
    }

    /** Fills in the country statuses and closed border posts from the local database. */
    private suspend fun configure(graph: RouteGraph) {
        val statuses = HashMap<String, Int>()
        for (country in world._allCountries()) {
            val status = country.overlanding ?: continue
            statuses[country.documentId.uppercase()] = status
        }
        graph.countryStatuses = statuses
        val openByPost = HashMap<String, Int>()
        for (post in world._allBorderPosts()) post.isOpen?.let { openByPost[post.documentId] = it }
        graph.closedBorderNodes = graph.nodes.filterTo(HashSet()) {
            RouteGraph.borderPostBlocksRouting(openByPost[graph.borderPostId(it)])
        }
    }

    // ── Trips ───────────────────────────────────────────────────────────────

    fun newTrip(name: String): PlannedTrip {
        recordHistory()
        val trip = PlannedTrip(id = UUID.randomUUID().toString(), name = name)
        _state.update { it.copy(trips = it.trips + trip, activeId = trip.id) }
        save(trip)
        return trip
    }

    fun select(id: String) {
        if (_state.value.trips.none { it.id == id }) return
        _state.update { it.copy(activeId = id, blocked = null, message = null) }
    }

    fun deleteTrip(id: String) {
        if (_state.value.trips.none { it.id == id }) return
        recordHistory()
        _state.update { current ->
            val remaining = current.trips.filter { it.id != id }
            current.copy(
                trips = remaining,
                activeId = if (current.activeId == id) remaining.firstOrNull()?.id else current.activeId,
            )
        }
        writes.trySend { dao.deleteTrip(id) }
        changed()
    }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name) }

    fun setColor(id: String, color: String) {
        if (!PlannedTrip.isValidColor(color)) return
        update(id) { it.copy(color = color.lowercase()) }
    }

    /** The day the trip starts; null clears it. */
    fun setStartDate(id: String, date: java.time.LocalDate?) = update(id) { it.copy(startDate = date) }

    /** The kilometres per day of every trip that has no value of its own. */
    fun setDefaultKmPerDay(kilometres: Double) {
        if (!kilometres.isFinite() || kilometres <= 0) return
        _state.update { it.copy(defaultKmPerDay = kilometres) }
        saveDefaultKmPerDay(kilometres)
    }

    /** Kilometres covered in a day on this trip; the days of the breakdown follow. */
    fun setKmPerDay(id: String, kilometres: Double) {
        if (!kilometres.isFinite() || kilometres <= 0) return
        update(id) { it.copy(kmPerDay = kilometres) }
    }

    fun setVisible(id: String, visible: Boolean) {
        update(id) { it.copy(visible = visible) }
        refreshDisplays()
    }

    private fun update(id: String, change: (PlannedTrip) -> PlannedTrip) {
        val current = _state.value.trips.firstOrNull { it.id == id } ?: return
        val updated = change(current)
        _state.update { s -> s.copy(trips = s.trips.map { if (it.id == id) updated else it }) }
        save(updated)
    }

    // ── Waypoints ───────────────────────────────────────────────────────────

    /**
     * Applies a flag edit on the active trip, creating a trip named [newTripName] if there is none.
     *
     * Never waits for the network: it routes with the polylines already on the device. If the
     * flags' polylines are missing they are fetched in the background and the trip is refined once
     * they arrive (see [fetchAndRefine]).
     */
    fun apply(edit: RoutePlanner.WaypointEdit, newTripName: String): EditResult {
        val graph = graph ?: return EditResult.Unavailable
        val segment = graph.segments[edit.segmentId] ?: return EditResult.Unavailable
        val current = _state.value
        graph.blockedReason(segment, current.restrictions)?.let { reason ->
            pendingEdit = edit
            pendingName = newTripName
            _state.update { it.copy(blocked = reason) }
            return EditResult.Blocked(reason)
        }
        pendingEdit = null
        _state.update { it.copy(blocked = null, message = null) }

        val existing = current.activeTrip
        val trip = existing ?: PlannedTrip(id = UUID.randomUUID().toString(), name = newTripName)
        if (!RoutePlanner.isConnected(trip, graph)) return EditResult.Unavailable
        val needed = listOf(edit.segmentId) + (trip.waypoints ?: emptyList()).map { it.segmentId }
        val missing = needed.filter { geometry.peek(it) == null }
        val edited = RoutePlanner.edit(trip, edit, graph, current.restrictions)
        if (edited == null) {
            _state.update { it.copy(message = PlanMessage.NoConnection) }
            return EditResult.NoConnection
        }
        recordHistory()
        _state.update { s ->
            if (existing == null) s.copy(trips = s.trips + edited, activeId = edited.id)
            else s.copy(trips = s.trips.map { if (it.id == edited.id) edited else it })
        }
        save(edited)
        changed()
        if (missing.isNotEmpty()) fetchAndRefine(edited.id, needed)
        return EditResult.Applied
    }

    /**
     * Fetches polylines in the background. Once they are there the trip is routed again with exact
     * positions along the first and last segment, like the web app's endpoint optimisation, and its
     * display is redrawn.
     */
    private fun fetchAndRefine(tripId: String, ids: List<String>) {
        scope.launch {
            val failed = geometry.ensure(ids)
            _state.update {
                it.copy(
                    geometryEpoch = it.geometryEpoch + 1,
                    message = if (failed.isNotEmpty()) PlanMessage.GeometryUnavailable else it.message,
                )
            }
            refine(tripId)
        }
    }

    private fun refine(tripId: String) {
        val graph = graph
        val current = _state.value
        val trip = current.trips.firstOrNull { it.id == tripId }
        val flags = trip?.waypoints
        if (graph == null || trip == null || flags.isNullOrEmpty()) {
            refreshDisplays(prefetch = false)
            return
        }
        val points = flags.toMutableList()
        points[0] = points[0].copy(direction = null)
        points[points.size - 1] = points[points.size - 1].copy(direction = null)
        val rebuilt = RoutePlanner.build(trip, points, graph, current.restrictions)
        if (rebuilt != null &&
            (rebuilt.segmentIds != trip.segmentIds || rebuilt.waypoints != trip.waypoints ||
                rebuilt.distanceMeters != trip.distanceMeters)
        ) {
            _state.update { s -> s.copy(trips = s.trips.map { if (it.id == tripId) rebuilt else it }) }
            save(rebuilt)
        }
        changed(prefetch = false)
    }

    /** Lifts the restriction that excluded the last rejected edit and applies it again. */
    fun confirmBlocked(): EditResult? {
        val current = _state.value
        val reason = current.blocked ?: return null
        val edit = pendingEdit ?: return null
        val relaxed = when (reason) {
            BlockedCountryReason.RESTRICTED -> current.restrictions.copy(avoidRestricted = false)
            BlockedCountryReason.UNSAFE, BlockedCountryReason.FORBIDDEN -> current.restrictions.copy(avoidUnsafe = false)
        }
        // Set directly: the edit that follows replans the trip with the relaxed restrictions.
        _state.update { it.copy(restrictions = relaxed, blocked = null) }
        return apply(edit, pendingName)
    }

    fun dismissBlocked() {
        pendingEdit = null
        _state.update { it.copy(blocked = null) }
    }

    fun removeWaypoint(id: String) {
        val graph = graph ?: return
        val current = _state.value
        val trip = current.activeTrip ?: return
        // Removing a flag must not fail because of the routing options: a saved trip can hold flags
        // in a country that the options (which are not saved) exclude again after a restart. If no
        // route exists under the options, the remaining flags are connected without them.
        val edited = RoutePlanner.removeWaypoint(trip, id, graph, current.restrictions)
            ?: RoutePlanner.removeWaypoint(trip, id, graph, RoutingRestrictions(false, false, false))
        if (edited == null) {
            Log.w(TAG, "Could not remove waypoint $id of trip ${trip.id}")
            _state.update { it.copy(message = PlanMessage.NoConnection) }
            return
        }
        _state.update { it.copy(message = null) }
        replace(edited)
    }

    fun trimSegment(end: RoutePlanner.TripEnd) {
        val graph = graph ?: return
        val trip = activeTrip ?: return
        replace(RoutePlanner.trimSegment(trip, end, graph) ?: return)
    }

    private fun replace(trip: PlannedTrip) {
        if (_state.value.trips.none { it.id == trip.id }) return
        recordHistory()
        _state.update { s -> s.copy(trips = s.trips.map { if (it.id == trip.id) trip else it }) }
        save(trip)
        changed()
    }

    // ── Live preview ────────────────────────────────────────────────────────

    /**
     * Recomputes the active trip as if [edit] were applied and shows it on the map, without
     * changing the trip. Called continuously while a flag is dragged, so it only does work that is
     * cheap: a routing search, and the cut of the two end segments. The middle of the trip is drawn
     * by the map from its own tiles.
     */
    fun previewEdit(edit: RoutePlanner.WaypointEdit) {
        val graph = graph ?: return
        val current = _state.value
        val trip = current.activeTrip ?: return
        val segment = graph.segments[edit.segmentId] ?: return
        if (graph.blockedReason(segment, current.restrictions) != null) return
        // Polylines that are not on the device are fetched for the next frames; this one proceeds
        // without them.
        val missing = (listOf(edit.segmentId) + (trip.waypoints ?: emptyList()).map { it.segmentId })
            .filter { geometry.peek(it) == null }
        if (missing.isNotEmpty()) scope.launch { geometry.ensure(missing) }
        val edited = RoutePlanner.edit(trip, edit, graph, current.restrictions) ?: return
        _state.update { it.copy(previewDisplay = display(edited, graph)) }
    }

    /** Drops the preview, e.g. when the flag is released where nothing can be edited. */
    fun cancelPreview() {
        if (_state.value.previewDisplay != null) _state.update { it.copy(previewDisplay = null) }
    }

    // ── Restrictions ────────────────────────────────────────────────────────

    fun setRestrictions(value: RoutingRestrictions) {
        if (value == _state.value.restrictions) return
        pendingEdit = null
        _state.update { it.copy(restrictions = value, blocked = null, message = null) }
        replanForRestrictions()
    }

    private fun replanForRestrictions() {
        val graph = graph ?: return
        val current = _state.value
        val trip = current.activeTrip?.takeIf { it.segmentIds.isNotEmpty() } ?: return
        if (RoutePlanner.isAllowed(trip, graph, current.restrictions)) return
        val planned = RoutePlanner.replan(trip, graph, current.restrictions) ?: return
        _state.update { s -> s.copy(trips = s.trips.map { if (it.id == trip.id) planned else it }) }
        save(planned)
        changed()
    }

    /** Whether the active trip crosses something the current restrictions exclude. */
    fun activeTripConflicts(): Boolean {
        val graph = graph ?: return false
        val current = _state.value
        val trip = current.activeTrip?.takeIf { it.segmentIds.isNotEmpty() } ?: return false
        return !RoutePlanner.isAllowed(trip, graph, current.restrictions)
    }

    // ── Undo ────────────────────────────────────────────────────────────────

    private fun recordHistory() {
        val current = _state.value
        history.addLast(Snapshot(current.trips, current.activeId))
        if (history.size > MAX_HISTORY) history.removeFirst()
        _state.update { it.copy(canUndo = true) }
    }

    fun undo() {
        val snapshot = history.removeLastOrNull() ?: return
        val kept = snapshot.trips.mapTo(HashSet()) { it.id }
        for (trip in _state.value.trips) if (trip.id !in kept) writes.trySend { dao.deleteTrip(trip.id) }
        _state.update {
            it.copy(
                trips = snapshot.trips,
                activeId = snapshot.activeId,
                canUndo = history.isNotEmpty(),
                blocked = null,
                message = null,
            )
        }
        snapshot.trips.forEach(::save)
        changed()
    }

    // ── Queries for the UI ──────────────────────────────────────────────────

    /**
     * The visible trip drawn on [segmentId] (a middle segment), if any; the last one wins, as in the
     * colours on the map.
     */
    fun tripIdForSegment(segmentId: String): String? {
        val current = _state.value
        return current.trips.lastOrNull { trip ->
            trip.isVisible && current.displays[trip.id]?.fullSegmentIds?.contains(segmentId) == true
        }?.id
    }

    /** The box around some segments: their polylines where stored, otherwise their end points. */
    fun bounds(segmentIds: List<String>): GeoBounds? {
        val graph = graph ?: return null
        var latMin = 90.0
        var latMax = -90.0
        var lonMin = 180.0
        var lonMax = -180.0
        var found = false
        fun add(point: RoutePoint) {
            latMin = minOf(latMin, point.latitude)
            latMax = maxOf(latMax, point.latitude)
            lonMin = minOf(lonMin, point.longitude)
            lonMax = maxOf(lonMax, point.longitude)
            found = true
        }
        for (id in segmentIds) {
            val polyline = geometry.peek(id)
            if (polyline != null) {
                polyline.coordinates.forEach(::add)
            } else {
                graph.segments[id]?.let { segment ->
                    segment.startCoordinate?.let(::add)
                    segment.endCoordinate?.let(::add)
                }
            }
        }
        return if (found) GeoBounds(latMin = latMin, latMax = latMax, lonMin = lonMin, lonMax = lonMax) else null
    }

    /**
     * The breakdown of a trip, with border posts that split the legs. Cached: views ask for it on
     * every refresh.
     */
    fun summary(trip: PlannedTrip): TripSummary? {
        val graph = graph ?: return null
        val key = SummaryKey(trip.segmentIds, trip.waypoints ?: emptyList(), _state.value.geometryEpoch)
        summaries[trip.id]?.let { (cachedKey, cached) -> if (cachedKey == key) return cached }
        val lengths = RouteGeometryBuilder.trim(trip, graph)?.lengths
        val summary = TripSummaryBuilder.summary(trip, graph, lengths, graph.borderPostNodes.keys)
        summaries[trip.id] = key to summary
        return summary
    }

    // ── Persistence and geometry ────────────────────────────────────────────

    private fun save(trip: PlannedTrip) {
        writes.trySend { dao.saveTrip(trip) }
    }

    private fun changed(prefetch: Boolean = true) {
        refreshDisplays(prefetch)
    }

    /**
     * What the map needs to draw [trip]: the middle segments are drawn from the map's own tiles, so
     * only the first and last segment, cut at the flags, need a polyline.
     */
    private fun display(trip: PlannedTrip, graph: RouteGraph): TripDisplay {
        val ids = trip.segmentIds
        val trimmed = RouteGeometryBuilder.trim(trip, graph)
        logZeroLengthSegments(trip, graph, trimmed?.fragments)
        if (trimmed != null) {
            val middle = if (ids.size > 2) ids.subList(1, ids.size - 1) else emptyList()
            return TripDisplay(fullSegmentIds = middle.distinct(), fragments = trimmed.fragments)
        }
        return TripDisplay(fullSegmentIds = ids.distinct(), fragments = emptyList())
    }

    /** What was already logged, so a trip that is redrawn many times reports each problem once. */
    private val loggedDegenerate = HashSet<String>()

    /**
     * A segment with no length is drawn as a dot, and should not exist in the network. Logs the ID
     * of every such segment of [trip] (also those under 10 m), with what makes it so (tag `TripPlanner`, warning).
     */
    private fun logZeroLengthSegments(trip: PlannedTrip, graph: RouteGraph, fragments: List<RouteFragment>?) {
        fun report(key: String, message: () -> String) {
            if (loggedDegenerate.add("${trip.id}:$key")) Log.w(TAG, "Trip \"${trip.name}\" (${trip.id}): ${message()}")
        }
        for (id in trip.segmentIds.distinct()) {
            val segment = graph.segments[id]
            if (segment == null) {
                report("$id:missing") { "segment $id is not in the route graph" }
                continue
            }
            val reasons = ArrayList<String>()
            if (segment.lengthMeters <= 0.0) reasons += "length_m=${segment.lengthMeters}"
            else if (segment.lengthMeters < VERY_SHORT_METERS) reasons += "very short, length_m=${segment.lengthMeters}"
            if (segment.startNode == segment.endNode) reasons += "start and end node are both ${segment.startNode}"
            val start = segment.startCoordinate
            if (start != null && start == segment.endCoordinate) reasons += "start and end coordinates are both [${start.longitude}, ${start.latitude}]"
            geometry.peek(id)?.let { if (it.total <= 0.0) reasons += "its polyline is ${it.coordinates.size} identical points" }
            if (reasons.isNotEmpty()) {
                report("$id:zero") {
                    "zero-length segment $id (${segment.name ?: "unnamed"}, ${segment.mode ?: "land"}, " +
                        "${segment.countries?.joinToString("/") ?: "-"}): ${reasons.joinToString("; ")}"
                }
            }
        }
        // The first or last segment cut at a flag down to a single point.
        fragments?.forEach { fragment ->
            val length = fragment.coordinates.zipWithNext { a, b -> routeMeters(a, b) }.sum()
            if (fragment.coordinates.size > 1 && length < VERY_SHORT_METERS) {
                report("${fragment.segmentId}:short") {
                    "segment ${fragment.segmentId} is cut to ${"%.1f".format(length)} m at its flag"
                }
            }
            if (fragment.coordinates.size == 1) {
                report("${fragment.segmentId}:cut") {
                    "segment ${fragment.segmentId} is cut to a single point at its flag " +
                        "[${fragment.coordinates[0].longitude}, ${fragment.coordinates[0].latitude}]"
                }
            }
        }
    }

    /**
     * Recomputes what is drawn for the visible trips. Synchronous and cheap; end polylines that are
     * missing are fetched in the background and the display is refreshed.
     */
    private fun refreshDisplays(prefetch: Boolean = true) {
        val graph = graph
        if (graph == null) {
            _state.update { it.copy(previewDisplay = null, displays = emptyMap()) }
            return
        }
        val result = LinkedHashMap<String, TripDisplay>()
        val missing = ArrayList<String>()
        for (trip in _state.value.trips) {
            if (!trip.isVisible || trip.segmentIds.isEmpty()) continue
            val shown = display(trip, graph)
            result[trip.id] = shown
            if (shown.fragments.isEmpty() && trip.waypoints?.isNotEmpty() == true) {
                missing += trip.segmentIds[0]
                missing += trip.segmentIds[trip.segmentIds.size - 1]
            }
        }
        _state.update { it.copy(previewDisplay = null, displays = result) }
        if (!prefetch || missing.isEmpty()) return
        scope.launch {
            val failed = geometry.ensure(missing)
            if (failed.size < missing.toSet().size) {
                _state.update { it.copy(geometryEpoch = it.geometryEpoch + 1) }
                refreshDisplays(prefetch = false)
            }
        }
    }

    private companion object {
        const val TAG = "TripPlanner"
        const val MAX_HISTORY = 50
        const val VERY_SHORT_METERS = 10.0
    }
}
