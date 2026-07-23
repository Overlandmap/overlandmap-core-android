package ch.overlandmap.map.data

import ch.overlandmap.map.data.local.CheckInRow
import ch.overlandmap.map.data.local.ContributedWaypointRow
import ch.overlandmap.map.data.local.LibraryDao
import ch.overlandmap.map.data.local.SocialDao
import ch.overlandmap.map.data.local.SocialSyncRow
import ch.overlandmap.map.data.local.VoteRow
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.FS
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await

/**
 * Manages the local cache of social/community data (check-ins, votes,
 * comments, contributed waypoints) per track pack. Syncs from Firestore and
 * provides reactive access for the UI.
 */
class SocialRepository(
    private val socialDao: SocialDao,
    private val libraryDao: LibraryDao,
    private val auth: AuthRepository,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    companion object {
        /** Minimum interval between automatic syncs for a given pack (24 h). */
        private const val SYNC_INTERVAL_MS = 24L * 60 * 60 * 1000
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** Whether the pack's social cache is stale (never synced or older than 24 h). */
    suspend fun isStale(trackPackId: String): Boolean {
        val last = socialDao.lastSyncedAt(trackPackId) ?: return true
        return System.currentTimeMillis() - last > SYNC_INTERVAL_MS
    }

    /**
     * Syncs social data for [trackPackId] from Firestore into Room if the
     * local cache is stale. Pass [force] = true to ignore the staleness check.
     */
    suspend fun syncIfNeeded(trackPackId: String, force: Boolean = false) {
        if (!force && !isStale(trackPackId)) return
        sync(trackPackId)
    }

    /** Forces a full sync regardless of staleness. */
    suspend fun sync(trackPackId: String) {
        auth.awaitUser()
        syncCheckIns(trackPackId)
        syncVotes(trackPackId)
        syncComments(trackPackId)
        syncContributedWaypoints(trackPackId)
        socialDao.upsertSync(SocialSyncRow(trackPackId, System.currentTimeMillis()))
    }

    // ── Check-ins ───────────────────────────────────────────────────────────

    fun observeCheckInsForObject(objectId: String): Flow<List<CheckInRow>> =
        socialDao.observeCheckInsForObject(objectId)

    suspend fun checkInsForObject(objectId: String): List<CheckInRow> =
        socialDao.checkInsForObject(objectId)

    /**
     * Creates a check-in in Firestore and inserts it into the local cache.
     * Returns the new document ID.
     */
    suspend fun createCheckIn(
        trackPackId: String,
        itinDocumentId: String,
        collection: String,
        objectId: String,
        content: String?,
    ): String {
        auth.awaitUser()
        val user = auth.currentUser ?: error("Not signed in")
        val data = buildMap<String, Any?> {
            put("trackPackId", trackPackId)
            put("itinDocumentId", itinDocumentId)
            put("collection", collection)
            put("objectId", objectId)
            put("userId", user.uid)
            put("userName", user.displayName ?: user.email ?: "")
            put("createdAt", com.google.firebase.Timestamp.now())
            if (!content.isNullOrBlank()) put("content", content)
        }
        val docRef = db.collection("check_in").add(data).await()
        val row = CheckInRow(
            documentId = docRef.id,
            objectId = objectId,
            trackPackId = trackPackId,
            itinDocumentId = itinDocumentId,
            collection = collection,
            reason = null,
            content = content,
            createdAt = System.currentTimeMillis(),
            userId = user.uid,
            userName = user.displayName ?: user.email ?: "",
            json = null,
        )
        socialDao.insertCheckIn(row)
        return docRef.id
    }

    /** Deletes a check-in from both Firestore and the local cache. */
    suspend fun deleteCheckIn(documentId: String) {
        auth.awaitUser()
        db.collection("check_in").document(documentId).delete().await()
        socialDao.deleteCheckIn(documentId)
    }

    // ── Votes ───────────────────────────────────────────────────────────────

    fun observeVotesForObject(objectId: String): Flow<List<VoteRow>> =
        socialDao.observeVotesForObject(objectId)

    // ── Private sync methods ────────────────────────────────────────────────

    private suspend fun syncCheckIns(trackPackId: String) {
        val snapshot = db.collection("check_in")
            .whereEqualTo("trackPackId", trackPackId)
            .get().await()
        val rows = snapshot.documents.map { doc ->
            val data = doc.data ?: emptyMap()
            CheckInRow(
                documentId = doc.id,
                objectId = FS.str(data["objectId"]),
                trackPackId = trackPackId,
                itinDocumentId = FS.str(data["itinDocumentId"]),
                collection = FS.str(data["collection"]),
                reason = FS.str(data["reason"]),
                content = FS.str(data["content"]),
                createdAt = FS.millis(data["createdAt"]),
                userId = FS.str(data["userId"]),
                userName = FS.str(data["userName"]),
                json = null,
            )
        }
        socialDao.replaceCheckInsForPack(trackPackId, rows)
    }

    private suspend fun syncVotes(trackPackId: String) {
        val snapshot = db.collection("vote")
            .whereEqualTo("trackPackId", trackPackId)
            .get().await()
        val rows = snapshot.documents.map { doc ->
            val data = doc.data ?: emptyMap()
            VoteRow(
                documentId = doc.id,
                objectId = FS.str(data["objectId"]),
                trackPackId = trackPackId,
                content = FS.str(data["content"]),
                createdAt = FS.millis(data["createdAt"]),
                userId = FS.str(data["userId"]),
                userName = FS.str(data["userName"]),
                upVote = if (FS.bool(data["upVote"])) 1 else 0,
            )
        }
        socialDao.replaceVotesForPack(trackPackId, rows)
    }

    private suspend fun syncComments(trackPackId: String) {
        // Fetch all comments for all itineraries of this pack + the pack itself.
        val objectIds = buildList {
            add(trackPackId)
            libraryDao.itinerariesOf(trackPackId).forEach { add(it.documentId) }
        }
        // Firestore 'in' queries support max 30 values; batch if needed.
        objectIds.chunked(30).forEach { chunk ->
            val snapshot = db.collection("comment")
                .whereIn("objectId", chunk)
                .get().await()
            val comments = snapshot.documents.map { doc ->
                Comment.fromFirestore(doc.id, doc.data ?: emptyMap())
            }
            // Group by objectId and replace each batch in Room.
            comments.groupBy { it.objectId }.forEach { (objectId, list) ->
                libraryDao.replaceComments(objectId, list)
            }
        }
    }

    private suspend fun syncContributedWaypoints(trackPackId: String) {
        val snapshot = db.collection("contributed_waypoint")
            .whereEqualTo("trackPackId", trackPackId)
            .get().await()
        val rows = snapshot.documents.map { doc ->
            val data = doc.data ?: emptyMap()
            ContributedWaypointRow(
                documentId = doc.id,
                active = if (FS.bool(data["active"])) 1 else 0,
                trackPackId = trackPackId,
                geohash = FS.str(data["geohash"]),
                type = FS.str(data["type"]),
                itinDocumentId = FS.str(data["itinDocumentId"]),
                lastUpdate = FS.millis(data["lastUpdate"]),
                userId = FS.str(data["userId"]),
                json = null,
            )
        }
        socialDao.replaceContributedWaypointsForPack(trackPackId, rows)
    }
}
