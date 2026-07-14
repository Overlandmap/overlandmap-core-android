package ch.overlandmap.map.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.Waypoint
import kotlinx.coroutines.flow.Flow

/** Access to the downloaded track packs (the local library). */
@Dao
interface LibraryDao {

    @Query("SELECT * FROM track_pack ORDER BY name")
    fun observeTrackPacks(): Flow<List<TrackPack>>

    @Query("SELECT * FROM itinerary WHERE trackPackId = :trackPackId ORDER BY name")
    fun observeItineraries(trackPackId: String): Flow<List<Itinerary>>

    @Query("SELECT * FROM itinerary WHERE trackPackId = :trackPackId ORDER BY name")
    suspend fun itinerariesOf(trackPackId: String): List<Itinerary>

    @Query(
        "SELECT * FROM itinerary WHERE lastOpenedAt IS NOT NULL " +
            "ORDER BY lastOpenedAt DESC LIMIT :limit"
    )
    fun observeLastOpened(limit: Int): Flow<List<Itinerary>>

    @Query("UPDATE itinerary SET lastOpenedAt = :time WHERE documentId = :id")
    suspend fun touchItinerary(id: String, time: Long)

    @Query("SELECT * FROM track_pack WHERE documentId = :id")
    suspend fun trackPack(id: String): TrackPack?

    /** IDs of the purchased (non-sample) packs; free samples are excluded. */
    @Query("SELECT documentId FROM track_pack WHERE isFreeSample = 0")
    suspend fun purchasedPackIds(): List<String>

    @Query("SELECT * FROM itinerary WHERE documentId = :id")
    suspend fun itinerary(id: String): Itinerary?

    @Query("SELECT * FROM itinerary_step WHERE itineraryId = :itineraryId ORDER BY stepId")
    suspend fun steps(itineraryId: String): List<ItineraryStep>

    @Query("SELECT * FROM itinerary_step WHERE trackPackId = :trackPackId")
    suspend fun stepsOfPack(trackPackId: String): List<ItineraryStep>

    @Query("UPDATE track_pack SET needsUpdate = :value WHERE documentId = :trackPackId")
    suspend fun setNeedsUpdate(trackPackId: String, value: Boolean)

    @Query("SELECT * FROM track WHERE itineraryId = :itineraryId")
    suspend fun tracks(itineraryId: String): List<Track>

    @Query("SELECT * FROM waypoint WHERE itineraryId = :itineraryId")
    suspend fun waypoints(itineraryId: String): List<Waypoint>

    @Query("SELECT * FROM sidebar WHERE trackPackId = :trackPackId ORDER BY name")
    suspend fun sidebars(trackPackId: String): List<Sidebar>

    // Lookups for markup links, which address objects by name, slug (the
    // human-readable itineraryId like "K4") or geohash.

    @Query("SELECT * FROM track_pack WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun trackPackByName(name: String): TrackPack?

    @Query(
        "SELECT * FROM itinerary WHERE trackPackId = :trackPackId " +
            "AND itineraryId = :slug COLLATE NOCASE LIMIT 1"
    )
    suspend fun itineraryBySlug(trackPackId: String, slug: String): Itinerary?

    @Query(
        "SELECT * FROM waypoint WHERE trackPackId = :trackPackId " +
            "AND name = :name COLLATE NOCASE LIMIT 1"
    )
    suspend fun waypointByName(trackPackId: String, name: String): Waypoint?

    @Query("SELECT * FROM waypoint WHERE geohash = :geohash LIMIT 1")
    suspend fun waypointByGeohash(geohash: String): Waypoint?

    @Query(
        "SELECT * FROM sidebar WHERE trackPackId = :trackPackId " +
            "AND name = :name COLLATE NOCASE LIMIT 1"
    )
    suspend fun sidebarByName(trackPackId: String, name: String): Sidebar?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackPack(pack: TrackPack)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItineraries(itineraries: List<Itinerary>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<ItineraryStep>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(tracks: List<Track>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWaypoints(waypoints: List<Waypoint>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSidebars(sidebars: List<Sidebar>)

    @Query("SELECT * FROM comment WHERE objectId = :objectId ORDER BY createdAt DESC")
    fun observeComments(objectId: String): Flow<List<Comment>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertComments(comments: List<Comment>)

    @Query("DELETE FROM comment WHERE objectId = :objectId")
    suspend fun deleteComments(objectId: String)

    @Transaction
    suspend fun replaceComments(objectId: String, comments: List<Comment>) {
        deleteComments(objectId)
        insertComments(comments)
    }

    @Query("DELETE FROM track_pack WHERE documentId = :trackPackId")
    suspend fun deleteTrackPack(trackPackId: String)

    @Query("DELETE FROM itinerary WHERE trackPackId = :trackPackId")
    suspend fun deleteItineraries(trackPackId: String)

    @Query("DELETE FROM itinerary_step WHERE trackPackId = :trackPackId")
    suspend fun deleteSteps(trackPackId: String)

    @Query("DELETE FROM track WHERE trackPackId = :trackPackId")
    suspend fun deleteTracks(trackPackId: String)

    @Query("DELETE FROM waypoint WHERE trackPackId = :trackPackId")
    suspend fun deleteWaypoints(trackPackId: String)

    @Query("DELETE FROM sidebar WHERE trackPackId = :trackPackId")
    suspend fun deleteSidebars(trackPackId: String)

    @Transaction
    suspend fun deletePackContent(trackPackId: String) {
        deleteItineraries(trackPackId)
        deleteSteps(trackPackId)
        deleteTracks(trackPackId)
        deleteWaypoints(trackPackId)
        deleteSidebars(trackPackId)
        deleteTrackPack(trackPackId)
    }
}
