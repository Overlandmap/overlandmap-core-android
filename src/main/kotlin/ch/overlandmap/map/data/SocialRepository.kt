package ch.overlandmap.map.data

import android.util.Log
import ch.overlandmap.map.data.local.FtsDoc
import ch.overlandmap.map.data.local.FtsIndex
import ch.overlandmap.map.data.local.LibraryDao
import ch.overlandmap.map.data.local.SocialDao
import ch.overlandmap.map.data.local.SyncStateRow
import ch.overlandmap.map.model.CheckIn
import ch.overlandmap.map.model.CheckInReason
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.ContributedWaypoint
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.Geohash
import ch.overlandmap.map.model.Vote
import ch.overlandmap.map.social.SocialRules
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Firestore implementation of [SocialDataSource]. Every query is forced to the server
 * ([Source.SERVER]) so being offline throws instead of returning an empty cached result,
 * which would otherwise be stamped as a successful sync.
 */
class FirestoreSocialDataSource(
    private val db: FirebaseFirestore,
    private val auth: AuthRepository,
) : SocialDataSource {

    override suspend fun fetchComments(trackPackId: String): List<Comment> =
        fetch("comment", trackPackId).map { Comment.fromFirestore(it.first, it.second) }

    override suspend fun fetchCheckIns(trackPackId: String): List<CheckIn> =
        fetch("check_in", trackPackId).mapNotNull { CheckIn.fromFirestore(it.first, it.second) }

    override suspend fun fetchVotes(trackPackId: String): List<Vote> =
        fetch("vote", trackPackId).map { Vote.fromFirestore(it.first, it.second) }

    override suspend fun fetchContributedWaypoints(trackPackId: String): List<ContributedWaypoint> =
        fetch("contributed_waypoint", trackPackId, activeOnly = true).map {
            ContributedWaypoint.fromFirestore(it.first, it.second, fallbackTrackPackId = trackPackId)
        }

    private suspend fun fetch(
        collection: String,
        trackPackId: String,
        activeOnly: Boolean = false,
    ): List<Pair<String, Map<String, Any?>>> {
        auth.awaitUser()
        var query = db.collection(collection).whereEqualTo("trackPackId", trackPackId)
        if (activeOnly) query = query.whereEqualTo("active", true)
        return query.get(Source.SERVER).await().documents.map { it.id to (it.data ?: emptyMap()) }
    }
}

/**
 * Manages the local cache of social/community data (comments, check-ins, votes, contributed
 * waypoints) per track pack: syncs it from Firestore under the per-kind [SyncPolicy] and
 * provides reactive access for the UI.
 */
