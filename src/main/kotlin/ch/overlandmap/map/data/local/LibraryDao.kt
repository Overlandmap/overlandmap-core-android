package ch.overlandmap.map.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.PackAsset
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.Waypoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Access to the downloaded track packs (the local library). Room implements the
 * `_`-prefixed methods against the *Row entities; the public methods map those
 * rows to and from the domain models (see LibraryRows). Callers only ever see
 * the models.
 */
@Dao
interface LibraryDao {

    @Query("SELECT * FROM track_pack ORDER BY name")
    fun _observeTrackPacks(): Flow<List<TrackPackRow>>
    fun observeTrackPacks(): Flow<List<TrackPack>> =
        _observeTrackPacks().map { rows -> rows.map { it.toModel() } }

    @Query("SELECT * FROM itinerary WHERE trackPackId = :trackPackId ORDER BY name")
    fun _observeItineraries(trackPackId: String): Flow<List<ItineraryRow>>
    fun observeItineraries(trackPackId: String): Flow<List<Itinerary>> =
        _observeItineraries(trackPackId).map { rows -> rows.map { it.toModel() } }

    @Query("SELECT * FROM itinerary WHERE trackPackId = :trackPackId ORDER BY name")
    suspend fun _itinerariesOf(trackPackId: String): List<ItineraryRow>
    suspend fun itinerariesOf(trackPackId: String): List<Itinerary> =
        _itinerariesOf(trackPackId).map { it.toModel() }

    @Query(
        "SELECT * FROM itinerary WHERE lastOpenedAt IS NOT NULL " +
            "ORDER BY lastOpenedAt DESC LIMIT :limit"
    )
    fun _observeLastOpened(limit: Int): Flow<List<ItineraryRow>>
    fun observeLastOpened(limit: Int): Flow<List<Itinerary>> =
        _observeLastOpened(limit).map { rows -> rows.map { it.toModel() } }

    @Query("UPDATE itinerary SET lastOpenedAt = :time WHERE documentId = :id")
    suspend fun touchItinerary(id: String, time: Long)

    @Query("SELECT * FROM track_pack WHERE documentId = :id")
    suspend fun _trackPack(id: String): TrackPackRow?
    suspend fun trackPack(id: String): TrackPack? = _trackPack(id)?.toModel()

    /** IDs of the purchased (non-sample) packs; free samples are excluded. */
    @Query("SELECT documentId FROM track_pack WHERE isFreeSample = 0")
    suspend fun purchasedPackIds(): List<String>

    @Query("SELECT * FROM itinerary WHERE documentId = :id")
    suspend fun _itinerary(id: String): ItineraryRow?
    suspend fun itinerary(id: String): Itinerary? = _itinerary(id)?.toModel()

    @Query("SELECT * FROM itinerary_step WHERE itineraryId = :itineraryId ORDER BY stepId")
    suspend fun _steps(itineraryId: String): List<ItineraryStepRow>
    suspend fun steps(itineraryId: String): List<ItineraryStep> =
        _steps(itineraryId).map { it.toModel() }

    @Query("SELECT * FROM itinerary_step WHERE trackPackId = :trackPackId")
    suspend fun _stepsOfPack(trackPackId: String): List<ItineraryStepRow>
    suspend fun stepsOfPack(trackPackId: String): List<ItineraryStep> =
        _stepsOfPack(trackPackId).map { it.toModel() }

    // Lookups by document ID, for opening a full-text search hit.

    @Query("SELECT * FROM itinerary_step WHERE documentId = :documentId LIMIT 1")
    suspend fun _stepByDocumentId(documentId: String): ItineraryStepRow?
    suspend fun stepByDocumentId(documentId: String): ItineraryStep? =
        _stepByDocumentId(documentId)?.toModel()

    @Query("SELECT * FROM waypoint WHERE documentId = :documentId LIMIT 1")
    suspend fun _waypointByDocumentId(documentId: String): WaypointRow?
    suspend fun waypointByDocumentId(documentId: String): Waypoint? =
        _waypointByDocumentId(documentId)?.toModel()

    @Query("SELECT documentId FROM waypoint WHERE trackPackId = :trackPackId")
    suspend fun waypointIdsOfPack(trackPackId: String): List<String>

    @Query("UPDATE track_pack SET needsUpdate = :value WHERE documentId = :trackPackId")
    suspend fun setNeedsUpdate(trackPackId: String, value: Boolean)

    @Query("SELECT * FROM track WHERE itineraryId = :itineraryId")
    suspend fun _tracks(itineraryId: String): List<TrackRow>
    suspend fun tracks(itineraryId: String): List<Track> = _tracks(itineraryId).map { it.toModel() }

    @Query("SELECT * FROM waypoint WHERE itineraryId = :itineraryId")
    suspend fun _waypoints(itineraryId: String): List<WaypointRow>
    suspend fun waypoints(itineraryId: String): List<Waypoint> =
        _waypoints(itineraryId).map { it.toModel() }

