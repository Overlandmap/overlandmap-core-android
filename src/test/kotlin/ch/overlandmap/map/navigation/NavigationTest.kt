package ch.overlandmap.map.navigation

import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.tracking.LocationFix
import ch.overlandmap.map.tracking.LocationSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {

    // A straight east-bound track on the equator: 0.001° of longitude is about 111 m.
    private val track = (0..30).map { Coordinate(0.0, it * 0.001) }
    private val stepLongitudes = listOf(0.0, 0.010, 0.020, 0.030)

    private fun step(n: Int, lon: Double) = ItineraryStep.fromFirestore(
        "s$n", "i1",
        mapOf(
            "trackPackId" to "p", "id" to n + 1, "name" to "Step ${n + 1}", "ele" to 1000 + n,
            "location" to mapOf("latitude" to 0.0, "longitude" to lon),
        ),
    )

    private val steps = stepLongitudes.mapIndexed(::step)

    private fun fix(lat: Double, lon: Double, t: Long = 0L, accuracy: Float = 5f) = LocationFix(lat, lon, null, t, accuracy)

    // ── Math ────────────────────────────────────────────────────────────────

    @Test
    fun projectionFindsTheClosestPointAndTheDistanceAlong() {
        val p = TrackNavigationMath.closestTrackProjection(Coordinate(0.0005, 0.0105), track)!!
        assertEquals(10, p.segmentIndex)
        assertEquals(0.5, p.fraction, 0.01)
        assertEquals(1.0 / 0.001 * 0 + 10.5 * 111.19, p.distanceAlongTrack, 3.0)
        assertEquals(55.6, p.crossDistance, 1.0)
        assertNull(TrackNavigationMath.closestTrackProjection(Coordinate(0.0, 0.0), listOf(Coordinate(0.0, 0.0))))
    }

    @Test
    fun theInitialStepIsTheNextOneAlongTheTrackInTheTravelDirection() {
        val coords = steps.map { it.coordinate()!! }
        fun initial(lon: Double, reverse: Boolean) =
            TrackNavigationMath.initialTargetStepIndex(Coordinate(0.0001, lon), track, coords, reverse)
        assertEquals(0, initial(-0.002, false))
        assertEquals(2, initial(0.0125, false))
        assertEquals(1, initial(0.0125, true))
        assertEquals(3, initial(0.0305, true))
        // Beyond the last step the forward journey ends there.
        assertEquals(3, initial(0.0305, false))
        // Far from the track: the start of the journey in either direction.
        val far = TrackNavigationMath.initialTargetStepIndex(Coordinate(1.0, 0.0125), track, coords, false)
        assertEquals(0, far)
        assertEquals(3, TrackNavigationMath.initialTargetStepIndex(Coordinate(1.0, 0.0125), track, coords, true))
        assertEquals(0, TrackNavigationMath.initialTargetStepIndex(Coordinate(0.0, 0.0), track, coords.take(1), false))
    }

    @Test
    fun guidancePointsAlongTheTrackAndTellsWhichSideTheUserIsOn() {
        // 55 m north of an east-bound track: the user is on its left.
        val north = TrackNavigationMath.computeGuidance(Coordinate(0.0005, 0.0105), track, reverse = false)!!
        assertEquals(CrossTrackSide.LEFT, north.crossTrackSide)
        assertEquals(55.6, north.crossTrackDistanceMeters, 1.0)
        assertTrue(north.referencePoint.distanceMeters(north.lookaheadPoint) in 99.0..101.0)
        // The arrow points from the user to a point 100 m ahead: east and a little south.
        assertTrue(north.trackBearing in 90.0..180.0)

        // Walking the other way, the same position is on the right.
        val reverse = TrackNavigationMath.computeGuidance(Coordinate(0.0005, 0.0105), track, reverse = true)!!
        assertEquals(CrossTrackSide.RIGHT, reverse.crossTrackSide)
        assertTrue(reverse.trackBearing in 180.0..270.0)

        // Within 20 m of the track no side is reported.
        val close = TrackNavigationMath.computeGuidance(Coordinate(0.00005, 0.0105), track, reverse = false)!!
        assertEquals(CrossTrackSide.NONE, close.crossTrackSide)

        // Near the end the lookahead stops at the last point.
        val end = TrackNavigationMath.computeGuidance(Coordinate(0.0, 0.0299), track, reverse = false)!!
        assertEquals(track.last(), end.lookaheadPoint)
        assertNull(TrackNavigationMath.computeGuidance(Coordinate(0.0, 0.0), emptyList(), false))
    }

    @Test
    fun relativeBearingIsSignedAndWrapped() {
        assertEquals(10.0, TrackNavigationMath.relativeBearing(10.0, 0.0), 1e-9)
        assertEquals(-20.0, TrackNavigationMath.relativeBearing(350.0, 10.0), 1e-9)
        assertEquals(20.0, TrackNavigationMath.relativeBearing(10.0, 350.0), 1e-9)
        assertEquals(-180.0, TrackNavigationMath.relativeBearing(0.0, 180.0), 1e-9)
    }

    // ── Directions ──────────────────────────────────────────────────────────

    @Test
    fun directionsResponseIsParsed() {
        val json = """{"code":"Ok","routes":[{"distance":12000.5,"duration":900.0,
            "geometry":{"type":"LineString","coordinates":[[69.0,41.0],[69.1,41.1]]},
            "legs":[{"steps":[
              {"distance":500,"duration":60,"name":"Main St","maneuver":{"instruction":"Turn left","type":"turn","modifier":"left","location":[69.0,41.0]}},
              {"distance":0,"duration":0,"maneuver":{"instruction":"Arrive","type":"arrive"}}]}]}]}"""
        val dest = Coordinate(41.1, 69.1)
        val route = MapboxDirectionsService.parse(json, dest)
        assertEquals(12000.5, route.totalDistanceMeters, 0.0)
        assertEquals(listOf(Coordinate(41.0, 69.0), Coordinate(41.1, 69.1)), route.coordinates)
        assertEquals(2, route.steps.size)
        assertEquals("Turn left", route.steps[0].instruction)
        assertEquals("left", route.steps[0].maneuver.modifier)
        assertEquals(Coordinate(41.0, 69.0), route.steps[0].maneuver.location)
        assertEquals("Main St", route.steps[0].name)
        // A maneuver without a location uses the destination.
        assertEquals(dest, route.steps[1].maneuver.location)
        assertNull(route.steps[1].name)
        try {
            MapboxDirectionsService.parse("""{"code":"NoRoute"}""", dest)
            org.junit.Assert.fail()
        } catch (_: DirectionsException) {
        }
        assertTrue(MapboxDirectionsService.url(Coordinate(41.0, 69.0), dest, "fr", "tok").startsWith(
            "https://api.mapbox.com/directions/v5/mapbox/driving/69.000000,41.000000;69.100000,41.100000?steps=true"
        ))
    }

    // ── Coordinator ─────────────────────────────────────────────────────────

    private class FakeLocation : LocationSource {
        override var lastFix: LocationFix? = null
        var started = 0
        var stopped = 0
        override fun start(minDistanceMeters: Float, onFix: (LocationFix) -> Unit) { started++ }
        override fun stop() { stopped++ }
    }

    private class FakePersistence : NavigationPersistence {
        var saved: SavedNavigationState? = null
        override fun load() = saved
        override fun save(state: SavedNavigationState) { saved = state }
        override fun clear() { saved = null }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() = scope.cancel()

    private class Harness(
        val location: FakeLocation = FakeLocation(),
        val persistence: FakePersistence = FakePersistence(),
        val reached: MutableList<String> = mutableListOf(),
        val changes: MutableList<Boolean> = mutableListOf(),
        var routing: DirectionsRouting = DirectionsRouting { _, _, _, _ -> error("no route") },
    )

    private fun coordinator(h: Harness) = NavigationCoordinator(
        h.location, h.persistence, scope, DirectionsRouting { o, d, l, t -> h.routing.fetchRoute(o, d, l, t) },
        onTargetReached = { h.reached += it }, onNavigatingChanged = { h.changes += it },
    )

    private fun start(
        c: NavigationCoordinator,
        reverse: Boolean = false,
        token: String? = "",
        initial: LocationFix? = null,
        index: Int? = null,
    ) = c.startNavigation("i1", "Trip", steps, track, reverse, index, initial, "en", token)

    @Test
    fun startingWithoutAFixWaitsForTheFirstOne() {
        val h = Harness()
        val c = coordinator(h)
        start(c)
        val s = c.state.value
        assertTrue(s.isNavigating)
        assertEquals(NavigationMode.ITINERARY, s.mode)
        assertEquals(0, s.currentTargetIndex)
        assertEquals(listOf(true), h.changes)
        assertEquals(1, h.location.started)
        assertEquals(SavedNavigationState("i1", false, 0), h.persistence.saved)

        c.processLocation(fix(0.0001, 0.0125))
        assertEquals(2, c.state.value.currentTargetIndex)
        assertEquals(SavedNavigationState("i1", false, 2), h.persistence.saved)
        assertEquals(0.0075 * 111_195.0, c.state.value.distanceToTargetMeters!!, 60.0)
        assertNotNull(c.state.value.trackBearingDegrees)
    }

    @Test
    fun reverseNavigationStartsAtTheLastStep() {
        val c = coordinator(Harness())
        start(c, reverse = true)
        assertEquals(3, c.state.value.currentTargetIndex)
        c.processLocation(fix(0.0001, 0.0125))
        assertEquals(1, c.state.value.currentTargetIndex)
    }

    @Test
    fun aRestoredSessionKeepsItsTarget() {
        val c = coordinator(Harness())
        start(c, index = 1, initial = fix(0.0, 0.0))
        assertEquals(1, c.state.value.currentTargetIndex)
    }

    @Test
    fun reachingAStepMovesOnAndTheLastStepEndsTheNavigation() {
        val h = Harness()
        val c = coordinator(h)
        start(c, initial = fix(0.0, -0.005)) // 555 m before the first step
        assertEquals(0, c.state.value.currentTargetIndex)
        assertTrue(h.reached.isEmpty())
        // Within 50 m of step 1: on to step 2.
        c.processLocation(fix(0.0, 0.0002))
        assertEquals(1, c.state.value.currentTargetIndex)
        assertEquals(listOf("Step 1"), h.reached)
        assertEquals("Step 1", c.state.value.recentlyReachedStepName)
        assertFalse(c.state.value.isOffTrack)
        assertEquals(SavedNavigationState("i1", false, 1), h.persistence.saved)

        c.processLocation(fix(0.0, 0.0100))
        c.processLocation(fix(0.0, 0.0200))
        assertEquals(3, c.state.value.currentTargetIndex)
        c.processLocation(fix(0.0, 0.0300))
        assertEquals(listOf("Step 1", "Step 2", "Step 3", "Step 4"), h.reached)
        assertFalse(c.state.value.isNavigating)
        assertNull(h.persistence.saved)
        assertEquals(listOf(true, false), h.changes)
        assertEquals(1, h.location.stopped)
    }

    @Test
    fun reachingTheFirstStepOnTheFirstFixCountsAsReached() {
        val h = Harness()
        val c = coordinator(h)
        start(c, initial = fix(0.0, 0.0))
        assertEquals(1, c.state.value.currentTargetIndex)
        assertEquals(listOf("Step 1"), h.reached)
    }

    @Test
    fun inaccurateFixesAreIgnored() {
        val c = coordinator(Harness())
        start(c)
        c.processLocation(fix(0.0, 0.0125, accuracy = -1f))
        assertNull(c.state.value.currentLocation)
        assertEquals(0, c.state.value.currentTargetIndex)
    }

    @Test
    fun farFromTheStartRoadDirectionsLeadThereAndTheBearingModeTakesOverUnderAKilometre() {
        val h = Harness()
        val route = RoadRoute(
            25_000.0, 1800.0, listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 0.2)),
            listOf(
                RoadStep("a", "Go east", RoadManeuver("Go east", "depart", null, Coordinate(0.0, 0.0)), 20_000.0, 600.0),
                RoadStep("b", "Turn left", RoadManeuver("Turn left", "turn", "left", Coordinate(0.0, 0.1)), 5_000.0, 300.0),
            ),
        )
        var requested: Triple<Coordinate, Coordinate, String>? = null
        h.routing = DirectionsRouting { o, d, _, token -> requested = Triple(o, d, token); route }
        val c = coordinator(h)
        start(c, token = "tok")
        // 22 km west of the start.
        c.processLocation(fix(0.0, -0.2))
        val s = c.state.value
        assertEquals(NavigationMode.ROAD_TO_START, s.mode)
        assertEquals(route, s.roadRoute)
        assertFalse(s.isCalculatingRoadRoute)
        assertEquals("tok", requested!!.third)
        assertEquals(Coordinate(0.0, 0.0), requested!!.second)
        assertEquals("Go east", s.currentRoadStep!!.instruction)
        assertEquals(22_239.0, s.distanceToStartPointMeters!!, 100.0)

        // Reaching the first maneuver moves to the next instruction.
        c.processLocation(fix(0.0, -0.0001))
        assertEquals(NavigationMode.ITINERARY, c.state.value.mode) // already under 1 km of the start
        assertNull(c.state.value.roadRoute)
    }

    @Test
    fun roadInstructionsAdvanceWhenAManeuverIsReached() {
        val h = Harness()
        val route = RoadRoute(
            30_000.0, 1800.0, emptyList(),
            listOf(
                RoadStep("a", "First", RoadManeuver("First", null, null, Coordinate(0.0, -0.3)), 0.0, 0.0),
                RoadStep("b", "Second", RoadManeuver("Second", null, "left", Coordinate(0.0, -0.2)), 0.0, 0.0),
            ),
        )
        h.routing = DirectionsRouting { _, _, _, _ -> route }
        val c = coordinator(h)
        start(c, token = "tok")
        c.processLocation(fix(0.0, -0.3005))
        assertEquals(0, c.state.value.currentRoadStepIndex)
        c.processLocation(fix(0.0, -0.3))
        assertEquals(1, c.state.value.currentRoadStepIndex)
        assertEquals(11_119.5, c.state.value.distanceToCurrentRoadStepMeters!!, 120.0)
    }

    @Test
    fun withoutAKnownTokenTheRouteWaitsForIt() {
        val h = Harness()
        var calls = 0
        h.routing = DirectionsRouting { _, _, _, _ -> calls++; RoadRoute(1.0, 1.0, emptyList(), emptyList()) }
        val c = coordinator(h)
        start(c, token = null)
        c.processLocation(fix(0.0, -0.2))
        assertEquals(NavigationMode.ROAD_TO_START, c.state.value.mode)
        assertTrue(c.state.value.isCalculatingRoadRoute)
        assertEquals(0, calls)
        c.updateMapboxToken("  late ")
        assertEquals(1, calls)
        assertFalse(c.state.value.isCalculatingRoadRoute)
        assertNotNull(c.state.value.roadRoute)
    }

    @Test
    fun anEmptyTokenOrAFailedRouteFallsBackToTheBearingMode() {
        val none = coordinator(Harness())
        start(none, token = "")
        none.processLocation(fix(0.0, -0.2))
        assertEquals(NavigationMode.ITINERARY, none.state.value.mode)

        val failing = coordinator(Harness())
        start(failing, token = "tok") // the default fake routing throws
        failing.processLocation(fix(0.0, -0.2))
        assertEquals(NavigationMode.ITINERARY, failing.state.value.mode)
        assertFalse(failing.state.value.isCalculatingRoadRoute)
        assertTrue(failing.state.value.isNavigating)
    }

    @Test
    fun pickingAStepByHandSetsTheDirectionFromTheTrack() {
        val h = Harness()
        val c = coordinator(h)
        start(c, initial = fix(0.0, 0.0150))
        assertEquals(2, c.state.value.currentTargetIndex)
        assertFalse(c.state.value.isReverse)

        // The previous step lies behind along the track: heading back.
        c.selectPreviousStep()
        assertEquals(1, c.state.value.currentTargetIndex)
        assertTrue(c.state.value.isReverse)
        assertEquals(SavedNavigationState("i1", true, 1), h.persistence.saved)
        assertEquals(556.0, c.state.value.distanceToTargetMeters!!, 5.0)

        c.selectStep(3)
        assertFalse(c.state.value.isReverse)
        assertEquals(3, c.state.value.currentTargetIndex)
        assertFalse(c.state.value.canSelectNextStep)
        c.selectNextStep() // no-op at the end
        assertEquals(3, c.state.value.currentTargetIndex)
        c.selectStep(99)
        assertEquals(3, c.state.value.currentTargetIndex)
    }

    @Test
    fun stoppingResetsEverythingAndRestartingWorks() {
        val h = Harness()
        val c = coordinator(h)
        start(c, initial = fix(0.0, 0.0))
        c.stopNavigation()
        assertFalse(c.state.value.isNavigating)
        assertEquals(NavigationMode.NONE, c.state.value.mode)
        assertTrue(c.state.value.steps.isEmpty())
        assertNull(h.persistence.saved)
        c.stopNavigation() // idempotent
        assertEquals(listOf(true, false), h.changes)
        start(c)
        assertTrue(c.state.value.isNavigating)
        assertEquals(listOf(true, false, true), h.changes)
    }

}
