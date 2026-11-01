package ch.overlandmap.map.ui.social

import ch.overlandmap.map.geo.Geohash
import ch.overlandmap.map.model.ContributedWaypoint
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SocialTargetTest {
    @Test fun pointTargetsUseTheirStoredGeohash() {
        val step = ItineraryStep("step", "itinerary", "pack", 1, "Step", geohash = "u09tunquc")
        val waypoint = Waypoint(documentId = "waypoint", trackPackId = "pack", name = "Waypoint", geohash = "u09tunquc")
        val contributed = ContributedWaypoint(documentId = "shared", trackPackId = "pack", itinDocumentId = "itinerary", geohash = "u09tunquc")

        assertEquals("u09tunquc", step.socialTarget().geohash)
        assertEquals("u09tunquc", waypoint.socialTarget().geohash)
        assertEquals("u09tunquc", contributed.socialTarget().geohash)
        assertEquals("pack", contributed.socialTarget().trackPackId)
        assertEquals("contributed_waypoint", contributed.socialTarget().collection)
    }

    @Test fun coordinatesProvideAHashWhenTheObjectHasNone() {
        val waypoint = Waypoint(documentId = "waypoint", trackPackId = "pack", name = "Waypoint", lat = 46.2, lon = 6.1)
        assertEquals(Geohash.encode(46.2, 6.1), waypoint.socialTarget().geohash)
        val missing = Waypoint(documentId = "missing", trackPackId = "pack", name = "Missing")
        assertNull(missing.socialTarget().geohash)
    }
}