    @Query("SELECT * FROM sidebar WHERE trackPackId = :trackPackId ORDER BY name")
    suspend fun _sidebars(trackPackId: String): List<SidebarRow>
    suspend fun sidebars(trackPackId: String): List<Sidebar> =
        _sidebars(trackPackId).map { it.toModel() }

    // Lookups for markup links, which address objects by name, slug (the
    // human-readable itineraryId like "K4") or geohash.

    @Query("SELECT * FROM track_pack WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun _trackPackByName(name: String): TrackPackRow?
    suspend fun trackPackByName(name: String): TrackPack? = _trackPackByName(name)?.toModel()

    @Query(
        "SELECT * FROM itinerary WHERE trackPackId = :trackPackId " +
            "AND itineraryId = :slug COLLATE NOCASE LIMIT 1"
    )
    suspend fun _itineraryBySlug(trackPackId: String, slug: String): ItineraryRow?
    suspend fun itineraryBySlug(trackPackId: String, slug: String): Itinerary? =
        _itineraryBySlug(trackPackId, slug)?.toModel()

    @Query(
        "SELECT * FROM waypoint WHERE trackPackId = :trackPackId " +
            "AND name = :name COLLATE NOCASE LIMIT 1"
    )
    suspend fun _waypointByName(trackPackId: String, name: String): WaypointRow?
    suspend fun waypointByName(trackPackId: String, name: String): Waypoint? =
        _waypointByName(trackPackId, name)?.toModel()

    @Query("SELECT * FROM waypoint WHERE geohash = :geohash LIMIT 1")
    suspend fun _waypointByGeohash(geohash: String): WaypointRow?
    suspend fun waypointByGeohash(geohash: String): Waypoint? =
        _waypointByGeohash(geohash)?.toModel()

    @Query(
        "SELECT * FROM sidebar WHERE trackPackId = :trackPackId " +
            "AND name = :name COLLATE NOCASE LIMIT 1"
    )
    suspend fun _sidebarByName(trackPackId: String, name: String): SidebarRow?
    suspend fun sidebarByName(trackPackId: String, name: String): Sidebar? =
        _sidebarByName(trackPackId, name)?.toModel()

    @Query("SELECT * FROM sidebar WHERE documentId = :documentId LIMIT 1")
    suspend fun _sidebarById(documentId: String): SidebarRow?
    suspend fun sidebarById(documentId: String): Sidebar? = _sidebarById(documentId)?.toModel()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertTrackPack(pack: TrackPackRow)
    suspend fun insertTrackPack(pack: TrackPack) = _insertTrackPack(pack.toRow())

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertItineraries(itineraries: List<ItineraryRow>)
    suspend fun insertItineraries(itineraries: List<Itinerary>) =
        _insertItineraries(itineraries.map { it.toRow() })

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertSteps(steps: List<ItineraryStepRow>)
    suspend fun insertSteps(steps: List<ItineraryStep>) = _insertSteps(steps.map { it.toRow() })

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertTracks(tracks: List<TrackRow>)
    suspend fun insertTracks(tracks: List<Track>) = _insertTracks(tracks.map { it.toRow() })

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertWaypoints(waypoints: List<WaypointRow>)
    suspend fun insertWaypoints(waypoints: List<Waypoint>) =
        _insertWaypoints(waypoints.map { it.toRow() })

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertSidebars(sidebars: List<SidebarRow>)
    suspend fun insertSidebars(sidebars: List<Sidebar>) = _insertSidebars(sidebars.map { it.toRow() })

    @Query("SELECT * FROM comment WHERE objectId = :objectId ORDER BY createdAt DESC")
    fun _observeComments(objectId: String): Flow<List<CommentRow>>
    fun observeComments(objectId: String): Flow<List<Comment>> =
        _observeComments(objectId).map { rows -> rows.map { it.toModel() } }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertComments(comments: List<CommentRow>)
    suspend fun insertComments(comments: List<Comment>) = _insertComments(comments.map { it.toRow() })

    @Query("DELETE FROM comment WHERE objectId = :objectId")
    suspend fun deleteComments(objectId: String)

    @Transaction
    suspend fun replaceComments(objectId: String, comments: List<Comment>) {
        deleteComments(objectId)
        insertComments(comments)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertPackAssets(assets: List<PackAssetRow>)
    suspend fun insertPackAssets(assets: List<PackAsset>) =
        _insertPackAssets(assets.map { it.toRow() })

    @Query("SELECT * FROM pack_asset WHERE trackPackId = :trackPackId")
    suspend fun _packAssets(trackPackId: String): List<PackAssetRow>
    suspend fun packAssets(trackPackId: String): List<PackAsset> =
        _packAssets(trackPackId).map { it.toModel() }

    @Query("DELETE FROM pack_asset WHERE trackPackId = :trackPackId")
    suspend fun deletePackAssets(trackPackId: String)

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
        deletePackAssets(trackPackId)
        deleteTrackPack(trackPackId)
    }
}
