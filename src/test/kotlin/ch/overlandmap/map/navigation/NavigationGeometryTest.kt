package ch.overlandmap.map.navigation

import ch.overlandmap.map.geo.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationGeometryTest {

    private val line = (0..10).map { Coordinate(0.0, it * 0.001) }

    // ── NavigationTrack ─────────────────────────────────────────────────────

    @Test
    fun aTrackNeedsTwoPoints() {
        assertNull(NavigationTrack.of(emptyList()))
        assertNull(NavigationTrack.of(listOf(Coordinate(0.0, 0.0))))
        assertNotNull(NavigationTrack.of(line.take(2)))
    }

    @Test
    fun projectionGivesTheIndexTheDistanceAlongAndTheCrossDistance() {
        val t = NavigationTrack.of(line)!!
        assertEquals(1112.0, t.totalLength, 2.0)
        val p = t.project(Coordinate(0.0005, 0.0035))!!
        assertEquals(3, p.segmentIndex)
        assertEquals(0.5, p.fraction, 0.01)
        assertEquals(3.5, p.index, 0.01)
        assertEquals(3.5 * 111.2, p.distanceAlongTrack, 1.0)
        assertEquals(55.6, p.crossDistance, 1.0)
        // Beyond the ends it is clamped.
        assertEquals(0.0, t.project(Coordinate(0.0, -0.01))!!.index, 1e-9)
        assertEquals(10.0, t.project(Coordinate(0.0, 0.02))!!.index, 1e-9)
    }

    @Test
    fun theSearchCanBeRestrictedToSomeSegments() {
        val t = NavigationTrack.of(line)!!
        val p = t.project(Coordinate(0.0, 0.0035), segments = 6..8)!!
        assertEquals(6.0, p.index, 1e-9)
        assertNull(t.project(Coordinate(0.0, 0.0035), segments = 20..30))
        // A range reaching outside the track is clamped to it.
        assertEquals(3.5, t.project(Coordinate(0.0, 0.0035), segments = -5..50)!!.index, 0.01)
    }

    @Test
    fun segmentsAroundAPositionCoverTheWindow() {
        val t = NavigationTrack.of(line)!!
        // Segments are ~111 m long: 300 m either side of 555 m (index 5) reaches about 2.3 to 7.7.
        assertEquals(2..7, t.segments(555.0, 300.0, 300.0))
        assertEquals(0..1, t.segments(0.0, 1000.0, 150.0))
        assertEquals(9..9, t.segments(t.totalLength, 0.0, 1000.0))
        assertEquals(9, t.segments(t.totalLength, 10.0, 10.0).last)
    }

    @Test
    fun distanceAtIndexInterpolatesAndClamps() {
        val t = NavigationTrack.of(line)!!
        assertEquals(0.0, t.distanceAtIndex(-3.0), 1e-9)
        assertEquals(555.9, t.distanceAtIndex(5.0), 1.0)
        assertEquals(5.5 * 111.2, t.distanceAtIndex(5.5), 1.0)
        assertEquals(t.totalLength, t.distanceAtIndex(10.0), 1e-6)
        assertEquals(t.totalLength, t.distanceAtIndex(99.0), 1e-6)
    }

    @Test
    fun theEarliestSegmentWithinTheToleranceWins() {
        // Out and back on the same road: the two legs are equally close.
        val t = NavigationTrack.of((0..10).map { Coordinate(0.0, it * 0.001) } + (0..9).map { Coordinate(0.0, (9 - it) * 0.001) })!!
        val strict = t.project(Coordinate(0.00001, 0.004))!!
        assertTrue(strict.index < 11)
        // 5 m closer to the return leg, but inside the 25 m tolerance: the outbound leg is kept.
        val near = t.project(Coordinate(0.00001, 0.004), preferEarliestWithin = 25.0)!!
        assertEquals(4.0, near.index, 0.01)
    }

    @Test
    fun offsetMovesAlongABearing() {
        val moved = NavigationTrack.offset(Coordinate(0.0, 0.0), 90.0, 50.0)
        assertEquals(50.0, Coordinate(0.0, 0.0).distanceMeters(moved), 0.5)
        assertEquals(90.0, Coordinate(0.0, 0.0).bearing(moved), 0.1)
        val north = NavigationTrack.offset(Coordinate(60.0, 10.0), 0.0, 50.0)
        assertEquals(50.0, Coordinate(60.0, 10.0).distanceMeters(north), 0.5)
        val east = NavigationTrack.offset(Coordinate(60.0, 10.0), 90.0, 50.0)
        assertEquals(50.0, Coordinate(60.0, 10.0).distanceMeters(east), 0.5)
    }

    // ── AutoCenterZone ──────────────────────────────────────────────────────

    @Test
    fun theVisibleHalfIsTheBottomInPortraitAndTheRightInLandscape() {
        assertEquals(ScreenRect(0.0, 1200.0, 1080.0, 2400.0), AutoCenterZone.visibleRect(1080.0, 2400.0, landscape = false))
        assertEquals(ScreenRect(1200.0, 0.0, 2400.0, 1080.0), AutoCenterZone.visibleRect(2400.0, 1080.0, landscape = true))
    }

    @Test
    fun theStableRectIsInsetByTenPercentOfTheFullMapSize() {
        // Portrait 1080 x 2400: 108 px sideways and 240 px up and down inside the bottom half.
        assertEquals(ScreenRect(108.0, 1440.0, 972.0, 2160.0), AutoCenterZone.stableRect(1080.0, 2400.0, landscape = false))
        // Landscape 2400 x 1080: 240 px sideways and 108 px up and down inside the right half.
        assertEquals(ScreenRect(1440.0, 108.0, 2160.0, 972.0), AutoCenterZone.stableRect(2400.0, 1080.0, landscape = true))
    }

    @Test
    fun theUserIsLeftAloneInsideTheStableRectAndBroughtBackOutsideIt() {
        fun portrait(x: Double, y: Double) = AutoCenterZone.needsRecentering(x, y, 1080.0, 2400.0, landscape = false)
        assertFalse(portrait(540.0, 1800.0)) // the centre of the visible half
        assertFalse(portrait(108.0, 1440.0)) // on the top-left edge: inside
        assertTrue(portrait(540.0, 1439.0)) // just above
        assertTrue(portrait(107.0, 1800.0))
        assertTrue(portrait(972.0, 1800.0)) // the right/bottom edges are outside
        assertTrue(portrait(540.0, 2160.0))
        assertTrue(portrait(540.0, 600.0)) // under the HUD
        assertTrue(portrait(-20.0, 1800.0)) // off the map
        assertTrue(portrait(540.0, 2500.0))

        fun landscape(x: Double, y: Double) = AutoCenterZone.needsRecentering(x, y, 2400.0, 1080.0, landscape = true)
        assertFalse(landscape(1800.0, 540.0))
        assertTrue(landscape(600.0, 540.0)) // under the HUD
        assertTrue(landscape(1439.0, 540.0))
        assertTrue(landscape(1800.0, 107.0))
        assertFalse(landscape(2159.0, 971.0))
        assertTrue(landscape(2160.0, 540.0))
    }

    @Test
    fun aHudBiggerThanHalfTheMapPushesTheVisibleAreaPastIt() {
        // Portrait: the HUD ends at 1500 px, below the middle (1200): the user stays under that line.
        assertEquals(ScreenRect(0.0, 1500.0, 1080.0, 2400.0), AutoCenterZone.visibleRect(1080.0, 2400.0, false, hudBottom = 1500.0))
        assertEquals(1500.0 + 240.0, AutoCenterZone.stableRect(1080.0, 2400.0, false, hudBottom = 1500.0).top, 1e-9)
        assertTrue(AutoCenterZone.needsRecentering(540.0, 1600.0, 1080.0, 2400.0, false, hudBottom = 1500.0))
        assertFalse(AutoCenterZone.needsRecentering(540.0, 1900.0, 1080.0, 2400.0, false, hudBottom = 1500.0))
        // A HUD that stops above the middle changes nothing.
        assertEquals(AutoCenterZone.visibleRect(1080.0, 2400.0, false), AutoCenterZone.visibleRect(1080.0, 2400.0, false, hudBottom = 900.0))
        // Landscape: the HUD ends at 1260 px, past the middle (1200).
        assertEquals(ScreenRect(1260.0, 0.0, 2400.0, 1080.0), AutoCenterZone.visibleRect(2400.0, 1080.0, true, hudRight = 1260.0))
        assertTrue(AutoCenterZone.needsRecentering(1400.0, 540.0, 2400.0, 1080.0, true, hudRight = 1260.0))
    }

    @Test
    fun aDegenerateMapNeverContainsTheUser() {
        assertTrue(AutoCenterZone.needsRecentering(0.0, 0.0, 0.0, 0.0, landscape = false))
    }

    // ── Layout ──────────────────────────────────────────────────────────────

    @Test
    fun theHudSizesFollowTheWindow() {
        assertEquals(480.0, NavigationLayout.hudHeight(1000.0), 1e-9)
        assertEquals(320.0, NavigationLayout.hudHeight(600.0), 1e-9)
        assertEquals(1152.0, NavigationLayout.hudHeight(2400.0), 1e-9)
        assertEquals(376.0, NavigationLayout.landscapeHudWidth(800.0), 1e-9)
        assertEquals(0.0, NavigationLayout.landscapeHudWidth(30.0), 1e-9)
        assertTrue(NavigationLayout.isLandscape(800.0, 400.0))
        assertFalse(NavigationLayout.isLandscape(400.0, 400.0))
        assertFalse(NavigationLayout.isLandscape(400.0, 800.0))
    }
}
