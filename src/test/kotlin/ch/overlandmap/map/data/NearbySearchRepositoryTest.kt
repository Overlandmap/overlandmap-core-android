package ch.overlandmap.map.data

import ch.overlandmap.map.data.local.*
import ch.overlandmap.map.geo.*
import ch.overlandmap.map.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NearbySearchRepositoryTest {
    private val origin = Coordinate(0.0, 0.0)
    private fun waypoint(id: String, lon: Double, hash: Boolean = true) = Waypoint(id, "pack", name = id,
        lat = 0.0, lon = lon, geohash = if (hash) Geohash.encode(0.0, lon) else null).toRow()

    @Test fun exactCircleDistanceOrderingAndLegacyRows() = runBlocking {
        val dao = FakeDao().apply {
            points = listOf(waypoint("further", 0.2), waypoint("nearest", 0.01), waypoint("legacy", 0.1, false), waypoint("outside", 0.8))
        }
        val results = NearbySearchRepository(dao) { null }.search(origin, 50.0, NearbySearchRepository.Type.WAYPOINT, "en")
        assertEquals(listOf("nearest", "legacy", "further"), results.map { it.documentId })
        assertEquals(origin.distanceKm(Coordinate(0.0, 0.01)), results.first().distanceKm!!, 1e-9)
        assertTrue(dao.rangeQueries > 0)
        assertTrue(results.zipWithNext().all { (a, b) -> a.distanceKm!! <= b.distanceKm!! })
    }
    @Test fun typeFiltersAndActiveCommunityWaypoints() = runBlocking {
        val dao = FakeDao().apply {
            points = listOf(waypoint("local", 0.1))
            community = listOf(
                ContributedWaypoint("active", "pack", name = "Shared", lat = 0.0, lon = 0.2, geohash = Geohash.encode(0.0, 0.2)).toRow(),
                ContributedWaypoint("inactive", "pack", name = "Hidden", lat = 0.0, lon = 0.1, isActive = false).toRow())
            borderPosts = listOf(BorderPost("crossing", "Crossing", lat = 0.0, lon = 0.05, geohash = Geohash.encode(0.0, 0.05)).toRow())
        }
        val repo = NearbySearchRepository(dao) { null }
        val all = repo.search(origin, 50.0, NearbySearchRepository.Type.ALL, "en")
        assertEquals(listOf("crossing", "local", "active"), all.map { it.documentId })
        assertNotNull(all.last().contributedWaypoint)
        val waypoints = repo.search(origin, 50.0, NearbySearchRepository.Type.WAYPOINT, "en")
        assertEquals(listOf("local", "active"), waypoints.map { it.documentId })
        assertEquals(listOf("crossing"), repo.search(origin, 50.0, NearbySearchRepository.Type.BORDER_POST, "en").map { it.documentId })
    }
    @Test fun nearbyStepNominatesLongItineraryAndKeepsStepLink() = runBlocking {
        val itinerary = Itinerary("itin", "pack", "L9", "Long drive", centerLat = 0.0, centerLon = 10.0)
        val dao = FakeDao().apply {
            itinerarySteps = listOf(ItineraryStep("step", "itin", "pack", 2, "Stop", lat = 0.0, lon = 0.01, geohash = Geohash.encode(0.0, 0.01)).toRow())
        }
        val results = NearbySearchRepository(dao) { itinerary }.search(origin, 50.0, NearbySearchRepository.Type.ITINERARY, "en")
        assertEquals(setOf("itin", "step"), results.map { it.documentId }.toSet())
        val step = results.first { it.documentId == "step" }
        assertEquals("itin", step.itineraryDocumentId)
        assertEquals(2, step.stepId)
        assertEquals("L9", step.itinerarySlug)
        assertEquals(results[0].distanceKm, results[1].distanceKm)
    }
    @Test fun includesItineraryWithCentreAndNoSteps() = runBlocking {
        val itinerary = Itinerary("centre", "pack", "L1", "Centre", centerLat = 0.0, centerLon = 0.1)
        val dao = FakeDao().apply { centres = listOf(itinerary.toRow()) }
        assertEquals(listOf("centre"), NearbySearchRepository(dao) { null }.search(origin, 50.0, NearbySearchRepository.Type.ITINERARY, "en").map { it.documentId })
    }

    private class FakeDao : NearbyDao {
        var rangeQueries = 0
        var points = emptyList<WaypointRow>()
        var itinerarySteps = emptyList<ItineraryStepRow>()
        var borderPosts = emptyList<BorderPostRow>()
        var community = emptyList<ContributedWaypointRow>()
        var centres = emptyList<ItineraryRow>()
        private fun matches(hash: String?, start: String, end: String): Boolean { rangeQueries++; return hash != null && hash >= start && hash < end }
        override suspend fun steps(start: String, end: String) = itinerarySteps.filter { matches(it.geohash, start, end) }
        override suspend fun waypoints(start: String, end: String) = points.filter { matches(it.geohash, start, end) }
        override suspend fun posts(start: String, end: String) = borderPosts.filter { matches(it.geohash, start, end) }
        override suspend fun contributed(start: String, end: String) = community.filter { it.active == 1 && matches(it.geohash, start, end) }
        override suspend fun unindexedSteps(precision: Int) = itinerarySteps.filter { (it.geohash?.length ?: 0) < precision }
        override suspend fun unindexedWaypoints(precision: Int) = points.filter { (it.geohash?.length ?: 0) < precision }
        override suspend fun unindexedPosts(precision: Int) = borderPosts.filter { (it.geohash?.length ?: 0) < precision }
        override suspend fun unindexedContributed(precision: Int) = community.filter { it.active == 1 && (it.geohash?.length ?: 0) < precision }
        override suspend fun itineraryCenters(south: Double, north: Double, west: Double, east: Double) = centres.filter {
            (it.centerLat ?: Double.NaN) in south..north && (it.centerLon ?: Double.NaN) in west..east
        }
    }
}
