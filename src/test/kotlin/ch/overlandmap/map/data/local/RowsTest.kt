package ch.overlandmap.map.data.local

import ch.overlandmap.map.model.CheckIn
import ch.overlandmap.map.model.CheckInReason
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.ContributedWaypoint
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Discussion
import ch.overlandmap.map.model.GlobalSetting
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.Vote
import ch.overlandmap.map.model.Zone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rows keep the queryable columns next to `json`; a model must survive the trip unchanged. */
class RowsTest {

    @Test
    fun checkInRoundTripKeepsColumnsAndExtras() {
        val checkIn = CheckIn.fromFirestore(
            "c",
            mapOf(
                "objectId" to "o", "trackPackId" to "p", "itinDocumentId" to "i", "reason" to "closed",
                "userId" to "u", "userName" to "Ann", "content" to "gate shut", "collection" to "x",
                "createdAt" to mapOf("_seconds" to 1_700_000_000L, "_nanoseconds" to 0L),
            ),
        )!!
        val row = checkIn.toRow()
        assertEquals("closed", row.reason)
        assertEquals(1_700_000_000_000L, row.createdAt)
        // userName/content/collection live in json, not in columns (iOS schema).
        assertTrue(row.json!!.contains("gate shut"))
        assertEquals(checkIn, row.toModel())
        assertEquals(CheckInReason.CLOSED, row.toModel()!!.reason)
    }

    @Test
    fun voteRoundTripStoresUpVoteAsInteger() {
        val vote = Vote.fromFirestore("v", mapOf("upVote" to false, "objectId" to "o", "trackPackId" to "p", "userName" to "Bo"))
        val row = vote.toRow()
        assertEquals(0, row.upVote)
        assertEquals(vote, row.toModel())
        assertFalse(row.toModel().isUpVote)
        assertEquals("Bo", row.toModel().userName)
    }

    @Test
    fun contributedWaypointRoundTrip() {
        val w = ContributedWaypoint.fromFirestore(
            "w",
            mapOf(
                "trackPackId" to "p", "name" to "Camp", "lat" to 46.0, "lon" to 7.0, "isBivouac" to true,
                "active" to true, "type" to "camp", "userId" to "u", "lastUpdate" to 1_700_000_000_000L,
                "translatedName" to mapOf("fr" to "Bivouac"), "content" to "nice",
            ),
        )
        val row = w.toRow()
        assertEquals(1, row.active)
        assertEquals("camp", row.type)
        val back = row.toModel()
        assertEquals(w, back)
        assertTrue(back.isBivouac)
        assertEquals("Bivouac", back.name("fr"))
    }

    @Test
    fun discussionRoundTripKeepsTopicString() {
        val d = Discussion.fromFirestore(
            "d", mapOf("objectId" to "o", "topic" to "bordercrossing", "reason" to "borderCrossing", "content" to "x", "createdAt" to 1_700_000_000_000L),
        )
        val row = d.toRow()
        assertEquals("borderCrossing", row.topic)
        val back = row.toModel()
        assertEquals(d.topic, back.topic)
        assertEquals("x", back.content)
        assertTrue(back.isCrossingReport)
        assertTrue(back.matches("bordercrossing"))
    }

    @Test
    fun commentRoundTrip() {
        val c = Comment.fromFirestore(
            "c",
            mapOf(
                "objectId" to "o", "content" to "nice", "rating" to 4L, "userName" to "Ann", "trackPackId" to "p",
                "itinDocumentId" to "i", "translations" to mapOf("fr" to "joli"), "createdAt" to 1_700_000_000_000L,
            ),
        )
        val row = c.toRow()
        assertEquals(4, row.rating)
        assertEquals("i", row.itinDocumentId)
        assertEquals(c, row.toModel())
    }

