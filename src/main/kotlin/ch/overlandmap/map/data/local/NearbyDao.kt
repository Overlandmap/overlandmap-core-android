package ch.overlandmap.map.data.local

import androidx.room.Dao
import androidx.room.Query

/** Range comparisons use the existing binary geohash indexes, unlike a substring scan. */
@Dao
interface NearbyDao {
    @Query("SELECT * FROM itinerary_step WHERE geohash >= :start AND geohash < :end")
    suspend fun steps(start: String, end: String): List<ItineraryStepRow>
    @Query("SELECT * FROM waypoint WHERE geohash >= :start AND geohash < :end")
    suspend fun waypoints(start: String, end: String): List<WaypointRow>
    @Query("SELECT * FROM border_post WHERE geohash >= :start AND geohash < :end")
    suspend fun posts(start: String, end: String): List<BorderPostRow>
    @Query("SELECT * FROM contributed_waypoint WHERE active = 1 AND geohash >= :start AND geohash < :end")
    suspend fun contributed(start: String, end: String): List<ContributedWaypointRow>

    // Legacy exports can lack a geohash or store one shorter than the search prefix.
    @Query("SELECT * FROM itinerary_step WHERE geohash IS NULL OR length(geohash) < :precision")
    suspend fun unindexedSteps(precision: Int): List<ItineraryStepRow>
    @Query("SELECT * FROM waypoint WHERE geohash IS NULL OR length(geohash) < :precision")
    suspend fun unindexedWaypoints(precision: Int): List<WaypointRow>
    @Query("SELECT * FROM border_post WHERE geohash IS NULL OR length(geohash) < :precision")
    suspend fun unindexedPosts(precision: Int): List<BorderPostRow>
    @Query("SELECT * FROM contributed_waypoint WHERE active = 1 AND (geohash IS NULL OR length(geohash) < :precision)")
    suspend fun unindexedContributed(precision: Int): List<ContributedWaypointRow>

    // Itineraries have a centre rather than a geohash column. Nearby indexed steps also
    // nominate their parent itinerary, so long itineraries aren't missed near an endpoint.
    @Query("SELECT * FROM itinerary WHERE COALESCE(centerLat, (latMin + latMax) / 2.0) BETWEEN :south AND :north AND COALESCE(centerLon, (lonMin + lonMax) / 2.0) BETWEEN :west AND :east")
    suspend fun itineraryCenters(south: Double, north: Double, west: Double, east: Double): List<ItineraryRow>
}
