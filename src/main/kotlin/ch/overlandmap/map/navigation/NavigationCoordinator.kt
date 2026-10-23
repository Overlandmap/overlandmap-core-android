package ch.overlandmap.map.navigation

import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.tracking.LocationFix
import ch.overlandmap.map.tracking.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

/** What is needed to resume a navigation after the app was killed (iOS `SavedNavigationState`). */
data class SavedNavigationState(val itineraryId: String, val isReverse: Boolean, val targetIndex: Int)

interface NavigationPersistence {
    fun load(): SavedNavigationState?
    fun save(state: SavedNavigationState)
    fun clear()
}

enum class NavigationMode {
    NONE,

    /** Following Mapbox driving directions to the itinerary's start. */
    ROAD_TO_START,

    /** Following the itinerary's steps. */
    ITINERARY,
}

/** Everything the navigation screen shows. Immutable; the coordinator publishes a new one per update. */
data class NavigationState(
    val isNavigating: Boolean = false,
    val mode: NavigationMode = NavigationMode.NONE,
    val itineraryId: String? = null,
    val itineraryTitle: String? = null,
    val language: String = "en",
    val isReverse: Boolean = false,
    val steps: List<ItineraryStep> = emptyList(),
    val currentTargetIndex: Int = 0,
    /** Distance to the target along the track (plus the gap to the track); in road mode, to the start. */
    val distanceToTargetMeters: Double? = null,
    val bearingToTargetDegrees: Double? = null,
    /** The red/green status. Only re-evaluated while moving. */
    val isOffTrack: Boolean = false,
    /** Fix-to-fix trend of the distance to the target (informational). */
    val isApproachingTarget: Boolean = true,
    val currentLocation: LocationFix? = null,
    /** Compass heading in degrees from true north, when the sensor reports one. */
    val heading: Float? = null,

    /** True while the device is travelling. Selects which algorithm runs. */
    val isMoving: Boolean = false,
    /** True while moving and the distance to the target keeps growing: the UI offers to reverse. */
    val isMovingAwayFromTarget: Boolean = false,
    /** The last known direction of travel (GPS course, else derived from the displacement). */
    val travelBearing: Double? = null,

    // Track guidance
    val trackCoordinates: List<Coordinate> = emptyList(),
    val trackBearingDegrees: Double? = null,
    /** The point on the track 100 m ahead of the user; the needle aims at it while moving. */
    val lookaheadPoint: Coordinate? = null,
    val crossTrackDistanceMeters: Double? = null,
    val crossTrackSide: CrossTrackSide = CrossTrackSide.NONE,
    val referenceTrackPoint: Coordinate? = null,
    /** The name of the step just reached; shown as a banner for a few seconds. */
    val recentlyReachedStepName: String? = null,

    // Road navigation to the start
    val roadRoute: RoadRoute? = null,
    val currentRoadStepIndex: Int = 0,
    val distanceToStartPointMeters: Double? = null,
    val distanceToCurrentRoadStepMeters: Double? = null,
    val isCalculatingRoadRoute: Boolean = false,
) {
    val currentTargetStep: ItineraryStep? get() = steps.getOrNull(currentTargetIndex)
    val currentRoadStep: RoadStep? get() = roadRoute?.steps?.getOrNull(currentRoadStepIndex)
    val canSelectPreviousStep: Boolean get() = currentTargetIndex > 0
    val canSelectNextStep: Boolean get() = currentTargetIndex < steps.size - 1

    /**
     * The direction the compass dial is relative to, in degrees.
     *
     * Moving: the direction of travel (GPS course), which stays right when the phone is mounted at
     * an angle, then the last travel bearing, the compass, and last the map's [cameraBearing].
     * Stopped: the compass, then the map's bearing.
     */
    fun deviceHeading(cameraBearing: Double? = null): Double? {
        val compass = heading?.toDouble()
        if (isMoving) return currentLocation?.courseDeg?.toDouble() ?: travelBearing ?: compass ?: cameraBearing
        return compass ?: cameraBearing
    }

    /**
     * The angle, in [-180, 180], from the device's direction to where the needle aims: 0 is straight
     * ahead, positive to the right. While moving in itinerary mode the needle follows the track
     * ahead (in the navigation direction); otherwise it points at the target step.
     */
    fun relativeBearingToTarget(cameraBearing: Double? = null): Double? {
        val target = (if (mode == NavigationMode.ITINERARY && isMoving) trackBearingDegrees else null)
            ?: bearingToTargetDegrees ?: return null
        val device = deviceHeading(cameraBearing) ?: return target
        return TrackNavigationMath.relativeBearing(target, device)
    }

    /** The point [relativeBearingToTarget] aims at: the look-ahead point on the track while moving, else the target step. */
    val needleTargetPoint: Coordinate?
        get() = if (mode == NavigationMode.ITINERARY && isMoving && trackBearingDegrees != null && lookaheadPoint != null) lookaheadPoint
        else currentTargetStep?.coordinate()
}