    @Test
    fun countryRoundTripPromotesStatusColumns() {
        val c = Country.fromFirestore(
            "FRA",
            mapOf(
                "name" to "France", "iso_a2" to "FR", "overlanding" to 1L, "carnet" to -1L, "visa" to 2L,
                "comment" to "x", "comment_translations" to mapOf("fr" to "y"), "money" to "Euro",
                "insurance_scheme" to "green_card", "bounding_box" to listOf(-5.0, 41.0, 9.5, 51.0),
                "languages" to listOf("fr"), "borders" to mapOf("ES" to "Spain"), "updatedAt" to 1_700_000_000_000L,
            ),
        )
        val row = c.toRow()
        assertEquals("FR", row.isoA2)
        assertEquals(1, row.overlanding)
        assertEquals(-1, row.carnet)
        assertEquals(2, row.visa)
        assertEquals(1_700_000_000_000L, row.updatedAt)
        assertEquals(c, row.toModel())
    }

    @Test
    fun countryRowWithoutStatusesReadsAsOpenNotRequired() {
        val row = CountryRow("X", "X", null, null, null, null, null, null)
        val c = row.toModel()
        assertEquals(3, c.overlanding)
        assertEquals(0, c.carnet)
    }

    @Test
    fun borderAndPostRoundTrip() {
        val b = CountryBorder.fromFirestore(
            "b", mapOf("name" to "A-B", "country1" to "A", "country2" to "B", "is_open" to 1L, "border_posts" to listOf("p"), "comment" to "c"),
        )
        val row = b.toRow()
        assertEquals(1, row.isOpen)
        assertEquals("A", row.country1)
        assertEquals(b, row.toModel())

        val p = BorderPost.fromFirestore("p", mapOf("name" to "Post", "is_open" to 4L, "borderId" to "b", "lat" to 1.0, "lon" to 2.0))
        val pr = p.toRow()
        assertEquals("b", pr.borderDocId)
        assertEquals(p.geohash, pr.geohash)
        assertEquals(p, pr.toModel())
    }

    @Test
    fun zoneAndSettingRoundTrip() {
        val z = Zone.fromFirestore("z", mapOf("name" to "Z", "country" to "IN", "type" to 2L, "comment" to "c", "bounding_box" to listOf(1.0, 2.0, 3.0, 4.0)))
        val row = z.toRow()
        assertEquals(2, row.type)
        assertEquals(z, row.toModel())

        val s = GlobalSetting.fromFirestore("advisory_url_template", mapOf("gbr_en" to "u", "updatedAt" to 1_700_000_000_000L))
        assertEquals(s, s.toRow().toModel())
    }

    @Test
    fun datesAreStoredAsIsoText() {
        val pack = TrackPack.fromFirestore("p", mapOf("name" to "P")).copy(lastUpdateCheck = 1_700_000_000_000L)
        val row = pack.toRow()
        assertEquals("2023-11-14T22:13:20Z", row.lastVersionCheck)
        assertEquals(1_700_000_000_000L, row.toModel().lastUpdateCheck)

        val it = Itinerary.fromFirestore("i", mapOf("name" to "I", "lengthDays" to 2L)).copy(lastOpenedAt = 1_700_000_000_999L)
        val itRow = it.toRow()
        // Second precision, as iOS' ISO8601DateFormatter: the millis are dropped.
        assertEquals("2023-11-14T22:13:20Z", itRow.lastSeen)
        assertEquals(2, itRow.lengthDays)
        assertEquals(1_700_000_000_000L, itRow.toModel().lastOpenedAt)
        assertNull(IsoDate.parse("not a date"))
    }

    @Test
    fun isoDatesSortChronologically() {
        val a = IsoDate.format(1_700_000_000_000L)
        val b = IsoDate.format(1_700_000_000_000L + 1_000)
        val c = IsoDate.format(1_700_000_000_000L + 60_000)
        assertTrue(a < b && b < c)
    }

    @Test
    fun freeSampleFlagLivesInJson() {
        val pack = TrackPack.fromFirestore("p", mapOf("name" to "P")).copy(isFreeSample = true)
        assertTrue(pack.toRow().json!!.contains("isFreeSample"))
        assertTrue(pack.toRow().toModel().isFreeSample)
        assertFalse(pack.copy(isFreeSample = false).toRow().toModel().isFreeSample)
    }
}
