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

/** The itinerary algorithm (moving / stopped, P1-P2-P3, targets, arrival, reverse offer) with synthetic fixes. */
class ItineraryEngineTest {

    // A straight east-bound track on the equator: 0.001° of longitude is about 111.19 m.
    private val track = (0..30).map { Coordinate(0.0, it * 0.001) }
    private val stepLongitudes = listOf(0.0, 0.010, 0.020, 0.030)

    private fun step(n: Int, lon: Double?, lat: Double = 0.0) = ItineraryStep.fromFirestore(
        "s$n", "i1",
        buildMap {
            put("trackPackId", "p"); put("id", n + 1); put("name", "Step ${n + 1}"); put("ele", 1000 + n)
            if (lon != null) put("location", mapOf("latitude" to lat, "longitude" to lon))
        },
    )

    private val steps = stepLongitudes.mapIndexed { i, lon -> step(i, lon) }

    /** A fix. [speed] and [course] are optional like on a real receiver. */
    private fun fix(
        lon: Double, t: Long = 0L, speed: Float? = null, course: Float? = null, lat: Double = 0.0, accuracy: Float = 5f,
    ) = LocationFix(lat, lon, null, t, accuracy, course, speed)

    private class FakeLocation : LocationSource {
        override var lastFix: LocationFix? = null
        override fun start(minDistanceMeters: Float, onFix: (LocationFix) -> Unit) {}
        override fun stop() {}
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

    private class Harness {
        val persistence = FakePersistence()
        val reached = mutableListOf<String>()
        val logs = mutableListOf<String>()
    }

    private fun coordinator(h: Harness = Harness()) = NavigationCoordinator(
        FakeLocation(), h.persistence, scope,
        onTargetReached = { h.reached += it }, log = { h.logs += it },
    )

    private fun start(
        c: NavigationCoordinator,
        reverse: Boolean = false,
        initial: LocationFix? = null,
        index: Int? = null,
        stepList: List<ItineraryStep> = steps,
        trackPoints: List<Coordinate> = track,
    ) = c.startNavigation("i1", "Trip", stepList, trackPoints, reverse, index, initial, "en", "")

    /** A moving fix: 5 m/s along [course]. */
    private fun moving(lon: Double, t: Long, course: Float, lat: Double = 0.0) = fix(lon, t, speed = 5f, course = course, lat = lat)

    private fun NavigationCoordinator.target() = state.value.currentTargetIndex

    // ── Moving / stopped ────────────────────────────────────────────────────

    @Test
    fun movingStartsAtOneMetrePerSecondAndStopsAfterThreeSecondsBelowHalf() {
        val c = coordinator()
        start(c, initial = fix(0.0050, 0, speed = 0f))
        assertFalse(c.state.value.isMoving)
        c.processLocation(fix(0.0051, 1000, speed = 0.9f))
        assertFalse(c.state.value.isMoving)
        c.processLocation(fix(0.0052, 2000, speed = 1.0f))
        assertTrue(c.state.value.isMoving)

        // Slow, but not for three seconds yet.
        c.processLocation(fix(0.0052, 3000, speed = 0.2f))
        c.processLocation(fix(0.0052, 5999, speed = 0.2f))
        assertTrue(c.state.value.isMoving)
        c.processLocation(fix(0.0052, 6000, speed = 0.2f))
        assertFalse(c.state.value.isMoving)
    }

    @Test
    fun aFastEnoughFixRestartsTheStopDebounce() {
        val c = coordinator()
        start(c, initial = fix(0.0050, 0, speed = 3f))
        assertTrue(c.state.value.isMoving)
        c.processLocation(fix(0.0050, 1000, speed = 0.2f))
        c.processLocation(fix(0.0050, 3000, speed = 0.7f)) // above 0.5 m/s: the clock starts again
        c.processLocation(fix(0.0050, 4000, speed = 0.2f))
        c.processLocation(fix(0.0050, 6999, speed = 0.2f))
        assertTrue(c.state.value.isMoving)
        c.processLocation(fix(0.0050, 7000, speed = 0.2f))
        assertFalse(c.state.value.isMoving)
    }

