package ch.overlandmap.map.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ch.overlandmap.map.model.CheckIn
import ch.overlandmap.map.model.ContributedWaypoint
import ch.overlandmap.map.model.Discussion
import ch.overlandmap.map.model.Vote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Access to the social tables: check-ins, votes, contributed waypoints, discussions and the
 * per-pack, per-kind sync timestamps that drive the refresh policy. Room implements the
 * `_`-prefixed row methods; the public methods map to and from the domain models.
 *
 * Each `replace…ForPack` runs in one transaction so a reader never sees a half-replaced kind.
 */
@Dao
interface SocialDao {

    // ── Sync timestamps ─────────────────────────────────────────────────────

    @Query("SELECT lastSync FROM sync_state WHERE trackPackId = :trackPackId AND kind = :kind")
    suspend fun lastSync(trackPackId: String, kind: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSyncState(row: SyncStateRow): Unit

    @Query("DELETE FROM sync_state WHERE trackPackId = :trackPackId AND kind = :kind")
    suspend fun deleteSyncState(trackPackId: String, kind: String): Unit

    @Query("DELETE FROM sync_state WHERE trackPackId = :trackPackId")
    suspend fun deleteSyncStates(trackPackId: String): Unit

    // ── Check-ins ───────────────────────────────────────────────────────────

    @Query("SELECT * FROM check_in WHERE trackPackId = :trackPackId ORDER BY createdAt DESC")
    suspend fun _checkInsForPack(trackPackId: String): List<CheckInRow>
    suspend fun checkInsForPack(trackPackId: String): List<CheckIn> =
        _checkInsForPack(trackPackId).mapNotNull { it.toModel() }

    // Only plain confirmations count as check-ins; the other reasons are corrections (iOS `checkIns(forObjectID:)`).
    @Query("SELECT * FROM check_in WHERE objectId = :objectId AND reason = 'check' ORDER BY createdAt DESC")
    fun _observeCheckInsForObject(objectId: String): Flow<List<CheckInRow>>
    fun observeCheckInsForObject(objectId: String): Flow<List<CheckIn>> =
        _observeCheckInsForObject(objectId).map { rows -> rows.mapNotNull { it.toModel() } }

    @Query("SELECT * FROM check_in WHERE objectId = :objectId AND reason = 'check' ORDER BY createdAt DESC")
    suspend fun _checkInsForObject(objectId: String): List<CheckInRow>
    suspend fun checkInsForObject(objectId: String): List<CheckIn> =
        _checkInsForObject(objectId).mapNotNull { it.toModel() }

    /** The newest correction (a check-in with a problem reason) of an object, live. */
    @Query(
        "SELECT * FROM check_in WHERE objectId = :objectId AND reason IN " +
            "('doesNotExist','closed','repaired','wrongDescription','wrongLocation') " +
            "ORDER BY createdAt DESC LIMIT 1"
    )
    fun _observeActiveCorrection(objectId: String): Flow<CheckInRow?>
    fun observeActiveCorrection(objectId: String): Flow<CheckIn?> =
        _observeActiveCorrection(objectId).map { it?.toModel() }

    @Query("SELECT * FROM check_in WHERE documentId = :documentId LIMIT 1")
    suspend fun _checkIn(documentId: String): CheckInRow?
    suspend fun checkIn(documentId: String): CheckIn? = _checkIn(documentId)?.toModel()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertCheckIns(rows: List<CheckInRow>): Unit
    suspend fun insertCheckIns(checkIns: List<CheckIn>): Unit = _insertCheckIns(checkIns.map { it.toRow() })
    suspend fun insertCheckIn(checkIn: CheckIn): Unit = insertCheckIns(listOf(checkIn))

    @Query("DELETE FROM check_in WHERE trackPackId = :trackPackId")
    suspend fun deleteCheckInsForPack(trackPackId: String): Unit

    @Query("DELETE FROM check_in WHERE documentId = :documentId")
    suspend fun deleteCheckIn(documentId: String): Unit

    @Transaction
    suspend fun replaceCheckInsForPack(trackPackId: String, checkIns: List<CheckIn>): Unit {
        deleteCheckInsForPack(trackPackId)
        insertCheckIns(checkIns)
    }

    // ── Votes ───────────────────────────────────────────────────────────────

    @Query("SELECT * FROM vote WHERE objectId = :objectId ORDER BY createdAt DESC")
    fun _observeVotesForObject(objectId: String): Flow<List<VoteRow>>
    fun observeVotesForObject(objectId: String): Flow<List<Vote>> =
        _observeVotesForObject(objectId).map { rows -> rows.map { it.toModel() } }

    @Query("SELECT * FROM vote WHERE objectId = :objectId ORDER BY createdAt DESC")
    suspend fun _votesForObject(objectId: String): List<VoteRow>
    suspend fun votesForObject(objectId: String): List<Vote> =
        _votesForObject(objectId).map { it.toModel() }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertVotes(rows: List<VoteRow>): Unit
    suspend fun insertVotes(votes: List<Vote>): Unit = _insertVotes(votes.map { it.toRow() })

    /** The user's own (newest) vote on an object, live; null when none. */
    @Query("SELECT * FROM vote WHERE objectId = :objectId AND userId = :userId ORDER BY createdAt DESC LIMIT 1")
    fun _observeUserVote(objectId: String, userId: String): Flow<VoteRow?>
    fun observeUserVote(objectId: String, userId: String): Flow<Vote?> =
        _observeUserVote(objectId, userId).map { it?.toModel() }

    @Query("SELECT * FROM vote WHERE objectId = :objectId AND userId = :userId ORDER BY createdAt DESC LIMIT 1")
    suspend fun _userVote(objectId: String, userId: String): VoteRow?
    suspend fun userVote(objectId: String, userId: String): Vote? = _userVote(objectId, userId)?.toModel()

    suspend fun insertVote(vote: Vote): Unit = insertVotes(listOf(vote))

    @Query("UPDATE vote SET upVote = :isUpVote WHERE documentId = :documentId")
    suspend fun updateVoteValue(documentId: String, isUpVote: Int): Unit

    @Query("DELETE FROM vote WHERE documentId = :documentId")
    suspend fun deleteVote(documentId: String): Unit

    @Query("DELETE FROM vote WHERE trackPackId = :trackPackId")
    suspend fun deleteVotesForPack(trackPackId: String): Unit

    @Transaction
    suspend fun replaceVotesForPack(trackPackId: String, votes: List<Vote>): Unit {
        deleteVotesForPack(trackPackId)
        insertVotes(votes)
    }

    // ── Contributed waypoints ───────────────────────────────────────────────

    @Query("SELECT * FROM contributed_waypoint WHERE trackPackId = :trackPackId AND active = 1")
    suspend fun _activeContributedWaypoints(trackPackId: String): List<ContributedWaypointRow>
    suspend fun activeContributedWaypoints(trackPackId: String): List<ContributedWaypoint> =
        _activeContributedWaypoints(trackPackId).map { it.toModel() }

    @Query("SELECT * FROM contributed_waypoint WHERE itinDocumentId = :itineraryId AND active = 1")
    suspend fun _activeContributedWaypointsOfItinerary(itineraryId: String): List<ContributedWaypointRow>
    suspend fun activeContributedWaypointsOfItinerary(itineraryId: String): List<ContributedWaypoint> =
        _activeContributedWaypointsOfItinerary(itineraryId).map { it.toModel() }

    @Query(
        "SELECT * FROM contributed_waypoint WHERE itinDocumentId = :itineraryId AND active = 1 " +
            "ORDER BY lastUpdate DESC"
    )
    fun _observeContributedWaypointsOfItinerary(itineraryId: String): Flow<List<ContributedWaypointRow>>
    fun observeContributedWaypointsOfItinerary(itineraryId: String): Flow<List<ContributedWaypoint>> =
        _observeContributedWaypointsOfItinerary(itineraryId).map { rows -> rows.map { it.toModel() } }

    @Query("DELETE FROM contributed_waypoint WHERE documentId = :documentId")
    suspend fun deleteContributedWaypoint(documentId: String): Unit

    suspend fun insertContributedWaypoint(waypoint: ContributedWaypoint): Unit =
        insertContributedWaypoints(listOf(waypoint))

    @Query("SELECT * FROM contributed_waypoint WHERE documentId = :documentId LIMIT 1")
    suspend fun _contributedWaypoint(documentId: String): ContributedWaypointRow?
    suspend fun contributedWaypoint(documentId: String): ContributedWaypoint? =
        _contributedWaypoint(documentId)?.toModel()

    @Query("SELECT documentId FROM contributed_waypoint WHERE trackPackId = :trackPackId")
    suspend fun contributedWaypointIdsOfPack(trackPackId: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertContributedWaypoints(rows: List<ContributedWaypointRow>): Unit
    suspend fun insertContributedWaypoints(waypoints: List<ContributedWaypoint>): Unit =
        _insertContributedWaypoints(waypoints.map { it.toRow() })

    @Query("DELETE FROM contributed_waypoint WHERE trackPackId = :trackPackId")
    suspend fun deleteContributedWaypointsForPack(trackPackId: String): Unit

    @Transaction
    suspend fun replaceContributedWaypointsForPack(
        trackPackId: String,
        waypoints: List<ContributedWaypoint>,
    ): Unit {
        deleteContributedWaypointsForPack(trackPackId)
        insertContributedWaypoints(waypoints)
    }

    // ── Discussions ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM discussion WHERE objectId = :objectId ORDER BY createdAt DESC")
    fun _observeDiscussions(objectId: String): Flow<List<DiscussionRow>>
    fun observeDiscussions(objectId: String): Flow<List<Discussion>> =
        _observeDiscussions(objectId).map { rows -> rows.map { it.toModel() } }

    @Query("SELECT * FROM discussion WHERE objectId = :objectId ORDER BY createdAt DESC")
    suspend fun _discussionsForObject(objectId: String): List<DiscussionRow>
    suspend fun discussionsForObject(objectId: String): List<Discussion> =
        _discussionsForObject(objectId).map { it.toModel() }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertDiscussions(rows: List<DiscussionRow>)
    suspend fun insertDiscussions(discussions: List<Discussion>) =
        _insertDiscussions(discussions.map { it.toRow() })

    @Query("DELETE FROM discussion WHERE documentId = :documentId")
    suspend fun deleteDiscussion(documentId: String)

    @Query("DELETE FROM discussion WHERE objectId = :objectId")
    suspend fun deleteDiscussionsForObject(objectId: String)

    @Transaction
    suspend fun replaceDiscussionsForObject(objectId: String, discussions: List<Discussion>) {
        deleteDiscussionsForObject(objectId)
        insertDiscussions(discussions)
    }

    // ── Pack removal ────────────────────────────────────────────────────────

    /** Removes everything cached for a pack that the user deleted (votes stay, as on iOS). */
    @Transaction
    suspend fun deletePackSocial(trackPackId: String) {
        deleteCheckInsForPack(trackPackId)
        deleteContributedWaypointsForPack(trackPackId)
        deleteSyncStates(trackPackId)
    }
}
