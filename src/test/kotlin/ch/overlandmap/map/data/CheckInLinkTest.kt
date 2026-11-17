package ch.overlandmap.map.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CheckInLinkTest {
    @Test fun pointCheckInStoresGeohashAndPackMetadataButNoObjectId() {
        val fields = checkInLinkFields("pack", "itinerary", "itinerary/itinerary/steps", "u09tunquc")
        assertEquals("u09tunquc", fields["geohash"])
        assertEquals("pack", fields["trackPackId"])
        assertEquals("itinerary", fields["itinDocumentId"])
        assertFalse(fields.containsKey("objectId"))
    }

    @Test fun importedPointDoesNotGetEmptyPackMetadata() {
        val fields = checkInLinkFields("", "", "imported_waypoint", "u09tunquc")
        assertEquals(mapOf("geohash" to "u09tunquc", "collection" to "imported_waypoint"), fields)
    }

    @Test(expected = IllegalArgumentException::class)
    fun pointWithoutLocationCannotFallBackToAnObjectId() {
        checkInLinkFields("pack", "itinerary", "waypoint", null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun countryCannotBeCheckedInEvenIfALocationIsProvided() {
        checkInLinkFields("", "", "country", "u09tunquc")
    }
}