    @Test
    fun withoutAReportedSpeedItComesFromTheDistanceBetweenFixesAtLeastASecondApart() {
        val c = coordinator()
        start(c, initial = fix(0.0050, 0))
        assertFalse(c.state.value.isMoving)
        // 11 m in 0.5 s: too close in time to tell.
        c.processLocation(fix(0.0051, 500))
        assertFalse(c.state.value.isMoving)
        // 11 m in 1.5 s since the previous fix, 7 m/s.
        c.processLocation(fix(0.0052, 2000))
        assertTrue(c.state.value.isMoving)
    }

    @Test
    fun inaccurateFixesDoNotChangeTheMotionState() {
        val c = coordinator()
        start(c, initial = fix(0.0050, 0, speed = 0f))
        c.processLocation(fix(0.0060, 1000, speed = 10f, accuracy = 65.1f))
        assertFalse(c.state.value.isMoving)
        c.processLocation(fix(0.0060, 2000, speed = 10f, accuracy = 65f))
        assertTrue(c.state.value.isMoving)
        c.processLocation(fix(0.0060, 3000, speed = 0f, accuracy = 80f))
        c.processLocation(fix(0.0060, 9000, speed = 0f, accuracy = 80f))
        assertTrue(c.state.value.isMoving)
    }

    @Test
    fun theTravelBearingComesFromTheCourseElseFromTheDisplacementSinceTheAnchor() {
        val c = coordinator()
        start(c, initial = fix(0.0050, 0, speed = 0f))
        c.processLocation(fix(0.00501, 1000, speed = 5f)) // moving, no course: the anchor is set here
        assertTrue(c.state.value.isMoving)
        assertNull(c.state.value.travelBearing)
        c.processLocation(fix(0.00505, 2000, speed = 5f)) // 5.5 m from the anchor: not enough
        assertNull(c.state.value.travelBearing)
        c.processLocation(fix(0.00520, 3000, speed = 5f)) // 21 m: east
        assertEquals(90.0, c.state.value.travelBearing!!, 0.5)
        assertFalse(c.state.value.isOffTrack)
        c.processLocation(moving(0.0053, 4000, course = 270f))
        assertEquals(270.0, c.state.value.travelBearing!!, 1e-6)
    }

    // ── Direction status ────────────────────────────────────────────────────

    @Test
    fun travellingAlongTheTrackIsGreenAndAgainstItRed() {
        val c = coordinator()
        start(c, initial = fix(0.0050, 0, speed = 0f))
        c.processLocation(moving(0.0051, 1000, 270f))
        assertTrue(c.state.value.isOffTrack)
        c.processLocation(moving(0.0052, 2000, 90f))
        assertFalse(c.state.value.isOffTrack)
        c.processLocation(moving(0.0051, 3000, 270f))
        assertTrue(c.state.value.isOffTrack)
    }

    @Test
    fun inReverseTheDirectionsAreSwapped() {
        val c = coordinator()
        start(c, reverse = true, initial = fix(0.0050, 0, speed = 0f))
        c.processLocation(moving(0.0051, 1000, 270f))
        assertFalse(c.state.value.isOffTrack)
        c.processLocation(moving(0.0050, 2000, 90f))
        assertTrue(c.state.value.isOffTrack)
    }

    @Test
    fun aMoveWithinFiveMetresAlongTheTrackKeepsThePreviousStatus() {
        val c = coordinator()
        start(c, initial = fix(0.0050, 0, speed = 0f))
        c.processLocation(moving(0.0051, 1000, 270f)) // red
        // Heading almost perpendicular to the track: 50 m ahead is only 4.4 m along it.
        c.processLocation(moving(0.0052, 2000, 5f))
        assertTrue(c.state.value.isOffTrack)
        c.processLocation(moving(0.0053, 3000, 90f)) // green
        c.processLocation(moving(0.0054, 4000, 355f)) // -4.4 m: kept
        assertFalse(c.state.value.isOffTrack)
    }

