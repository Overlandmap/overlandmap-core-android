package ch.overlandmap.map.data

import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.UniversalLink
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.model.Zone
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UniversalLinkResolverTest {

    private val pack = TrackPack(documentId = "p1", name = "Pamir", editor = "ed")
    private val itinerary = Itinerary(documentId = "i1", trackPackId = "p1", itineraryId = "P1", name = "Pamir 1")
    private val teaser = Itinerary(documentId = "i2", trackPackId = "p1", itineraryId = "P2", name = "Teaser", isBuyable = true)

    private val store = object : LinkStore {
        override suspend fun trackPacks() = listOf(pack, TrackPack(documentId = "p2", name = "Pamir", editor = "other"))
        override suspend fun itineraryBySlug(trackPackId: String, slug: String) =
            listOf(itinerary, teaser).firstOrNull { it.trackPackId == trackPackId && it.itineraryId == slug }
        override suspend fun steps(itineraryId: String) =
            if (itineraryId == "i1") listOf(ItineraryStep("s1", "i1", "p1", stepId = 1, name = "A"), ItineraryStep("s3", "i1", "p1", stepId = 3, name = "C"))
            else emptyList()
        override suspend fun waypointByGeohash(geohash: String) =
            if (geohash == "u0qj2") Waypoint(documentId = "w1", trackPackId = "p1", name = "Pass") else null
        override suspend fun country(alpha3: String) = if (alpha3 == "FRA") Country(documentId = "FRA", name = "France") else null
        override suspend fun border(id: String) = if (id == "FRA-ESP") CountryBorder(documentId = id, name = "x") else null
        override suspend fun borderPost(id: String) = if (id == "bp") BorderPost(documentId = id, name = "Post") else null
        override suspend fun zone(id: String) = if (id == "z") Zone(documentId = id, name = "Zone") else null
    }
    private val resolver = UniversalLinkResolver(store)

    private fun resolve(link: UniversalLink) = runBlocking { resolver.resolve(link) }

    private fun failure(link: UniversalLink): LinkResolutionException.Reason = try {
        resolve(link); fail("expected a failure"); error("unreachable")
    } catch (e: LinkResolutionException) {
        e.reason
    }

    @Test
    fun packsAreMatchedByNameAndEditor() {
        assertEquals(LinkDestination.OfTrackPack(pack), resolve(UniversalLink.TrackPack("ed", "Pamir")))
        assertEquals(LinkResolutionException.Reason.UNAVAILABLE, failure(UniversalLink.TrackPack("nobody", "Pamir")))
    }

    @Test
    fun itinerariesNeedTheRightEditorAndMustNotBeTeasers() {
        assertEquals(LinkDestination.OfItinerary(itinerary, null), resolve(UniversalLink.Itinerary("ed", "Pamir", "P1")))
        assertEquals(LinkResolutionException.Reason.UNAVAILABLE, failure(UniversalLink.Itinerary("other", "Pamir", "P1")))
        assertEquals(LinkResolutionException.Reason.UNAVAILABLE, failure(UniversalLink.Itinerary("ed", "Pamir", "P2")))
    }

    @Test
    fun anItinerarysOwnEditorIdWinsOverItsPacks() {
        val own = itinerary.copy(extra = mapOf("editorId" to "guest"))
        val ownStore = object : LinkStore by store {
            override suspend fun itineraryBySlug(trackPackId: String, slug: String) = if (slug == "P1" && trackPackId == "p1") own else null
        }
        val r = UniversalLinkResolver(ownStore)
        assertEquals("guest", linkEditorId(own, pack))
        assertEquals("ed", linkEditorId(itinerary, pack))
        assertEquals(LinkDestination.OfItinerary(own, null), runBlocking { r.resolve(UniversalLink.Itinerary("guest", "Pamir", "P1")) })
        assertEquals(LinkResolutionException.Reason.UNAVAILABLE, try { runBlocking { r.resolve(UniversalLink.Itinerary("ed", "Pamir", "P1")) }; null } catch (e: LinkResolutionException) { e.reason })
    }

    @Test
    fun aStepLinkMustNameAnExistingStep() {
        assertEquals(LinkDestination.OfItinerary(itinerary, 3), resolve(UniversalLink.Itinerary("ed", "Pamir", "P1", 3)))
        assertEquals(LinkResolutionException.Reason.UNAVAILABLE, failure(UniversalLink.Itinerary("ed", "Pamir", "P1", 2)))
    }

    @Test
    fun waypointsAreFoundByGeohash() {
        assertEquals("w1", (resolve(UniversalLink.Waypoint("u0qj2")) as LinkDestination.OfWaypoint).waypoint.documentId)
        assertEquals(LinkResolutionException.Reason.UNAVAILABLE, failure(UniversalLink.Waypoint("zzzz")))
    }

    @Test
    fun overlandObjectsResolveOrReportTheDatasetNotDownloaded() {
        assertTrue(resolve(UniversalLink.Country("fra")) is LinkDestination.OfCountry) // case-insensitive
        assertTrue(resolve(UniversalLink.Border("FRA-ESP")) is LinkDestination.OfBorder)
        assertTrue(resolve(UniversalLink.BorderPost("bp")) is LinkDestination.OfBorderPost)
        assertTrue(resolve(UniversalLink.Zone("z")) is LinkDestination.OfZone)
        listOf(
            UniversalLink.Country("XXX"), UniversalLink.Border("nope"),
            UniversalLink.BorderPost("nope"), UniversalLink.Zone("nope"),
        ).forEach { assertEquals(LinkResolutionException.Reason.NOT_DOWNLOADED, failure(it)) }
    }
}