class SocialRepository(
    private val socialDao: SocialDao,
    private val libraryDao: LibraryDao,
    private val auth: AuthRepository,
    private val fts: FtsIndex? = null,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    policy: SyncPolicy = SyncPolicy.RELEASE,
    /** The name put on posts (the profile's display name, else "Anonymous"). */
    private val userName: suspend () -> String = { ANONYMOUS },
) {

    private val cache = object : SocialCache {
        override suspend fun lastSync(trackPackId: String, kind: SyncKind): Long? =
            socialDao.lastSync(trackPackId, kind.raw)

        override suspend fun setLastSync(trackPackId: String, kind: SyncKind, time: Long) =
            socialDao.upsertSyncState(SyncStateRow(trackPackId, kind.raw, time))

        override suspend fun clearLastSync(trackPackId: String, kind: SyncKind) =
            socialDao.deleteSyncState(trackPackId, kind.raw)

        override suspend fun replaceComments(trackPackId: String, comments: List<Comment>) =
            libraryDao.replaceCommentsOfPack(trackPackId, comments)

        override suspend fun replaceCheckIns(trackPackId: String, checkIns: List<CheckIn>) =
            socialDao.replaceCheckInsForPack(trackPackId, checkIns)

        override suspend fun replaceVotes(trackPackId: String, votes: List<Vote>) =
            socialDao.replaceVotesForPack(trackPackId, votes)

        override suspend fun replaceContributedWaypoints(
            trackPackId: String,
            waypoints: List<ContributedWaypoint>,
        ) {
            val previous = socialDao.contributedWaypointIdsOfPack(trackPackId)
            socialDao.replaceContributedWaypointsForPack(trackPackId, waypoints)
            // Contributed waypoints are searchable, so they are indexed like any other object.
            fts?.deleteDocuments(previous)
            fts?.index(
                waypoints.map { w ->
                    FtsDoc(
                        type = FtsIndex.TYPE_CONTRIBUTED_WAYPOINT,
                        documentId = w.documentId,
                        name = w::name,
                        description = { w.description(it).orEmpty() },
                        trackPackId = w.trackPackId,
                    )
                }
            )
        }
    }

    private val coordinator = SyncCoordinator(
        cache = cache,
        source = FirestoreSocialDataSource(db, auth),
        policy = policy,
        onError = { kind, packId, e -> Log.w(TAG, "Sync of ${kind.raw} failed for $packId", e) },
    )

    // ── Sync ────────────────────────────────────────────────────────────────

    /**
     * Refreshes the kinds of social data of [trackPackId] whose cache is missing or past its
     * TTL; [force] refreshes all of them. One failing kind doesn't stop the others.
     */
    suspend fun syncTrackPackSocialData(trackPackId: String, force: Boolean = false) =
        coordinator.syncTrackPackSocialData(trackPackId, force)

    /** Refreshes one [kind] when its cache is stale (the comments tab does this on entry); failures are ignored. */
    suspend fun syncIfNeeded(trackPackId: String, kind: SyncKind) {
        try {
            coordinator.syncIfNeeded(trackPackId, kind)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync of ${kind.raw} failed for $trackPackId", e)
        }
    }

    /** Fetches [kind] regardless of freshness (before saving a comment, to see one written on another device). */
    suspend fun forceSync(trackPackId: String, kind: SyncKind) {
        try {
            coordinator.sync(trackPackId, kind)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Forced sync of ${kind.raw} failed for $trackPackId", e)
        }
    }

    /** When [kind] of [trackPackId] was last synced (epoch millis), or null. */
    suspend fun lastSync(trackPackId: String, kind: SyncKind): Long? =
        coordinator.lastSync(trackPackId, kind)

    /** Forces the next sync of [kind] to refetch. */
    suspend fun invalidate(trackPackId: String, kind: SyncKind) = coordinator.invalidate(trackPackId, kind)

    // ── Check-ins and corrections ───────────────────────────────────────────
    //
    // A correction is a check-in whose reason says what is wrong (iOS `CorrectionWriter`), so both
    // live in the `check_in` collection and table.

    /** The signed-in, non-anonymous user, or [NotSignedInException]: only real accounts post. */
    private suspend fun requireUser(allowAnonymous: Boolean = false): com.google.firebase.auth.FirebaseUser {
        auth.awaitUser()
        val user = auth.currentUser
        if (user == null || (user.isAnonymous && !allowAnonymous)) throw NotSignedInException()
        return user
    }

    fun observeCheckInsForGeohash(geohash: String): Flow<List<CheckIn>> =
        socialDao.observeCheckInsForGeohash(geohash).map { list -> list.filter { it.reason == CheckInReason.CHECK } }

    fun observeCorrectionsForGeohash(geohash: String): Flow<List<CheckIn>> =
        socialDao.observeCheckInsForGeohash(geohash).map { list -> list.filter { it.reason.indicatesProblem } }

    fun observeCorrectionsForObject(objectId: String): Flow<List<CheckIn>> =
        socialDao.observeCorrectionsForObject(objectId)

    fun observeActiveCorrectionForGeohash(geohash: String): Flow<CheckIn?> =
        socialDao.observeCheckInsForGeohash(geohash).map { list -> list.firstOrNull { it.reason.indicatesProblem } }

    suspend fun refreshCheckInsForGeohash(geohash: String) {
        try {
            auth.awaitUser()
            val remote = db.collection(CHECK_IN).whereEqualTo("geohash", geohash).get().await()
                .documents.mapNotNull { CheckIn.fromFirestore(it.id, it.data ?: emptyMap()) }
            socialDao.replaceCheckInsForGeohash(geohash, remote)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { Log.w("SocialRepository", "Fetching waypoint check-ins failed", e) }
    }

    fun observeCheckInsForObject(objectId: String): Flow<List<CheckIn>> =
        socialDao.observeCheckInsForObject(objectId)

    suspend fun checkInsForObject(objectId: String): List<CheckIn> =
        socialDao.checkInsForObject(objectId)

    /** The newest correction of an object (a check-in with a problem reason), live. */
    fun observeActiveCorrection(objectId: String): Flow<CheckIn?> =
        socialDao.observeActiveCorrection(objectId)

    /**
     * Creates a check-in in Firestore and inserts it into the local cache, with the payload the
     * iOS `CheckInWriter` writes (reason `check`, `checkInDate`, default user name "Anonymous").
     * Returns the new document ID.
     */
    suspend fun createCheckIn(
        trackPackId: String,
        itinDocumentId: String,
        collection: String,
        objectId: String,
        content: String?,
        geohash: String? = null,
    ): String = postCheckIn(
        trackPackId, itinDocumentId, collection, objectId, CheckInReason.CHECK,
        content?.takeIf { it.isNotBlank() }, null, geohash,
    ).documentId

    /**
     * Posts a correction of a step or waypoint: [reason] is one of [SocialRules.correctionReasons],
     * [content] is required, and [location] (the right position) only goes with `wrongLocation`.
     */
    suspend fun createCorrection(
        trackPackId: String,
        itinDocumentId: String,
        collection: String,
        objectId: String,
        reason: CheckInReason,
        content: String,
        location: Coordinate?,
        geohash: String? = null,
    ): CheckIn = postCheckIn(
        trackPackId, itinDocumentId, collection, objectId, reason, content,
        location.takeIf { reason == CheckInReason.WRONG_LOCATION }, geohash,
    )

    private suspend fun postCheckIn(
        trackPackId: String,
        itinDocumentId: String,
        collection: String,
        objectId: String,
        reason: CheckInReason,
        content: String?,
        location: Coordinate?,
        geohash: String?,
    ): CheckIn {
        val user = requireUser()
        val documentId = UUID.randomUUID().toString()
        val now = Timestamp.now()
        val name = userName()
        val data = buildMap<String, Any?> {
            if (geohash == null) put("objectId", objectId)
            geohash?.let { put("geohash", it) }
            put("collection", collection)
            if (geohash == null) put("trackPackId", trackPackId)
            if (geohash == null) put("itinDocumentId", itinDocumentId)
            put("userId", user.uid)
            put("userName", name)
            put("createdAt", mapOf("_seconds" to now.seconds, "_nanoseconds" to now.nanoseconds))
            put("checkInDate", now)
            put("reason", reason.raw)
            if (!content.isNullOrBlank()) put("content", content)
            if (location != null) put("location", GeoPoint(location.latitude, location.longitude))
        }
        db.collection(CHECK_IN).document(documentId).set(data).await()
        val checkIn = CheckIn(
            documentId = documentId,
            objectId = if (geohash == null) objectId else "",
            trackPackId = trackPackId.takeIf { geohash == null },
            itinDocumentId = itinDocumentId.takeIf { geohash == null },
            reason = reason,
            createdAt = now.toDate().time,
            userId = user.uid,
            extra = buildMap {
                geohash?.let { put("geohash", it) }
                put("userName", name)
                put("collection", collection)
                if (!content.isNullOrBlank()) put("content", content)
                if (location != null) {
                    put("location", mapOf("latitude" to location.latitude, "longitude" to location.longitude))
                }
            },
        )
        socialDao.insertCheckIn(checkIn)
        return checkIn
    }

    /** Edits the user's own correction; a null [location] removes the stored position. */
    suspend fun updateCorrection(documentId: String, reason: CheckInReason, content: String, location: Coordinate?) {
        requireUser()
        val position = location.takeIf { reason == CheckInReason.WRONG_LOCATION }
        db.collection(CHECK_IN).document(documentId).update(
            mapOf(
                "reason" to reason.raw,
                "content" to content,
                "location" to (position?.let { GeoPoint(it.latitude, it.longitude) } ?: FieldValue.delete()),
            )
        ).await()
        val local = socialDao.checkIn(documentId) ?: return
        val extra = local.extra.toMutableMap()
        extra["content"] = content
        if (position != null) {
            extra["location"] = mapOf("latitude" to position.latitude, "longitude" to position.longitude)
        } else {
            extra.remove("location")
        }
        socialDao.insertCheckIn(local.copy(reason = reason, extra = extra))
    }

    /** Deletes a check-in or correction from both Firestore and the local cache. */
    suspend fun deleteCheckIn(documentId: String) {
        auth.awaitUser()
        db.collection(CHECK_IN).document(documentId).delete().await()
        socialDao.deleteCheckIn(documentId)
    }

    // ── Comments ────────────────────────────────────────────────────────────

    /**
     * Posts a comment on an itinerary ([itinDocumentId] set, [objectId] its id) or on a pack
     * ([itinDocumentId] null, [objectId] the pack id). `createdAt` is epoch milliseconds, the way
     * iOS writes comments. The rating is clamped to 0..5.
     */
    suspend fun createComment(
        objectId: String,
        trackPackId: String,
        itinDocumentId: String?,
        content: String,
        langCode: String?,
        rating: Int,
    ): Comment {
        val user = requireUser()
        val documentId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val name = userName()
        val stars = rating.coerceIn(0, 5)
        val data = buildMap<String, Any?> {
            put("objectId", objectId)
            put("trackPackId", trackPackId)
            put("userId", user.uid)
            put("userName", name)
            put("content", content)
            put("rating", stars)
            put("createdAt", now)
            if (!itinDocumentId.isNullOrEmpty()) put("itinDocumentId", itinDocumentId)
            if (!langCode.isNullOrEmpty()) put("langCode", langCode)
        }
        db.collection(COMMENT).document(documentId).set(data).await()
        val comment = Comment(
            documentId = documentId,
            objectId = objectId,
            content = content,
            langCode = langCode?.takeIf { it.isNotEmpty() },
            userName = name,
            createdAt = now,
            rating = stars,
            itinDocumentId = itinDocumentId?.takeIf { it.isNotEmpty() },
            userId = user.uid,
            trackPackId = trackPackId,
        )
        libraryDao.insertComment(comment)
        return comment
    }

    /** Deletes a comment; Firestore's rules decide who may (the author, or an admin). */
    suspend fun deleteComment(documentId: String) {
        auth.awaitUser()
        db.collection(COMMENT).document(documentId).delete().await()
        libraryDao.deleteComment(documentId)
    }

    // ── Votes ───────────────────────────────────────────────────────────────

    fun observeVotesForObject(objectId: String): Flow<List<Vote>> =
        socialDao.observeVotesForObject(objectId)

    /** The signed-in user's own vote on [objectId], live. */
    fun observeUserVote(objectId: String, userId: String): Flow<Vote?> =
        socialDao.observeUserVote(objectId, userId)

    /**
     * Applies a tap on an up or down button ([SocialRules.voteAction]): the same vote again
     * removes it, the other one switches it, none creates it. `createdAt` is an ISO string, the
     * way iOS writes votes.
     */
    suspend fun castVote(
        objectId: String,
        trackPackId: String?,
        tappedUp: Boolean,
        /** Overlanding discussions accept votes from anonymous sessions (as on iOS). */
        allowAnonymous: Boolean = false,
    ): SocialRules.VoteAction {
        val user = requireUser(allowAnonymous)
        val action = SocialRules.voteAction(socialDao.userVote(objectId, user.uid), tappedUp)
        when (action) {
            is SocialRules.VoteAction.Create -> {
                val documentId = UUID.randomUUID().toString()
                val now = System.currentTimeMillis()
                val data = buildMap<String, Any?> {
                    put("objectId", objectId)
                    put("userId", user.uid)
                    put("createdAt", java.time.Instant.ofEpochMilli(now).toString())
                    put("upVote", tappedUp)
                    if (!trackPackId.isNullOrEmpty()) put("trackPackId", trackPackId)
                }
                db.collection(VOTE).document(documentId).set(data).await()
                socialDao.insertVote(
                    Vote(documentId, tappedUp, objectId, trackPackId?.takeIf { it.isNotEmpty() }, null, now, user.uid)
                )
            }
            is SocialRules.VoteAction.Update -> {
                db.collection(VOTE).document(action.vote.documentId).update("upVote", tappedUp).await()
                socialDao.updateVoteValue(action.vote.documentId, if (tappedUp) 1 else 0)
            }
            is SocialRules.VoteAction.Delete -> {
                db.collection(VOTE).document(action.vote.documentId).delete().await()
                socialDao.deleteVote(action.vote.documentId)
            }
        }
        return action
    }

    // ── Contributed waypoints ───────────────────────────────────────────────

    fun observeContributedWaypoints(itineraryId: String): Flow<List<ContributedWaypoint>> =
        socialDao.observeContributedWaypointsOfItinerary(itineraryId)

    /** Shares a new waypoint with the community (iOS `ContributedWaypointWriter.create`). */
    suspend fun createContributedWaypoint(
        trackPackId: String,
        itinDocumentId: String,
        name: String,
        content: String,
        coordinate: Coordinate,
    ): ContributedWaypoint {
        val user = requireUser()
        val documentId = UUID.randomUUID().toString()
        val now = Timestamp.now()
        val userDisplayName = userName()
        val geohash = Geohash.encode(coordinate.latitude, coordinate.longitude)
        val data = mapOf(
            "documentId" to documentId,
            "trackPackId" to trackPackId,
            "itinDocumentId" to itinDocumentId,
            "userId" to user.uid,
            "userName" to userDisplayName,
            "name" to name,
            "content" to content,
            "type" to DEFAULT_WAYPOINT_TYPE,
            "maki" to DEFAULT_WAYPOINT_MAKI,
            "active" to true,
            "geohash" to geohash,
            "location" to GeoPoint(coordinate.latitude, coordinate.longitude),
            "lastUpdate" to now,
            "createdAt" to now,
        )
        db.collection(CONTRIBUTED_WAYPOINT).document(documentId).set(data).await()
        val waypoint = ContributedWaypoint(
            documentId = documentId,
            trackPackId = trackPackId,
            geohash = geohash,
            type = DEFAULT_WAYPOINT_TYPE,
            itinDocumentId = itinDocumentId,
            lastUpdate = now.toDate().time,
            userId = user.uid,
            isActive = true,
            name = name,
            lat = coordinate.latitude,
            lon = coordinate.longitude,
            extra = mapOf("userName" to userDisplayName, "content" to content, "maki" to DEFAULT_WAYPOINT_MAKI),
        )
        socialDao.insertContributedWaypoint(waypoint)
        indexContributed(waypoint)
        return waypoint
    }

    suspend fun updateContributedWaypoint(documentId: String, name: String, content: String, coordinate: Coordinate) {
        requireUser()
        val geohash = Geohash.encode(coordinate.latitude, coordinate.longitude)
        val now = Timestamp.now()
        db.collection(CONTRIBUTED_WAYPOINT).document(documentId).update(
            mapOf(
                "name" to name,
                "content" to content,
                "type" to DEFAULT_WAYPOINT_TYPE,
                "maki" to DEFAULT_WAYPOINT_MAKI,
                "geohash" to geohash,
                "location" to GeoPoint(coordinate.latitude, coordinate.longitude),
                "lastUpdate" to now,
            )
        ).await()
        val local = socialDao.contributedWaypoint(documentId) ?: return
        val updated = local.copy(
            name = name,
            geohash = geohash,
            lat = coordinate.latitude,
            lon = coordinate.longitude,
            lastUpdate = now.toDate().time,
            extra = local.extra + mapOf("content" to content, "maki" to DEFAULT_WAYPOINT_MAKI),
        )
        socialDao.insertContributedWaypoint(updated)
        indexContributed(updated)
    }

    /** Hard-deletes a shared waypoint (the author's action, confirmed in the UI). */
    suspend fun deleteContributedWaypoint(documentId: String) {
        auth.awaitUser()
        db.collection(CONTRIBUTED_WAYPOINT).document(documentId).delete().await()
        socialDao.deleteContributedWaypoint(documentId)
        fts?.deleteDocuments(listOf(documentId))
    }

    private suspend fun indexContributed(w: ContributedWaypoint) {
        fts ?: return
        fts.deleteDocuments(listOf(w.documentId))
        fts.index(
            listOf(
                FtsDoc(
                    type = FtsIndex.TYPE_CONTRIBUTED_WAYPOINT,
                    documentId = w.documentId,
                    name = w::name,
                    trackPackId = w.trackPackId,
                    description = { w.description(it).orEmpty() },
                )
            )
        )
    }

    /** Thrown by the writers when nobody (or only the anonymous session) is signed in. */
    class NotSignedInException : IllegalStateException("Sign in required")

    companion object {
        private const val TAG = "SocialRepository"
        const val ANONYMOUS = "Anonymous"
        private const val CHECK_IN = "check_in"
        private const val COMMENT = "comment"
        private const val VOTE = "vote"
        private const val CONTRIBUTED_WAYPOINT = "contributed_waypoint"
        const val DEFAULT_WAYPOINT_TYPE = "point"
        const val DEFAULT_WAYPOINT_MAKI = "marker"
    }
}