    @Test
    fun theStatusIsFrozenWhileStopped() {
        val c = coordinator()
        start(c, initial = fix(0.0050, 0, speed = 0f))
        c.processLocation(moving(0.0051, 1000, 270f)) // red
        c.processLocation(fix(0.0051, 2000, speed = 0.1f))
        c.processLocation(fix(0.0051, 5000, speed = 0.1f))
        assertFalse(c.state.value.isMoving)
        // Pointing the right way while stopped changes nothing; neither does moving by a few metres.
        c.processLocation(fix(0.0052, 6000, speed = 0.1f, course = 90f))
        assertTrue(c.state.value.isOffTrack)
        // Moving again re-evaluates it.
        c.processLocation(moving(0.0053, 7000, 90f))
        assertFalse(c.state.value.isOffTrack)
    }

    @Test
    fun noVerdictIsGivenWithinFiftyMetresOfTheEndBeingTravelledToward() {
        val c = coordinator()
        start(c, initial = fix(0.0295, 0, speed = 0f))
        // 55.6 m before the end of the track: there is room for P2.
        c.processLocation(moving(0.0295, 1000, 270f))
        assertTrue(c.state.value.isOffTrack)
        // 33 m before the end: no verdict, the red stays even though the course is right.
        c.processLocation(moving(0.0297, 2000, 90f))
        assertTrue(c.state.value.isOffTrack)
    }

    // ── Targets ─────────────────────────────────────────────────────────────

    @Test
    fun theTargetIsTheFirstStepAfterP3AndReverseTheLastBefore() {
        val forward = coordinator()
        start(forward, initial = moving(0.0125, 0, 90f))
        assertEquals(2, forward.target())

        val reverse = coordinator()
        start(reverse, reverse = true, initial = moving(0.0125, 0, 270f))
        assertEquals(1, reverse.target())
    }

    @Test
    fun passingAStepWhileMovingMovesOnAndShowsTheBanner() {
        val h = Harness()
        val c = coordinator(h)
        start(c, initial = moving(0.0085, 0, 90f))
        assertEquals(1, c.target())
        c.processLocation(moving(0.0095, 1000, 90f)) // P3 at index 9.95: still short of step 2
        assertEquals(1, c.target())
        assertTrue(h.reached.isEmpty())
        c.processLocation(moving(0.0105, 2000, 90f)) // P3 at 10.95: step 2 is behind
        assertEquals(2, c.target())
        assertEquals(listOf("Step 2"), h.reached)
        assertEquals("Step 2", c.state.value.recentlyReachedStepName)
        assertEquals(SavedNavigationState("i1", false, 2), h.persistence.saved)
    }

    @Test
    fun passingAStepInReverseMovesOnToThePreviousOne() {
        val h = Harness()
        val c = coordinator(h)
        start(c, reverse = true, initial = moving(0.0115, 0, 270f))
        assertEquals(1, c.target())
        c.processLocation(moving(0.0105, 1000, 270f)) // P3 at 10.05
        assertEquals(1, c.target())
        c.processLocation(moving(0.0095, 2000, 270f)) // P3 at 9.05: step 2 (at 10) is behind
        assertEquals(0, c.target())
        assertEquals(listOf("Step 2"), h.reached)
    }

    @Test
    fun aManualTargetIsKeptWhileItIsStillAheadOfP3() {
        val h = Harness()
        val c = coordinator(h)
        start(c, initial = moving(0.0045, 0, 90f))
        assertEquals(1, c.target())
        c.selectStep(2)
        assertEquals(2, c.target())
        c.processLocation(moving(0.0125, 1000, 90f)) // auto would be step 3 (index 12.95 > 10); step 3 is the manual one at 20
        assertEquals(2, c.target())
        c.processLocation(moving(0.0195, 2000, 90f)) // P3 at 19.95: step at 20 is still ahead
        assertEquals(2, c.target())
        assertTrue(h.reached.isEmpty())
        c.processLocation(moving(0.0205, 3000, 90f)) // passed: the automatic choice resumes
        assertEquals(3, c.target())
        assertEquals(listOf("Step 3"), h.reached)
    }

