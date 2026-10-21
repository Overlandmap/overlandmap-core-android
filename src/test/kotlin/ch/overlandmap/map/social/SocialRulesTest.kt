package ch.overlandmap.map.social

import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.model.CheckInReason
import ch.overlandmap.map.model.Vote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocialRulesTest {

    private fun assertCoord(lat: Double, lon: Double, c: Coordinate?) {
        assertTrue("null for $lat,$lon", c != null)
        assertEquals(lat, c!!.latitude, 1e-6)
        assertEquals(lon, c.longitude, 1e-6)
    }

    // ── Coordinate parsing ──────────────────────────────────────────────────

    @Test
    fun parsesSignedDecimalPairs() {
        assertCoord(46.5, 7.25, CoordinateParser.parse("46.5, 7.25"))
        assertCoord(-33.9, 151.2, CoordinateParser.parse("(-33.9 151.2)"))
        assertCoord(46.5, 7.25, CoordinateParser.parse("[46.5; 7.25]"))
        assertCoord(46.5, 7.25, CoordinateParser.parse("  46.5 7.25\n"))
    }

    @Test
    fun parsesCardinalsBeforeOrAfter() {
        assertCoord(46.5, 7.25, CoordinateParser.parse("46.5N 7.25E"))
        assertCoord(46.5, -7.25, CoordinateParser.parse("46.5 N, 7.25 W"))
        assertCoord(-46.5, 7.25, CoordinateParser.parse("S 46.5 E 7.25"))
        assertCoord(46.5, 7.25, CoordinateParser.parse("E 7.25 N 46.5"))
    }

    @Test
    fun parsesDegreesMinutesSeconds() {
        assertCoord(46.505, 7.25, CoordinateParser.parse("46°30'18\"N 7°15'E"))
        assertCoord(-46.5, -7.25, CoordinateParser.parse("46 30 S 7 15 W"))
        assertCoord(46.5, 7.25, CoordinateParser.parse("N 46 30 E 7 15"))
    }

    @Test
    fun rejectsNonsenseAndOutOfRange() {
        assertNull(CoordinateParser.parse(""))
        assertNull(CoordinateParser.parse("hello"))
        assertNull(CoordinateParser.parse("46.5"))
        assertNull(CoordinateParser.parse("91, 0"))
        assertNull(CoordinateParser.parse("0, 181"))
        assertNull(CoordinateParser.parse("46.5N 7.25N"))
    }

    // ── Pack bounds and corrections ─────────────────────────────────────────

    private val bounds = GeoBounds(latMin = 30.0, latMax = 31.0, lonMin = 77.0, lonMax = 78.0)

    @Test
    fun packBoundsGrowByFiftyKilometres() {
        assertTrue(SocialRules.isWithinPack(bounds, Coordinate(30.5, 77.5)))
        assertTrue(SocialRules.isWithinPack(bounds, Coordinate(31.3, 77.5))) // ~33 km north
        assertFalse(SocialRules.isWithinPack(bounds, Coordinate(32.0, 77.5)))
        assertTrue(SocialRules.isWithinPack(null, Coordinate(-50.0, 0.0)))
    }

    @Test
    fun correctionValidation() {
        val v = SocialRules::validateCorrection
        assertEquals(SocialRules.CorrectionError.REASON_REQUIRED, v(null, "x", "", null, bounds))
        assertEquals(SocialRules.CorrectionError.CONTENT_REQUIRED, v(CheckInReason.CLOSED, "  ", "", null, bounds))
        assertNull(v(CheckInReason.CLOSED, "gate shut", "", null, bounds))
        assertEquals(SocialRules.CorrectionError.LOCATION_REQUIRED, v(CheckInReason.WRONG_LOCATION, "x", " ", null, bounds))
        assertEquals(SocialRules.CorrectionError.LOCATION_INVALID, v(CheckInReason.WRONG_LOCATION, "x", "nowhere", null, bounds))
        assertEquals(SocialRules.CorrectionError.LOCATION_OUTSIDE, v(CheckInReason.WRONG_LOCATION, "x", "10, 10", null, bounds))
        assertNull(v(CheckInReason.WRONG_LOCATION, "x", "30.5, 77.5", null, bounds))
        assertNull(v(CheckInReason.WRONG_LOCATION, "x", "", Coordinate(30.5, 77.5), bounds))
        assertEquals(5, SocialRules.correctionReasons.size)
    }

    @Test
    fun waypointValidationIncludingDuplicateGuard() {
        val others = listOf(SocialRules.KnownPoint("Km 12 fuel", Coordinate(30.5, 77.5)))
        fun check(name: String, content: String, loc: String) =
            SocialRules.validateWaypoint(name, content, loc, null, bounds, others)
        assertEquals(SocialRules.WaypointError.FIELDS_REQUIRED, check("", "c", "30.6, 77.6").error)
        assertEquals(SocialRules.WaypointError.FIELDS_REQUIRED, check("n", "c", "").error)
        assertEquals(SocialRules.WaypointError.LOCATION_INVALID, check("n", "c", "??").error)
        assertEquals(SocialRules.WaypointError.LOCATION_OUTSIDE, check("n", "c", "0, 0").error)
        // 0.0001° is about 11 m from the fuel point.
        val dup = check("n", "c", "30.5001, 77.5")
        assertEquals(SocialRules.WaypointError.DUPLICATE, dup.error)
        assertEquals("Km 12 fuel", dup.duplicateOf)
        assertNull(check("n", "c", "30.6, 77.6").error)
    }

    // ── Comments and votes ──────────────────────────────────────────────────

    @Test
    fun commentValidationAndPermissions() {
        assertEquals(SocialRules.CommentError.TEXT_REQUIRED, SocialRules.validateComment(" ", 3))
        assertEquals(SocialRules.CommentError.RATING_REQUIRED, SocialRules.validateComment("ok", 0))
        assertNull(SocialRules.validateComment("ok", 1))
        assertTrue(SocialRules.canAddComment(signedIn = true, hasOwn = false))
        assertFalse(SocialRules.canAddComment(signedIn = true, hasOwn = true))
        assertFalse(SocialRules.canAddComment(signedIn = false, hasOwn = false))
        assertTrue(SocialRules.canDeleteComment("u1", "u1", false))
        assertFalse(SocialRules.canDeleteComment("u1", "u2", false))
        assertTrue(SocialRules.canDeleteComment("u1", "u2", true))
        assertFalse(SocialRules.canDeleteComment(null, null, false))
    }

    @Test
    fun votingTogglesLikeIos() {
        val up = Vote("v", isUpVote = true)
        assertTrue(SocialRules.voteAction(null, true) is SocialRules.VoteAction.Create)
        assertTrue(SocialRules.voteAction(up, true) is SocialRules.VoteAction.Delete)
        assertTrue(SocialRules.voteAction(up, false) is SocialRules.VoteAction.Update)
    }
}
