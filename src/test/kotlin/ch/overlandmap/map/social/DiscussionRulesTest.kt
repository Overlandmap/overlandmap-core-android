package ch.overlandmap.map.social

import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.CrossingVehicle
import ch.overlandmap.map.model.Discussion
import com.google.firebase.Timestamp
import com.google.firebase.firestore.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscussionRulesTest {

    private val t0 = 1_700_000_000_000L

    private fun discussion(
        id: String,
        reason: String = "correction",
        topic: String = "comment",
        at: Long = t0,
        thread: String? = null,
        user: String = "u1",
        deleted: Boolean = false,
        extra: Map<String, Any?> = emptyMap(),
    ) = Discussion.fromFirestore(
        id,
        buildMap {
            put("objectId", "obj"); put("reason", reason); put("topic", topic); put("content", "c-$id")
            put("createdAt", at); put("userId", user)
            if (thread != null) put("threadId", thread)
            if (deleted) put("deleted", true)
            putAll(extra)
        },
    )

    @Test
    fun rootsAreNewestFirstAndRepliesOldestFirst() {
        val a = discussion("a", at = t0)
        val b = discussion("b", at = t0 + 2000)
        val r2 = discussion("r2", reason = "followup", thread = "a", at = t0 + 3000)
        val r1 = discussion("r1", reason = "followup", thread = "a", at = t0 + 1000)
        val orphan = discussion("o", reason = "followup", thread = "gone", at = t0 + 500)
        val all = listOf(a, b, r2, r1, orphan)
        assertEquals(listOf("b", "a"), DiscussionRules.roots(all).map { it.documentId })
        assertEquals(listOf("r1", "r2"), DiscussionRules.followups(a, all).map { it.documentId })
        assertEquals(emptyList<String>(), DiscussionRules.followups(b, all).map { it.documentId })
    }

    @Test
    fun aDiscussionWithRepliesCannotBeDeleted() {
        val a = discussion("a")
        val reply = discussion("r", reason = "followup", thread = "a")
        assertFalse(DiscussionRules.canDelete(a, listOf(a, reply)))
        assertTrue(DiscussionRules.canDelete(a, listOf(a)))
        val deletedReply = discussion("r", reason = "followup", thread = "a", deleted = true)
        assertTrue(DiscussionRules.canDelete(a, listOf(a, deletedReply)))
    }

    @Test
    fun fieldDiscussionsMatchTheTopicIgnoringCaseAndSkipDeletedOnes() {
        val visa = discussion("v", topic = "visa")
        val gone = discussion("g", topic = "carnet", deleted = true)
        val all = listOf(visa, gone)
        assertTrue(DiscussionRules.hasDiscussion(all, "VISA"))
        assertFalse(DiscussionRules.hasDiscussion(all, "carnet"))
        assertEquals(listOf("v"), DiscussionRules.forField(all, "visa").map { it.documentId })
    }

    @Test
    fun crossingReportsAreOrderedByCrossingDateThenCreation() {
        val old = discussion("old", reason = "testimonial", topic = "borderCrossing", at = t0 + 9000,
            extra = mapOf("border_direction" to "A->B", "border_crossing_date" to t0))
        val recent = discussion("new", reason = "testimonial", topic = "borderCrossing", at = t0 + 1000,
            extra = mapOf("border_direction" to "A->B"))
        val other = discussion("x", reason = "general", topic = "general")
        assertEquals(listOf("new", "old"), DiscussionRules.crossingReports(listOf(old, recent, other)).map { it.documentId })
    }

    @Test
    fun suggestionsExcludeRepliesToThem() {
        val s = discussion("s", reason = "suggestion", topic = "borderCrossing")
        val reply = discussion("r", reason = "followup", thread = "s")
        assertEquals(listOf("s"), DiscussionRules.suggestions(listOf(s, reply)).map { it.documentId })
    }

    @Test
    fun ownershipNeedsAMatchingNonEmptyUid() {
        val d = discussion("a", user = "u1")
        assertTrue(DiscussionRules.isOwner(d, "u1"))
        assertFalse(DiscussionRules.isOwner(d, "u2"))
        assertFalse(DiscussionRules.isOwner(d, null))
        assertFalse(DiscussionRules.isOwner(d, ""))
    }

    private fun newDiscussion(location: Coordinate? = null, isOpen: Int? = null, thread: String? = null) =
        DiscussionRules.NewDiscussion(
            id = "id1", objectId = "FRA", collection = "country", userId = "u1", userName = "Ann",
            topic = "visa", reason = "correction", content = "Text", lang = "fr",
            threadId = thread, location = location, isOpen = isOpen, nowMs = t0,
        )

    @Test
    fun correctionPayloadCarriesTheIosFields() {
        val p = DiscussionRules.firestorePayload(newDiscussion())
        assertEquals("FRA", p["objectId"]); assertEquals("country", p["collection"])
        assertEquals("u1", p["userId"]); assertEquals("Ann", p["userName"])
        assertEquals("correction", p["reason"]); assertEquals("visa", p["topic"])
        assertEquals("Text", p["content"]); assertEquals("fr", p["lang"])
        assertEquals(Timestamp(java.util.Date(t0)), p["createdAt"])
        assertEquals(Timestamp(java.util.Date(t0)), p["updatedAt"])
        assertFalse(p.containsKey("threadId")); assertFalse(p.containsKey("location"))
        assertFalse(p.containsKey("geohash")); assertFalse(p.containsKey("is_open"))
    }

    @Test
    fun suggestionPayloadAddsLocationGeohashAndState() {
        val p = DiscussionRules.firestorePayload(newDiscussion(Coordinate(43.35, -1.77), isOpen = 2, thread = "t"))
        assertEquals(GeoPoint(43.35, -1.77), p["location"])
        assertNotNull(p["geohash"]); assertEquals(2, p["is_open"]); assertEquals("t", p["threadId"])
    }

    @Test
    fun localCopyReadsBackLikeTheRemoteDocument() {
        val d = DiscussionRules.localCopy(newDiscussion(Coordinate(43.35, -1.77), isOpen = 3))
        assertEquals("id1", d.documentId); assertEquals("FRA", d.objectId); assertEquals("u1", d.userId)
        assertEquals(t0, d.createdAt); assertEquals("Text", d.content); assertEquals("Ann", d.userName)
        assertEquals(43.35, d.lat!!, 1e-9); assertEquals(-1.77, d.lon!!, 1e-9)
        assertTrue(d.matches("visa"))
    }

    @Test
    fun crossingReportPayloadUsesTheTestimonialShape() {
        val r = DiscussionRules.NewCrossingReport(
            "r1", "post1", "u1", "Ann", "FRA->ESP", "CHE", CrossingVehicle.MOTO,
            timeSpent = "30 min", amountPaid = "", crossingDateMs = t0, content = "Fast", lang = "en", nowMs = t0 + 5,
        )
        val p = DiscussionRules.firestorePayload(r)
        assertEquals("post1", p["objectId"]); assertEquals("border_post", p["collection"])
        assertEquals("testimonial", p["reason"]); assertEquals("borderCrossing", p["topic"])
        assertEquals("FRA->ESP", p["border_direction"]); assertEquals("CHE", p["border_passport"])
        assertEquals("moto", p["border_vehicle"]); assertEquals("30 min", p["border_time_spent"])
        assertFalse(p.containsKey("border_amount_paid"))
        assertEquals(Timestamp(java.util.Date(t0)), p["border_crossing_date"])

        val local = DiscussionRules.localCopy(r)
        assertTrue(local.isCrossingReport); assertTrue(local.isTestimonial)
        assertEquals(CrossingVehicle.MOTO, local.crossingVehicle)
        assertEquals("30 min", local.crossingTimeString)
        assertEquals(t0, local.crossingDate)
    }

    @Test
    fun directionsComeFromThePostThenFromTheBorder() {
        val names = mapOf("FRA" to "France", "ESP" to "Spain")
        val fromPost = DiscussionRules.directionOptions("FRA-ESP", "AAA", "BBB") { names[it] }
        assertEquals(listOf("France -> Spain", "Spain -> France"), fromPost.map { it.label })
        assertEquals(listOf("FRA->ESP", "ESP->FRA"), fromPost.map { it.code })

        val spaced = DiscussionRules.directionOptions("GE - AM", null, null) { null }
        assertEquals(listOf("GE->AM", "AM->GE"), spaced.map { it.code })
        assertEquals("GE -> AM", spaced[0].label)

        val fromBorder = DiscussionRules.directionOptions(null, "FRA", "ESP") { names[it] }
        assertEquals(listOf("FRA->ESP", "ESP->FRA"), fromBorder.map { it.code })

        assertTrue(DiscussionRules.directionOptions("FRA", null, "ESP") { null }.isEmpty())
    }

    @Test
    fun crossingDatesAreStrictDayMonthYear() {
        val ms = DiscussionRules.parseCrossingDate("05/03/2024")
        assertNotNull(ms)
        assertEquals("05/03/2024", DiscussionRules.formatCrossingDate(ms!!))
        assertNull(DiscussionRules.parseCrossingDate("31/02/2024"))
        assertNull(DiscussionRules.parseCrossingDate("2024-03-05"))
        assertNull(DiscussionRules.parseCrossingDate("  "))
    }

    @Test
    fun aSuggestionTooCloseToAnExistingPostIsRefused() {
        val posts = listOf(
            BorderPost(documentId = "p1", name = "Near", lat = 43.3500, lon = -1.7700),
            BorderPost(documentId = "p2", name = "No location"),
        )
        assertEquals("p1", DiscussionRules.tooClosePost(Coordinate(43.3540, -1.7700), posts)?.documentId) // ~0.44 km
        assertNull(DiscussionRules.tooClosePost(Coordinate(43.3700, -1.7700), posts)) // ~2.2 km
    }

    @Test
    fun boundsCheckAcceptsAnythingWithoutKnownBounds() {
        val box = GeoBounds(latMin = 40.0, latMax = 50.0, lonMin = 0.0, lonMax = 10.0)
        assertTrue(DiscussionRules.isWithinBounds(Coordinate(1.0, 1.0), emptyList()))
        assertTrue(DiscussionRules.isWithinBounds(Coordinate(45.0, 5.0), listOf(box)))
        assertFalse(DiscussionRules.isWithinBounds(Coordinate(60.0, 5.0), listOf(box)))
    }
}