    @Test
    fun aManualTargetBehindChangesTheDirectionAndSurvivesUntilPassed() {
        val h = Harness()
        val c = coordinator(h)
        start(c, initial = moving(0.0125, 0, 90f)) // target step 3
        c.selectPreviousStep()
        assertEquals(1, c.target())
        assertTrue(c.state.value.isReverse)
        assertEquals(SavedNavigationState("i1", true, 1), h.persistence.saved)
        // Heading back: green now, and the step is kept ahead.
        c.processLocation(moving(0.0115, 1000, 270f))
        assertEquals(1, c.target())
        assertFalse(c.state.value.isOffTrack)
    }

    @Test
    fun theFirstFixPicksTheTargetFromTheClosestTrackPoint() {
        val forward = coordinator()
        start(forward)
        forward.processLocation(fix(-0.002))
        assertEquals(0, forward.target())

        val c = coordinator()
        start(c)
        c.processLocation(fix(0.0125, lat = 0.0001))
        assertEquals(2, c.target())

        // Beyond the last step, forward keeps the last, reverse picks the one before the user.
        val end = coordinator()
        start(end)
        end.processLocation(fix(0.0305))
        assertEquals(3, end.target())
        val back = coordinator()
        start(back, reverse = true)
        back.processLocation(fix(0.0305))
        assertEquals(3, back.target())
        val before = coordinator()
        start(before, reverse = true)
        before.processLocation(fix(-0.002))
        assertEquals(0, before.target())
    }

    @Test
    fun farFromTheTrackTheNavigationStartsAtTheBeginning() {
        // 10 km north: still the closest point decides.
        val near = coordinator()
        start(near)
        near.processLocation(fix(0.0125, lat = 0.09))
        assertEquals(2, near.target())
        assertEquals(NavigationMode.ITINERARY, near.state.value.mode) // no token: no road directions

        // 55 km north: more than 30 km from the track.
        val far = coordinator()
        start(far)
        far.processLocation(fix(0.0125, lat = 0.5))
        assertEquals(0, far.target())
        val farReverse = coordinator()
        start(farReverse, reverse = true)
        farReverse.processLocation(fix(0.0125, lat = 0.5))
        assertEquals(3, farReverse.target())
    }

    @Test
    fun aRestoredTargetIsKept() {
        val c = coordinator()
        start(c, index = 2, initial = fix(0.0, speed = 0f))
        assertEquals(2, c.target())
    }

    // ── Out and back ────────────────────────────────────────────────────────

    /** Out along the equator and back 33 m to the north, with steps on both legs. */
    private val uTrack = (0..30).map { Coordinate(0.0, it * 0.001) } + (0..30).map { Coordinate(0.0003, (30 - it) * 0.001) }
    private val uSteps = listOf(
        step(0, 0.010), step(1, 0.020), step(2, 0.030),
        step(3, 0.020, 0.0003), step(4, 0.010, 0.0003), step(5, 0.001, 0.0003),
    )

    @Test
    fun theStepsOfAnOutAndBackLandOnTheirOwnLeg() {
        val t = NavigationTrack.of(uTrack)!!
        val indices = t.stepIndices(uSteps.map { it.coordinate() })
        listOf(10.0, 20.0, 30.0, 41.0, 51.0, 60.0).forEachIndexed { i, expected -> assertEquals("step $i", expected, indices[i]!!, 0.01) }

        // Identical legs: ties go to the earliest segment from the previous step on.
        val same = NavigationTrack.of((0..10).map { Coordinate(0.0, it * 0.001) } + (0..9).map { Coordinate(0.0, (9 - it) * 0.001) })!!
        val onSame = same.stepIndices(listOf(0.003, 0.007, 0.010, 0.007, 0.003).map { Coordinate(0.0, it) })
        listOf(3.0, 7.0, 10.0, 13.0, 17.0).forEachIndexed { i, expected -> assertEquals("step $i", expected, onSame[i]!!, 0.01) }
    }

