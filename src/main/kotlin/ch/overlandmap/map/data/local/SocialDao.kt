package ch.overlandmap.map.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Access to the social tables: check-ins, votes, contributed waypoints, and
 * the per-pack sync timestamps that drive the 24-hour refresh policy.
 */
@Dao
interface SocialDao {

    // ── Sync timestamps ─────────────────────────────────────────────────────

    @Query("SELECT lastSyncedAt FROM social_sync WHERE trackPackId = :trackPackId")
    suspend fun lastSyncedAt(trackPackId: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSync(row: SocialSyncRow)

    // ── Check-ins ───────────────────────────────────────────────────────────

    @Query("SELECT * FROM check_in WHERE trackPackId = :trackPackId ORDER BY createdAt DESC")
    suspend fun checkInsForPack(trackPackId: String): List<CheckInRow>

    @Query("SELECT * FROM check_in WHERE objectId = :objectId ORDER BY createdAt DESC")
    fun observeCheckInsForObject(objectId: String): Flow<List<CheckInRow>>

    @Query("SELECT * FROM check_in WHERE objectId = :objectId ORDER BY createdAt DESC")
    suspend fun checkInsForObject(objectId: String): List<CheckInRow>

    @Query("SELECT * FROM check_in ORDER BY createdAt DESC LIMIT 500")
    suspend fun latestCheckIns(): List<CheckInRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckIns(rows: List<CheckInRow>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCheckIn(row: CheckInRow)

    @Query("DELETE FROM check_in WHERE trackPackId = :trackPackId")
    suspend fun deleteCheckInsForPack(trackPackId: String)

    @Query("DELETE FROM check_in WHERE documentId = :documentId")
    suspend fun deleteCheckIn(documentId: String)

    @Query("DELETE FROM check_in")
    suspend fun deleteAllCheckIns()

    @Transaction
    suspend fun replaceCheckInsForPack(trackPackId: String, rows: List<CheckInRow>) {
        deleteCheckInsForPack(trackPackId)
        insertCheckIns(rows)
    }

    // ── Votes ───────────────────────────────────────────────────────────────

    @Query("SELECT * FROM vote WHERE objectId = :objectId ORDER BY createdAt DESC")
    fun observeVotesForObject(objectId: String): Flow<List<VoteRow>>

    @Query("SELECT * FROM vote WHERE objectId = :objectId ORDER BY createdAt DESC")
    suspend fun votesForObject(objectId: String): List<VoteRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVotes(rows: List<VoteRow>)

    @Query("DELETE FROM vote WHERE trackPackId = :trackPackId")
    suspend fun deleteVotesForPack(trackPackId: String)

    @Transaction
    suspend fun replaceVotesForPack(trackPackId: String, rows: List<VoteRow>) {
        deleteVotesForPack(trackPackId)
        insertVotes(rows)
    }

    // ── Contributed waypoints ───────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContributedWaypoints(rows: List<ContributedWaypointRow>)

    @Query("DELETE FROM contributed_waypoint WHERE trackPackId = :trackPackId")
    suspend fun deleteContributedWaypointsForPack(trackPackId: String)

    @Transaction
    suspend fun replaceContributedWaypointsForPack(
        trackPackId: String,
        rows: List<ContributedWaypointRow>,
    ) {
        deleteContributedWaypointsForPack(trackPackId)
        insertContributedWaypoints(rows)
    }
}