/**
 * Guides along an itinerary (iOS `NavigationCoordinator`). Two algorithms, depending on whether the
 * device is moving:
 *
 * **Stopped.** The needle points at the target step using the compass. The on/off-track status is
 * frozen until the device moves again, and the target only changes when the user arrives within 50 m.
 *
 * **Moving.** P1 = the closest position on the track. P2 = P1 shifted 50 m along the direction of
 * travel. P3 = the track position closest to P2. If P3 comes after P1 in track order (before it when
 * reversed) the direction is right (green), else wrong (red). The target is the first step after P3.
 *
 * When the distance to the target keeps growing while moving, [NavigationState.isMovingAwayFromTarget]
 * turns on so the UI can offer [reverseDirection]. The road-to-start mode (Mapbox Directions while the
 * itinerary is more than 5 km away) is independent of the above.
 */
class NavigationCoordinator(
    private val location: LocationSource,
    private val persistence: NavigationPersistence,
    private val scope: CoroutineScope,
    private val directions: DirectionsRouting = MapboxDirectionsService,
    /** Plays the arrival sound. */
    private val onTargetReached: (String) -> Unit = {},
    /** Called with true when navigation starts and false when it ends (heading sensor, foreground service). */
    private val onNavigatingChanged: (Boolean) -> Unit = {},
    /** Receives a line when the navigation ends, saying why. */
    private val log: (String) -> Unit = {},
    /** Verbose trace of the decisions (start, first fix, mode and target changes, road route). */
    private val debug: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(NavigationState())
    val state: StateFlow<NavigationState> = _state.asStateFlow()

    /** The working state. Algorithm steps assign it freely; [flush] publishes it once per update. */
    private var s = NavigationState()

    // Itinerary algorithm state
    private var navTrack: NavigationTrack? = null
    /** Track index of each step, in step order; null for steps without a coordinate. */
    private var stepTrackIndices: List<Double?> = emptyList()
    private var lastP1: NavigationTrack.Position? = null
    private var lastP3: NavigationTrack.Position? = null
    /** P3 − P1 in metres along the track from the last moving evaluation, so a direction flip can re-derive the status. */
    private var lastDirectionDelta: Double? = null
    /** A step picked with the previous/next arrows; honoured while it is still ahead of P3. */
    private var manualTargetIndex: Int? = null
    private var lastMotionFix: LocationFix? = null
    private var stoppedSinceMs: Long? = null
    private var courseAnchor: Coordinate? = null
    private var trendAnchorDistance: Double? = null
    private var lastDistanceToTarget: Double? = null
    private var closestDistance: Double? = null
    /**
     * True once the user has been [FINAL_ARM_M] away from the final step (or has gone past the step
     * before it). Until then reaching it doesn't end the navigation: starting next to the final step,
     * or at the start of a loop that ends where it began, would otherwise end it on the first fix.
     */
    private var finalArrivalArmed = false

    private var hasFoundInitialClosestStep = false
    private var mapboxToken: String? = null
    private var roadRouteJob: Job? = null
    private var reachedBannerJob: Job? = null

    private fun flush() {
        _state.value = s
    }

    val isNavigating: Boolean get() = s.isNavigating

    /**
     * Starts navigating [steps] (with the itinerary's [trackCoordinates] for guidance). A given
     * [initialTargetIndex] (a restored session) skips the closest-step search; [mapboxToken] null
     * means "not known yet" (see [updateMapboxToken]) and an empty one "no road directions".
     */
    fun startNavigation(
        itineraryId: String,
        itineraryTitle: String?,
        steps: List<ItineraryStep>,
        trackCoordinates: List<Coordinate>,
        reverse: Boolean,
        initialTargetIndex: Int? = null,
        initialLocation: LocationFix? = null,
        language: String = "en",
        mapboxToken: String? = null,
    ) {
        if (steps.isEmpty()) return
        roadRouteJob?.cancel()
        roadRouteJob = null
        reachedBannerJob?.cancel()
        this.mapboxToken = mapboxToken?.trim()
        val wasNavigating = s.isNavigating
        resetMotionState()
        val restored = initialTargetIndex?.takeIf { it in steps.indices }
        hasFoundInitialClosestStep = restored != null
        s = NavigationState(
            isNavigating = true,
            mode = NavigationMode.ITINERARY,
            itineraryId = itineraryId,
            itineraryTitle = itineraryTitle,
            language = language,
            isReverse = reverse,
            steps = steps,
            currentTargetIndex = restored ?: if (reverse) steps.lastIndex else 0,
            trackCoordinates = trackCoordinates,
            currentLocation = s.currentLocation,
            heading = s.heading,
        )
        rebuildNavigationTrack()
        debug(
            "start: itinerary=$itineraryId reverse=$reverse steps=${steps.size} (with position ${steps.count { it.coordinate() != null }}) " +
                "trackPoints=${trackCoordinates.size} navTrack=${navTrack?.points?.size ?: "none"} " +
                "target=${s.currentTargetIndex} restored=${restored != null} token=${when { mapboxToken == null -> "unknown"; mapboxToken.isEmpty() -> "none"; else -> "yes" }} " +
                "initialFix=${(initialLocation ?: location.lastFix ?: s.currentLocation) != null}"
        )
        flush()
        location.start(1f) { fix -> processLocation(fix) }
        if (!wasNavigating) onNavigatingChanged(true)
        (initialLocation ?: location.lastFix ?: s.currentLocation)?.let(::processLocation)
        persist()
    }

    /** Provides or replaces the Mapbox token, resuming a road route that was waiting for it. */
    fun updateMapboxToken(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return
        mapboxToken = trimmed
        // "Calculating" is also shown while the token is awaited, so look for an actual request.
        if (s.mode == NavigationMode.ROAD_TO_START && s.roadRoute == null && roadRouteJob?.isActive != true) {
            val here = s.currentLocation?.coordinate
            val target = s.currentTargetStep?.coordinate()
            if (here != null && target != null) {
                requestRoadRoute(here, target, trimmed)
                flush()
            }
        }
    }

    /** Injects the itinerary's track for guidance (when it was loaded after the start). */
    fun setTrackCoordinates(coordinates: List<Coordinate>) {
        s = s.copy(trackCoordinates = coordinates)
        rebuildNavigationTrack()
        val here = s.currentLocation
        if (s.isNavigating && s.mode == NavigationMode.ITINERARY && here != null) processItineraryLocation(here)
        flush()
    }

    fun updateHeading(degrees: Float?) {
        if (s.heading == degrees) return
        s = s.copy(heading = degrees)
        flush()
    }

    /** Ends the navigation and resets the state; [reason] only feeds the log. */
    fun stopNavigation(reason: String = "requested") {
        if (!s.isNavigating && s.mode == NavigationMode.NONE) return
        log("Navigation stopped: $reason (itinerary ${s.itineraryId ?: "-"}, target ${s.currentTargetIndex}, moving ${s.isMoving})")
        roadRouteJob?.cancel()
        roadRouteJob = null
        reachedBannerJob?.cancel()
        mapboxToken = null
        hasFoundInitialClosestStep = false
        location.stop()
        val wasNavigating = s.isNavigating
        resetMotionState()
        navTrack = null
        stepTrackIndices = emptyList()
        s = NavigationState(currentLocation = s.currentLocation, heading = s.heading)
        flush()
        persistence.clear()
        if (wasNavigating) onNavigatingChanged(false)
    }

    fun persist() {
        val id = s.itineraryId ?: return
        if (s.isNavigating) persistence.save(SavedNavigationState(id, s.isReverse, s.currentTargetIndex))
    }

    /** Leaves road directions for the bearing mode. */
    fun switchToItineraryNavigation() {
        if (!s.isNavigating) return
        leaveRoadMode()
        flush()
    }

    private fun leaveRoadMode() {
        debug("road mode left -> itinerary mode")
        roadRouteJob?.cancel()
        roadRouteJob = null
        closestDistance = null
        lastDistanceToTarget = null
        trendAnchorDistance = null
        s = s.copy(
            mode = NavigationMode.ITINERARY, roadRoute = null, currentRoadStepIndex = 0,
            distanceToCurrentRoadStepMeters = null, distanceToStartPointMeters = null,
            isCalculatingRoadRoute = false, isOffTrack = false, isApproachingTarget = true,
            isMovingAwayFromTarget = false,
        )
        s.currentLocation?.let(::processItineraryLocation)
    }

    // ── Reverse ─────────────────────────────────────────────────────────────

    /**
     * Inverts the navigation direction (offered when the distance to the target keeps growing), then
     * re-applies the algorithm: the target becomes the next step after P3 in the new direction, and
     * the status is re-derived from the last P1/P3.
     */
    fun reverseDirection() {
        if (!s.isNavigating || s.mode != NavigationMode.ITINERARY) return
        s = s.copy(isReverse = !s.isReverse)
        manualTargetIndex = null

        // Moving: P3 is the reference. Stopped: there is no fresh P3, and P1 is current.
        val reference = (if (s.isMoving) lastP3 else null) ?: lastP1
        val index = reference?.let { autoTargetIndex(it.index) }
        if (index != null) setTarget(index) else resetTargetTrend()
        reapplyDirectionStatus()
        s.currentLocation?.let { refreshTargetMetrics(it.coordinate) }
        persist()
        flush()
    }

    // ── Manual step selection ───────────────────────────────────────────────

    fun selectPreviousStep() { if (s.canSelectPreviousStep) selectStep(s.currentTargetIndex - 1) }

    fun selectNextStep() { if (s.canSelectNextStep) selectStep(s.currentTargetIndex + 1) }

    fun selectStep(index: Int) {
        if (index !in s.steps.indices) return
        updateDirectionForManualStepSelection(index)
        setTarget(index)
        manualTargetIndex = index
        // A manual selection can flip the direction; the status follows from the last measured P1/P3.
        reapplyDirectionStatus()
        s.currentLocation?.let { refreshTargetMetrics(it.coordinate) }
        persist()
        flush()
    }

    /** Picks the direction that has the chosen step ahead of the user. */
    private fun updateDirectionForManualStepSelection(newIndex: Int) {
        val track = navTrack
        val reference = lastP1
        val stepIndex = stepTrackIndices.getOrNull(newIndex)
        if (track != null && reference != null && stepIndex != null) {
            val diff = track.distanceAtIndex(stepIndex) - reference.distanceAlongTrack
            if (abs(diff) > 1.0) {
                s = s.copy(isReverse = diff < 0)
                return
            }
        }
        if (newIndex < s.currentTargetIndex) s = s.copy(isReverse = true)
        else if (newIndex > s.currentTargetIndex) s = s.copy(isReverse = false)
    }

    // ── Location updates ────────────────────────────────────────────────────

    fun locationUnavailable() {
        s = s.copy(currentLocation = null, distanceToTargetMeters = null)
        resetMotionState()
        flush()
    }

    fun processLocation(fix: LocationFix) {
        process(fix)
        flush()
    }

    private fun process(fix: LocationFix) {
        if (!s.isNavigating || fix.accuracyM < 0) {
            debug("fix ignored: navigating=${s.isNavigating} accuracy=${fix.accuracyM}")
            return
        }
        s = s.copy(currentLocation = fix)

        if (!hasFoundInitialClosestStep) {
            hasFoundInitialClosestStep = true
            val user = fix.coordinate

            // The target is the next step after the closest point on the track (the previous one in reverse).
            val selected = initialTargetIndex(user)
            s = s.copy(currentTargetIndex = selected)
            persist()
            debug(
                "first fix ${fix.latitude},${fix.longitude} acc=${fix.accuracyM}: initial target=$selected " +
                    "distanceToStraight=${s.steps.getOrNull(selected)?.coordinate()?.let { user.distanceMeters(it).toInt() }} m mapboxToken=${mapboxToken?.let { if (it.isEmpty()) "none" else "yes" } ?: "unknown"}"
            )

            // While the target is over 5 km away, follow Mapbox directions until it is under 1 km, then
            // switch to the bearing overlay. Nearer than 5 km, show the bearing overlay at once.
            val target = s.steps.getOrNull(selected)?.coordinate()
            if (target == null) {
                s = s.copy(mode = NavigationMode.ITINERARY)
                return
            }
            val distance = user.distanceMeters(target)
            if (distance > ROAD_NAVIGATION_FROM_M) {
                val token = mapboxToken
                when {
                    !token.isNullOrEmpty() -> {
                        s = s.copy(mode = NavigationMode.ROAD_TO_START, distanceToStartPointMeters = distance)
                        requestRoadRoute(user, target, token)
                        processRoadLocation(fix)
                        return
                    }
                    // The token isn't known yet: wait for it (see updateMapboxToken).
                    token == null -> {
                        s = s.copy(mode = NavigationMode.ROAD_TO_START, distanceToStartPointMeters = distance, isCalculatingRoadRoute = true)
                        processRoadLocation(fix)
                        return
                    }
                    else -> s = s.copy(mode = NavigationMode.ITINERARY)
                }
            } else {
                s = s.copy(mode = NavigationMode.ITINERARY)
            }
        }

        when (s.mode) {
            NavigationMode.ROAD_TO_START -> processRoadLocation(fix)
            NavigationMode.ITINERARY -> processItineraryLocation(fix)
            NavigationMode.NONE -> Unit
        }
    }

    private fun processRoadLocation(fix: LocationFix) {
        val user = fix.coordinate
        val target = s.currentTargetStep?.coordinate()
        if (target == null) {
            leaveRoadMode()
            return
        }
        val dTarget = user.distanceMeters(target)
        s = s.copy(distanceToStartPointMeters = dTarget)
        // Under 1 km, switch to the bearing overlay.
        if (dTarget < ROAD_NAVIGATION_UNTIL_M) {
            leaveRoadMode()
            return
        }
        val route = s.roadRoute
        val step = route?.steps?.getOrNull(s.currentRoadStepIndex)
        if (route == null || step == null) {
            s = s.copy(bearingToTargetDegrees = user.bearing(target), distanceToTargetMeters = dTarget)
            return
        }
        val dManeuver = user.distanceMeters(step.maneuver.location)
        if (dManeuver < MANEUVER_REACHED_M && s.currentRoadStepIndex < route.steps.size - 1) {
            val following = route.steps[s.currentRoadStepIndex + 1]
            s = s.copy(
                currentRoadStepIndex = s.currentRoadStepIndex + 1,
                distanceToCurrentRoadStepMeters = user.distanceMeters(following.maneuver.location),
                bearingToTargetDegrees = user.bearing(following.maneuver.location),
            )
        } else {
            s = s.copy(distanceToCurrentRoadStepMeters = dManeuver, bearingToTargetDegrees = user.bearing(step.maneuver.location))
        }
        val last = lastDistanceToTarget
        val approaching = if (last != null) {
            val delta = dTarget - last
            if (delta < -0.5) true else if (delta > 0.5) false else s.isApproachingTarget
        } else true
        lastDistanceToTarget = dTarget
        s = s.copy(distanceToTargetMeters = dTarget, isApproachingTarget = approaching)
    }

    private fun requestRoadRoute(origin: Coordinate, destination: Coordinate, token: String) {
        s = s.copy(isCalculatingRoadRoute = true)
        roadRouteJob?.cancel()
        val language = s.language
        roadRouteJob = scope.launch {
            try {
                val route = directions.fetchRoute(origin, destination, language, token)
                debug("road route: ${route.steps.size} steps, ${route.totalDistanceMeters.toInt()} m")
                // Stopping, restarting or reaching the start invalidates this request.
                if (!s.isNavigating || s.mode != NavigationMode.ROAD_TO_START) return@launch
                s = s.copy(roadRoute = route, isCalculatingRoadRoute = false)
                roadRouteJob = null
                s.currentLocation?.let(::process)
                flush()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                debug("road route failed: ${e.message}")
                if (!s.isNavigating || s.mode != NavigationMode.ROAD_TO_START) return@launch
                // Offline or no route: never leave an endless spinner.
                leaveRoadMode()
                flush()
            }
        }
    }

    // ── Itinerary navigation ────────────────────────────────────────────────

    private fun processItineraryLocation(fix: LocationFix) {
        if (s.currentTargetIndex !in s.steps.indices) {
            // Should not happen; fall back to a valid step rather than ending the navigation.
            val valid = nearestNavigableStepIndex()
            if (valid != null) setTarget(valid) else stopNavigation("target index out of range, no navigable step")
            return
        }

        val user = fix.coordinate
        updateMotionState(fix, user)

        val track = navTrack
        if (track != null) {
            if (s.isMoving) {
                evaluateMoving(user, travelBearing(fix, user), track)
            } else {
                // Stopped: keep P1 current (continuity, manual selection) but leave the status and the target alone.
                projectP1(user, track)?.let { lastP1 = it }
            }
        }

        val target = s.currentTargetStep?.coordinate()
        if (target == null) {
            // A step with no position can't be navigated to: move to the closest one that has one.
            val next = nearestNavigableStepIndex()
            if (next != null) {
                manualTargetIndex = null
                setTarget(next)
                refreshTargetMetrics(user)
            } else {
                stopNavigation("no step has a position")
            }
            return
        }

        val distance = user.distanceMeters(target)
        if (distance < ARRIVAL_M) {
            val reachedName = s.steps[s.currentTargetIndex].name(s.language)
            if (isFinalTarget) {
                if (finalArrivalArmed) {
                    handleTargetReached(reachedName)
                    stopNavigation("final step reached")
                    return
                }
                // Already at the final step when it became the target: keep navigating until the user has left.
            } else if (!s.isMoving) {
                // Stopped: there is no P3, so arrival advances the target. (While moving, the target
                // follows P3, which has already moved past a step as it is reached.)
                handleTargetReached(reachedName)
                advanceTarget()
                refreshTargetMetrics(user)
                return
            }
        }
        updateTargetMetrics(user, target, distance)
    }

    /** The moving algorithm: P1, P2, P3, direction status, target after P3. */
    private fun evaluateMoving(user: Coordinate, travelBearing: Double?, track: NavigationTrack) {
        val p1 = projectP1(user, track) ?: return
        lastP1 = p1

        var p3 = p1
        if (travelBearing != null) {
            val p2 = NavigationTrack.offset(p1.point, travelBearing, LOOK_AHEAD_M)
            // Searched next to P1 only, so P2 can't snap to another part of the track (the other leg
            // of an out-and-back, a loop crossing).
            val window = track.segments(p1.distanceAlongTrack, P3_WINDOW_M, P3_WINDOW_M)
            track.project(p2, window)?.let { projected ->
                p3 = projected
                lastDirectionDelta = projected.distanceAlongTrack - p1.distanceAlongTrack
                reapplyDirectionStatus()
            }
        }
        lastP3 = p3
        retarget(p3.index)
    }

    /** Sets the red/green status from the last P3 − P1, in the current direction. */
    private fun reapplyDirectionStatus() {
        val track = navTrack ?: return
        val delta = lastDirectionDelta ?: return
        val p1 = lastP1 ?: return
        // At the end being travelled toward, P3 can't get further ahead: no verdict.
        val remaining = if (s.isReverse) p1.distanceAlongTrack else track.totalLength - p1.distanceAlongTrack
        if (remaining <= LOOK_AHEAD_M) return

        val progress = if (s.isReverse) -delta else delta
        if (progress > DIRECTION_DEAD_BAND_M) s = s.copy(isOffTrack = false)
        else if (progress < -DIRECTION_DEAD_BAND_M) s = s.copy(isOffTrack = true)
    }

    /**
     * Moves the target to the first step after [trackIndex] (the last before it in reverse), unless a
     * manually chosen step is still ahead.
     */
    private fun retarget(trackIndex: Double) {
        manualTargetIndex?.let { manual ->
            // Can't be placed on the track: keep it until arrival.
            val manualIndex = stepTrackIndices.getOrNull(manual) ?: return
            val stillAhead = if (s.isReverse) manualIndex < trackIndex else manualIndex > trackIndex
            if (stillAhead) return
            manualTargetIndex = null
        }

        val newIndex = autoTargetIndex(trackIndex) ?: return
        if (newIndex == s.currentTargetIndex) return

        val passedInTravelDirection = if (s.isReverse) newIndex < s.currentTargetIndex else newIndex > s.currentTargetIndex
        if (passedInTravelDirection) s.currentTargetStep?.let { handleTargetReached(it.name(s.language)) }
        setTarget(newIndex)
        // Having gone past the step before the final one is progress: reaching the final step is then
        // the real end, wherever the user was when it became the target.
        if (passedInTravelDirection) finalArrivalArmed = true
    }

    /** The step with a position closest to the current target, searching in the travel direction first. */
    private fun nearestNavigableStepIndex(): Int? {
        if (s.steps.isEmpty()) return null
        val clamped = s.currentTargetIndex.coerceIn(0, s.steps.lastIndex)
        val ahead = if (s.isReverse) (clamped downTo 0).toList() else (clamped until s.steps.size).toList()
        val behind = if (s.isReverse) (clamped until s.steps.size).toList() else (clamped downTo 0).toList()
        return (ahead + behind).firstOrNull { s.steps[it].coordinate() != null }
    }

    /**
     * The step after [trackIndex] in the navigation direction. Forward: the first step at or after
     * it. Reverse: the last step at or before it. Past the last step, the last step stays the target
     * (reverse: the first).
     */
    private fun autoTargetIndex(trackIndex: Double): Int? {
        val placed = stepTrackIndices.withIndex().mapNotNull { (offset, value) -> value?.let { offset to it } }
        val first = placed.firstOrNull() ?: return null
        val last = placed.last()
        return if (s.isReverse) placed.lastOrNull { it.second <= trackIndex }?.first ?: first.first
        else placed.firstOrNull { it.second >= trackIndex }?.first ?: last.first
    }

    private fun initialTargetIndex(user: Coordinate): Int {
        val fallback = if (s.isReverse) s.steps.lastIndex else 0
        val track = navTrack ?: return fallback
        val p1 = track.project(user) ?: return fallback
        lastP1 = p1
        if (p1.crossDistance > INITIAL_MAX_DISTANCE_M) return fallback
        return autoTargetIndex(p1.index) ?: fallback
    }

    /** P1, preferring continuity with the previous P1. */
    private fun projectP1(user: Coordinate, track: NavigationTrack): NavigationTrack.Position? {
        val global = track.project(user) ?: return null
        val previous = lastP1 ?: return global
        val window = track.segments(previous.distanceAlongTrack, P1_WINDOW_M, P1_WINDOW_M)
        val local = track.project(user, window) ?: return global
        return if (local.crossDistance <= global.crossDistance + P1_JUMP_TOLERANCE_M) local else global
    }

    private val isFinalTarget: Boolean
        get() = if (s.isReverse) s.currentTargetIndex <= 0 else s.currentTargetIndex >= s.steps.size - 1

    private fun setTarget(index: Int) {
        debug("target ${s.currentTargetIndex} -> $index (reverse=${s.isReverse}, moving=${s.isMoving})")
        s = s.copy(currentTargetIndex = index, isApproachingTarget = true)
        finalArrivalArmed = false
        lastDistanceToTarget = null
        closestDistance = null
        resetTargetTrend()
        persist()
    }

    private fun advanceTarget() {
        manualTargetIndex = null
        setTarget(if (s.isReverse) s.currentTargetIndex - 1 else s.currentTargetIndex + 1)
    }

    private fun refreshTargetMetrics(user: Coordinate) {
        val target = s.currentTargetStep?.coordinate() ?: return
        updateTargetMetrics(user, target, user.distanceMeters(target))
    }

    /**
     * [distance] is the straight line from [user] to [target]; it decides arrival and arms the final
     * step (geometric questions). The distance shown, and whose trend drives the reverse offer, is
     * measured along the track.
     */
    private fun updateTargetMetrics(user: Coordinate, target: Coordinate, distance: Double) {
        val shown = distanceAlongTrackToTarget() ?: distance
        closestDistance = min(closestDistance ?: shown, shown)
        if (isFinalTarget && distance >= FINAL_ARM_M) finalArrivalArmed = true

        val last = lastDistanceToTarget
        val approaching = if (last != null) {
            val delta = shown - last
            if (delta < -0.5) true else if (delta > 0.5) false else s.isApproachingTarget
        } else true
        lastDistanceToTarget = shown
        s = s.copy(distanceToTargetMeters = shown, bearingToTargetDegrees = user.bearing(target), isApproachingTarget = approaching)

        if (s.isMoving) updateAwayTrend(shown)
        updateTrackGuidance(user)
    }

    /**
     * The distance to the target step along the track: from the user's closest point on it (P1) to
     * the step's position on it, in either direction, plus the gap between the user and the track so
     * that being away from the track still counts. Null without a track or when the step has no
     * position on it; callers fall back to the straight line.
     */
    private fun distanceAlongTrackToTarget(): Double? {
        val track = navTrack ?: return null
        val p1 = lastP1 ?: return null
        val stepIndex = stepTrackIndices.getOrNull(s.currentTargetIndex) ?: return null
        return abs(track.distanceAtIndex(stepIndex) - p1.distanceAlongTrack) + p1.crossDistance
    }

    /**
     * Hysteresis on the distance trend: "moving away" after a 30 m increase, cleared after a 15 m
     * decrease from the peak. Only fed while moving.
     */
    private fun updateAwayTrend(distance: Double) {
        val anchor = trendAnchorDistance
        if (anchor == null) {
            trendAnchorDistance = distance
            return
        }
        if (distance > anchor + AWAY_INCREASE_M) {
            s = s.copy(isMovingAwayFromTarget = true)
            trendAnchorDistance = distance
        } else if (distance < anchor - APPROACH_DECREASE_M) {
            s = s.copy(isMovingAwayFromTarget = false)
            trendAnchorDistance = distance
        } else if (s.isMovingAwayFromTarget && distance > anchor) {
            trendAnchorDistance = distance // track the peak
        } else if (!s.isMovingAwayFromTarget && distance < anchor) {
            trendAnchorDistance = distance // track the low
        }
    }

    private fun resetTargetTrend() {
        trendAnchorDistance = null
        if (s.isMovingAwayFromTarget) s = s.copy(isMovingAwayFromTarget = false)
    }

    // ── Motion ──────────────────────────────────────────────────────────────

    /**
     * Moving vs stopped, from the GPS speed (or the speed between fixes when the fix has none).
     * Starts moving at 1 m/s or more; stops after 3 s below 0.5 m/s.
     */
    private fun updateMotionState(fix: LocationFix, user: Coordinate) {
        if (fix.accuracyM > MAX_MOTION_ACCURACY_M) return

        var speed: Double? = null
        val reported = fix.speedMps
        if (reported != null && reported >= 0f) {
            speed = reported.toDouble()
        } else {
            lastMotionFix?.let { last ->
                val elapsed = (fix.timeMs - last.timeMs) / 1000.0
                if (elapsed >= 1.0) speed = fix.coordinate.distanceMeters(last.coordinate) / elapsed
            }
        }
        lastMotionFix = fix
        val v = speed ?: return

        if (s.isMoving) {
            if (v >= STOP_MOVING_SPEED) {
                stoppedSinceMs = null
                return
            }
            val since = stoppedSinceMs ?: fix.timeMs
            stoppedSinceMs = since
            if (fix.timeMs - since >= STOP_DEBOUNCE_MS) {
                debug("motion: stopped (speed ${"%.2f".format(v)} m/s)")
                s = s.copy(isMoving = false)
                stoppedSinceMs = null
                courseAnchor = null
                // The trend restarts when moving resumes.
                trendAnchorDistance = null
            }
        } else if (v >= START_MOVING_SPEED) {
            debug("motion: moving (speed ${"%.2f".format(v)} m/s)")
            s = s.copy(isMoving = true)
            stoppedSinceMs = null
            courseAnchor = user
        }
    }

    /** The direction of travel: the GPS course, else the bearing from the last point at least 10 m back. */
    private fun travelBearing(fix: LocationFix, user: Coordinate): Double? {
        val course = fix.courseDeg
        if (course != null && course >= 0f) {
            courseAnchor = user
            s = s.copy(travelBearing = course.toDouble())
            return course.toDouble()
        }
        val anchor = courseAnchor
        if (anchor == null) {
            courseAnchor = user
            return s.travelBearing
        }
        if (anchor.distanceMeters(user) < COURSE_ANCHOR_M) return s.travelBearing
        val bearing = anchor.bearing(user)
        courseAnchor = user
        s = s.copy(travelBearing = bearing)
        return bearing
    }

    private fun resetMotionState() {
        finalArrivalArmed = false
        lastMotionFix = null
        stoppedSinceMs = null
        courseAnchor = null
        trendAnchorDistance = null
        manualTargetIndex = null
        lastP1 = null
        lastP3 = null
        lastDirectionDelta = null
        lastDistanceToTarget = null
        closestDistance = null
    }

    /**
     * The track used by the itinerary algorithm, with the track index of every step. Without a track,
     * the polyline through the steps stands in for it.
     */
    private fun rebuildNavigationTrack() {
        val points = if (s.trackCoordinates.size >= 2) s.trackCoordinates else s.steps.mapNotNull { it.coordinate() }
        navTrack = NavigationTrack.of(points)
        stepTrackIndices = navTrack?.stepIndices(s.steps.map { it.coordinate() }) ?: List(s.steps.size) { null }
        lastP1 = null
        lastP3 = null
        lastDirectionDelta = null
    }

    private fun updateTrackGuidance(user: Coordinate) {
        val points = navTrack?.points
        val guidance = if (points == null) null else TrackNavigationMath.computeGuidance(user, points, s.isReverse)
        s = if (guidance == null) {
            s.copy(
                referenceTrackPoint = null, trackBearingDegrees = null, lookaheadPoint = null,
                crossTrackDistanceMeters = null, crossTrackSide = CrossTrackSide.NONE,
            )
        } else {
            s.copy(
                referenceTrackPoint = guidance.referencePoint, lookaheadPoint = guidance.lookaheadPoint,
                trackBearingDegrees = guidance.trackBearing, crossTrackDistanceMeters = guidance.crossTrackDistanceMeters,
                crossTrackSide = guidance.crossTrackSide,
            )
        }
    }

    private fun handleTargetReached(name: String) {
        s = s.copy(recentlyReachedStepName = name)
        onTargetReached(name)
        reachedBannerJob?.cancel()
        reachedBannerJob = scope.launch {
            delay(REACHED_BANNER_MS)
            s = s.copy(recentlyReachedStepName = null)
            flush()
        }
    }

    companion object {
        // Start moving above this speed (3.6 km/h), and stop below the other once it has held for the debounce.
        const val START_MOVING_SPEED = 1.0
        const val STOP_MOVING_SPEED = 0.5
        const val STOP_DEBOUNCE_MS = 3000L

        /** Fixes less accurate than this don't change the moving/stopped state. */
        const val MAX_MOTION_ACCURACY_M = 65.0

        /** P1 → P2 shift along the direction of travel. */
        const val LOOK_AHEAD_M = 50.0

        /** |P3 − P1| below this is too small to judge the direction: the status is kept. */
        const val DIRECTION_DEAD_BAND_M = 5.0

        /** Arrival radius at a step. */
        const val ARRIVAL_M = 50.0

        /** P1 is searched near the previous P1 first; the global best wins only when clearly closer. */
        const val P1_WINDOW_M = 1000.0
        const val P1_JUMP_TOLERANCE_M = 50.0

        /** P3 is searched within this many metres of P1 along the track. */
        const val P3_WINDOW_M = 200.0

        /** The distance to the target must grow by this much before the reverse offer appears, and shrink by the other to clear it. */
        const val AWAY_INCREASE_M = 30.0
        const val APPROACH_DECREASE_M = 15.0

        /** Beyond this distance from the track the navigation starts at the beginning. */
        const val INITIAL_MAX_DISTANCE_M = 30_000.0

        /** Minimum displacement used to derive a travel direction when the fix carries no course. */
        const val COURSE_ANCHOR_M = 10.0

        /** The final step only ends the navigation once the user has been this far from it. */
        const val FINAL_ARM_M = 100.0

        const val ROAD_NAVIGATION_FROM_M = 5000.0
        const val ROAD_NAVIGATION_UNTIL_M = 1000.0
        const val MANEUVER_REACHED_M = 40.0
        const val REACHED_BANNER_MS = 3000L
    }
}

/** A step's position, when it has one. */
fun ItineraryStep.coordinate(): Coordinate? {
    val la = lat ?: return null
    val lo = lon ?: return null
    return Coordinate(la, lo)
}