    @Test
    fun theTargetFollowsTheLegTheUserIsOn() {
        val c = coordinator()
        start(c, stepList = uSteps, trackPoints = uTrack, initial = moving(0.0040, 0, 90f))
        assertEquals(0, c.target()) // outbound, before the first step
        c.processLocation(moving(0.0195, 1000, 90f))
        assertEquals(1, c.target())

        val back = coordinator()
        start(back, stepList = uSteps, trackPoints = uTrack, initial = moving(0.0040, 0, 270f, lat = 0.0003))
        assertEquals(5, back.target()) // on the return leg
    }

    @Test
    fun p1KeepsToTheLegItWasOnUnlessTheOtherIsClearlyCloser() {
        val c = coordinator()
        start(c, stepList = uSteps, trackPoints = uTrack, initial = moving(0.0040, 0, 270f, lat = 0.0003))
        assertEquals(5, c.target())
        // 13 m north of the outbound leg (20 m from the return leg): the outbound one is closer, but
        // by less than 50 m, so P1 stays on the return leg.
        c.processLocation(moving(0.0035, 1000, 270f, lat = 0.00012))
        assertEquals(5, c.target())
        assertFalse(c.state.value.isOffTrack)
    }

    // ── Arrival ─────────────────────────────────────────────────────────────

    @Test
    fun arrivingWhileStoppedAdvancesTheTarget() {
        val h = Harness()
        val c = coordinator(h)
        start(c, initial = fix(0.0050, 0, speed = 0f))
        assertEquals(1, c.target())
        // Stopped 33 m before step 2 (at 0.010).
        c.processLocation(fix(0.0097, 1000, speed = 0f))
        assertEquals(2, c.target())
        assertEquals(listOf("Step 2"), h.reached)
        assertEquals(SavedNavigationState("i1", false, 2), h.persistence.saved)
        assertEquals((0.020 - 0.0097) * 111_195.0, c.state.value.distanceToTargetMeters!!, 15.0)
    }

    @Test
    fun whileStoppedTheTargetDoesNotChangeEvenFarPastIt() {
        val c = coordinator()
        start(c, initial = fix(0.0050, 0, speed = 0f))
        c.processLocation(fix(0.0155, 1000, speed = 0f))
        assertEquals(1, c.target())
        assertEquals((0.0155 - 0.010) * 111_195.0, c.state.value.distanceToTargetMeters!!, 15.0)
    }

    @Test
    fun theFinalStepDoesNotEndTheNavigationWhenStartingNextToIt() {
        val h = Harness()
        val c = coordinator(h)
        start(c, initial = fix(0.0299, 0, speed = 0f))
        assertEquals(3, c.target())
        c.processLocation(fix(0.0300, 1000, speed = 0f))
        assertTrue(c.state.value.isNavigating)
        assertTrue(h.reached.isEmpty())
        // Leaving by 150 m arms it; coming back ends it.
        c.processLocation(fix(0.0285, 2000, speed = 0f))
        assertTrue(c.state.value.isNavigating)
        c.processLocation(fix(0.0299, 3000, speed = 0f))
        assertFalse(c.state.value.isNavigating)
        assertEquals(listOf("Step 4"), h.reached)
        assertNull(h.persistence.saved)
        assertEquals("Navigation stopped: final step reached (itinerary i1, target 3, moving false)", h.logs.single())
    }

    @Test
    fun goingPastTheStepBeforeTheFinalOneArmsIt() {
        val h = Harness()
        val c = coordinator(h)
        start(c, initial = moving(0.0195, 0, 90f))
        assertEquals(2, c.target())
        c.processLocation(moving(0.0205, 1000, 90f))
        assertEquals(3, c.target())
        c.processLocation(moving(0.0300, 2000, 90f))
        assertFalse(c.state.value.isNavigating)
        assertTrue(h.reached.contains("Step 4"))
    }

    @Test
    fun aFinalStepFirstSeenCloseByWhileMovingDoesNotEndTheNavigation() {
        val c = coordinator()
        start(c, initial = moving(0.0297, 0, 90f))
        assertEquals(3, c.target())
        assertTrue(c.state.value.isNavigating)
    }

    @Test
    fun aLoopWhoseLastStepIsItsFirstDoesNotEndAtTheStart() {
        // A square loop of about 444 m a side, steps at the corners, the last at the start.
        val side = 0.004
        val loop = listOf(
            Coordinate(0.0, 0.0), Coordinate(0.0, side), Coordinate(side, side), Coordinate(side, 0.0), Coordinate(0.0, 0.0),
        )
        val loopSteps = loop.mapIndexed { i, p -> step(i, p.longitude, p.latitude) }

        // At the start the first step is reached, not the last.
        val first = coordinator()
        start(first, stepList = loopSteps, trackPoints = loop, initial = fix(0.0, 0, speed = 0f))
        assertTrue(first.state.value.isNavigating)
        assertEquals(1, first.target())

        // With the last step as the target (a restored session), standing on it does not end the loop...
        val h = Harness()
        val c = coordinator(h)
        start(c, stepList = loopSteps, trackPoints = loop, index = 4, initial = fix(0.0, 0, speed = 0f))
        assertTrue(c.state.value.isNavigating)
        c.processLocation(fix(0.0, 1000, speed = 0f))
        assertTrue(c.state.value.isNavigating)
        // ...until the user has been 100 m away and comes back.
        c.processLocation(fix(0.0015, 2000, speed = 0f))
        c.processLocation(fix(0.0, 3000, speed = 0f))
        assertFalse(c.state.value.isNavigating)
        assertEquals(listOf("Step 5"), h.reached)
    }

    // ── Distance ────────────────────────────────────────────────────────────

    @Test
    fun theDistanceIsMeasuredAlongTheTrackPlusTheGapToIt() {
        val c = coordinator()
        start(c, initial = fix(0.0050, 0, speed = 0f, lat = 0.0005)) // 55.6 m north of the track
        val expected = (0.010 - 0.0050) * 111_195.0 + 0.0005 * 111_195.0
        assertEquals(expected, c.state.value.distanceToTargetMeters!!, 3.0)

        // Beyond the target the distance counts back along the track.
        c.processLocation(fix(0.0120, 1000, speed = 0f))
        assertEquals(1, c.target())
        assertEquals(0.002 * 111_195.0, c.state.value.distanceToTargetMeters!!, 3.0)
    }

    @Test
    fun withoutATrackTheDistanceIsTheStraightLine() {
        val single = listOf(step(0, 0.010))
        val c = coordinator()
        start(c, stepList = single, trackPoints = emptyList(), initial = fix(0.0, 0, speed = 0f, lat = 0.0))
        assertEquals(0.010 * 111_195.0, c.state.value.distanceToTargetMeters!!, 3.0)
        assertNotNull(c.state.value.bearingToTargetDegrees)
        assertEquals(90.0, c.state.value.bearingToTargetDegrees!!, 0.5)
    }

    // ── Reverse offer ───────────────────────────────────────────────────────

    @Test
    fun theReverseOfferTurnsOnAfterThirtyMetresOverTheLowAndOffAfterFifteenFromThePeak() {
        val c = coordinator()
        start(c, initial = moving(0.0125, 0, 90f)) // target step 3 at 0.020; anchor 834 m
        assertFalse(c.state.value.isMovingAwayFromTarget)
        c.processLocation(moving(0.0126, 1000, 90f)) // 823 m: a new low
        c.processLocation(moving(0.0124, 2000, 90f)) // 845 m: +22
        assertFalse(c.state.value.isMovingAwayFromTarget)
        c.processLocation(moving(0.0123, 3000, 90f)) // 856 m: +33 over the low
        assertTrue(c.state.value.isMovingAwayFromTarget)
        // The peak is tracked; a 11 m drop is not enough, a 22 m one is.
        c.processLocation(moving(0.0122, 4000, 90f)) // 867 m: new peak
        c.processLocation(moving(0.0123, 5000, 90f)) // 856 m: -11
        assertTrue(c.state.value.isMovingAwayFromTarget)
        c.processLocation(moving(0.0124, 6000, 90f)) // 845 m: -22
        assertFalse(c.state.value.isMovingAwayFromTarget)
    }

    @Test
    fun theReverseOfferIsOnlyFedWhileMovingAndResetByAStopAndATargetChange() {
        val c = coordinator()
        start(c, initial = fix(0.0125, 0, speed = 0f))
        c.processLocation(fix(0.0100, 1000, speed = 0f)) // far from "closer": stopped, nothing fed
        c.processLocation(fix(0.0050, 2000, speed = 0f))
        assertFalse(c.state.value.isMovingAwayFromTarget)

        val m = coordinator()
        start(m, initial = moving(0.0125, 0, 90f))
        m.processLocation(moving(0.0123, 1000, 90f))
        m.processLocation(moving(0.0121, 2000, 90f))
        assertTrue(m.state.value.isMovingAwayFromTarget)
        // Coming to a stop clears the anchor but not the flag; a new target clears both.
        m.selectStep(3)
        assertFalse(m.state.value.isMovingAwayFromTarget)
    }

    @Test
    fun reversingWhileMovingRetargetsFromP3AndRederivesTheStatus() {
        val h = Harness()
        val c = coordinator(h)
        start(c, initial = moving(0.0125, 0, 90f))
        assertEquals(2, c.target())
        assertFalse(c.state.value.isOffTrack)
        c.reverseDirection()
        assertTrue(c.state.value.isReverse)
        assertEquals(1, c.target()) // the last step at or before P3 (index 12.95)
        assertTrue(c.state.value.isOffTrack) // still heading east, now against the direction
        assertFalse(c.state.value.isMovingAwayFromTarget)
        assertEquals(SavedNavigationState("i1", true, 1), h.persistence.saved)
        assertEquals((0.0125 - 0.010) * 111_195.0, c.state.value.distanceToTargetMeters!!, 15.0)

        // Turning round: green.
        c.processLocation(moving(0.0120, 1000, 270f))
        assertFalse(c.state.value.isOffTrack)

        c.reverseDirection()
        assertFalse(c.state.value.isReverse)
        assertEquals(2, c.target())
    }

    @Test
    fun reversingWhileStoppedRetargetsFromP1() {
        val c = coordinator()
        start(c, initial = fix(0.0125, 0, speed = 0f))
        assertEquals(2, c.target())
        c.reverseDirection()
        assertEquals(1, c.target())
        assertTrue(c.state.value.isReverse)
    }

    // ── Steps without a position ────────────────────────────────────────────

    private val gappedSteps = listOf(step(0, 0.0), step(1, null), step(2, 0.020), step(3, 0.030))

    @Test
    fun stepsWithoutAPositionAreSkippedWhenChoosingTheTarget() {
        val c = coordinator()
        start(c, stepList = gappedSteps)
        c.processLocation(fix(0.0050))
        assertEquals(2, c.target())
        val t = NavigationTrack.of(track)!!
        assertNull(t.stepIndices(gappedSteps.map { it.coordinate() })[1])
    }

    @Test
    fun aTargetWithoutAPositionMovesToTheNearestOneInTheTravelDirection() {
        val forward = coordinator()
        start(forward, stepList = gappedSteps, index = 1, initial = fix(0.0050, 0, speed = 0f))
        assertEquals(2, forward.target())

        val reverse = coordinator()
        start(reverse, stepList = gappedSteps, reverse = true, index = 1, initial = fix(0.0050, 0, speed = 0f))
        assertEquals(0, reverse.target())
    }

    @Test
    fun theNavigationEndsWhenNoStepHasAPosition() {
        val h = Harness()
        val c = coordinator(h)
        start(c, stepList = listOf(step(0, null), step(1, null)), index = 0, initial = fix(0.0050, 0, speed = 0f))
        assertFalse(c.state.value.isNavigating)
        assertEquals("Navigation stopped: no step has a position (itinerary i1, target 0, moving false)", h.logs.single())
    }

    // ── Needle ──────────────────────────────────────────────────────────────

    @Test
    fun theDeviceHeadingIsTheCourseWhileMovingAndTheCompassWhenStopped() {
        val moving = NavigationState(
            isMoving = true, heading = 80f, travelBearing = 70.0,
            currentLocation = fix(0.0, 0, speed = 5f, course = 100f),
        )
        assertEquals(100.0, moving.deviceHeading(30.0)!!, 1e-9)
        assertEquals(70.0, moving.copy(currentLocation = fix(0.0, 0)).deviceHeading(30.0)!!, 1e-9)
        assertEquals(80.0, moving.copy(currentLocation = fix(0.0, 0), travelBearing = null).deviceHeading(30.0)!!, 1e-9)
        assertEquals(30.0, moving.copy(currentLocation = fix(0.0, 0), travelBearing = null, heading = null).deviceHeading(30.0)!!, 1e-9)
        assertNull(moving.copy(currentLocation = null, travelBearing = null, heading = null).deviceHeading())

        val stopped = moving.copy(isMoving = false)
        assertEquals(80.0, stopped.deviceHeading(30.0)!!, 1e-9)
        assertEquals(30.0, stopped.copy(heading = null).deviceHeading(30.0)!!, 1e-9)
    }

    @Test
    fun theNeedleFollowsTheTrackAheadWhileMovingAndTheTargetWhenStopped() {
        val c = coordinator()
        start(c, initial = fix(0.0050, 0, speed = 0f, lat = 0.0005)) // 55 m north, stopped
        val stopped = c.state.value
        // Stopped: the bearing to the step (east and a little south).
        assertEquals(stopped.bearingToTargetDegrees!!, stopped.relativeBearingToTarget()!!, 1e-9)
        assertEquals(TrackNavigationMath.relativeBearing(stopped.bearingToTargetDegrees!!, 30.0), stopped.relativeBearingToTarget(30.0)!!, 1e-9)
        assertEquals(c.state.value.currentTargetStep!!.coordinate(), stopped.needleTargetPoint)

        c.processLocation(moving(0.0051, 1000, 90f, lat = 0.0005))
        val s = c.state.value
        // Moving: the point 100 m ahead on the track.
        assertEquals(s.lookaheadPoint, s.needleTargetPoint)
        assertEquals(TrackNavigationMath.relativeBearing(s.trackBearingDegrees!!, 90.0), s.relativeBearingToTarget()!!, 1e-9)
        assertTrue(s.relativeBearingToTarget()!! > 0) // the track ahead is south of east: to the right
    }

    @Test
    fun theCrossTrackSideIsReportedBeyondTwentyMetres() {
        val c = coordinator()
        start(c, initial = fix(0.0050, 0, speed = 0f, lat = 0.0005)) // north of an east-bound track: left
        assertEquals(CrossTrackSide.LEFT, c.state.value.crossTrackSide)
        assertEquals(55.6, c.state.value.crossTrackDistanceMeters!!, 1.0)
        c.processLocation(fix(0.0050, 1000, speed = 0f, lat = 0.00005))
        assertEquals(CrossTrackSide.NONE, c.state.value.crossTrackSide)
        c.processLocation(fix(0.0050, 2000, speed = 0f, lat = -0.0005))
        assertEquals(CrossTrackSide.RIGHT, c.state.value.crossTrackSide)
    }
}
