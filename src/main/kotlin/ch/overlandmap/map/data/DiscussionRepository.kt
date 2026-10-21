package ch.overlandmap.map.data

import android.util.Log
import ch.overlandmap.map.data.local.SocialDao
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.model.CrossingVehicle
import ch.overlandmap.map.model.Discussion
import ch.overlandmap.map.model.Vote
import ch.overlandmap.map.social.DiscussionRules
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * The community discussions of the Overlanding objects (country, border, border post, zone):
 * corrections, input, replies, border-post suggestions and crossing reports (iOS
 * `DiscussionWriter`). They live in Firestore's `discussion` collection and are cached in Room;
 * a fetch replaces an object's cached discussions, and happens at most weekly unless forced.
 */
class DiscussionRepository(
    private val dao: SocialDao,
    private val auth: AuthRepository,
    private val prefs: UserPreferences,
    /** The name put on posts (the profile's display name, else "Anonymous"). */
    private val userName: suspend () -> String,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** The cached discussions of an object, live. */
    fun observe(objectId: String): Flow<List<Discussion>> =
        dao.observeDiscussions(objectId).map { list -> list.filter { !it.isDeleted } }

    /**
     * Fetches the object's discussions when the cache is older than a week or [force] is set,
     * replacing the cached ones. Returns false when the fetch failed (the cache is kept).
     */
    suspend fun refresh(objectId: String, force: Boolean = false): Boolean {
        if (objectId.isEmpty()) return true
        val key = "lastFetch.discussion.$objectId"
        if (!force && !isStale(key) && dao.discussionsForObject(objectId).isNotEmpty()) return true
        return try {
            auth.awaitUser()
            val remote = db.collection(COLLECTION).whereEqualTo("objectId", objectId).get().await()
                .documents.map { Discussion.fromFirestore(it.id, it.data ?: emptyMap()) }
            dao.replaceDiscussionsForObject(objectId, remote)
            prefs.setOverlandingString(key, now().toString())
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Fetching discussions of $objectId failed", e)
            false
        }
    }

    /**
     * Fetches the votes of an object's discussions (and of the object itself), weekly or when
     * [force]d; they are what the up/down counters and the user's own vote read.
     */
    suspend fun refreshVotes(objectId: String, discussionIds: Collection<String>, force: Boolean = false) {
        if (objectId.isEmpty()) return
        val key = "lastFetch.votes.$objectId"
        if (!force && !isStale(key)) return
        try {
            auth.awaitUser()
            val ids = (discussionIds + objectId).toSet().toList()
            val votes = mutableListOf<Vote>()
            // Firestore's `in` queries take at most 30 values.
            ids.chunked(30).forEach { chunk ->
                db.collection("vote").whereIn("objectId", chunk).get().await().documents
                    .mapTo(votes) { Vote.fromFirestore(it.id, it.data ?: emptyMap()) }
            }
            if (votes.isNotEmpty()) dao.insertVotes(votes)
            prefs.setOverlandingString(key, now().toString())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Fetching votes of $objectId failed", e)
        }
    }

    /** The ids of the cached discussions of an object (what their votes are fetched for). */
    suspend fun cachedIds(objectId: String): List<String> =
        dao.discussionsForObject(objectId).map { it.documentId }

    private fun isStale(key: String): Boolean {
        val last = prefs.overlandingStringNow(key)?.toLongOrNull() ?: return true
        return now() - last >= WEEK_MS
    }

    /** The signed-in session (anonymous included, as on iOS), or null before the first sign-in. */
    val currentUserId: String? get() = auth.currentUser?.uid

    /**
     * Writes a correction, input, reply or suggestion. [topic] is the field name for a correction
     * and the reason for input; replies carry the [threadId] of the discussion they answer.
     */
    suspend fun create(
        objectId: String,
        collection: String,
        topic: String,
        reason: String,
        content: String,
        lang: String?,
        threadId: String? = null,
        location: Coordinate? = null,
        isOpen: Int? = null,
    ): Discussion {
        val user = auth.awaitUser()
        val new = DiscussionRules.NewDiscussion(
            id = UUID.randomUUID().toString(),
            objectId = objectId, collection = collection,
            userId = user.uid, userName = userName(),
            topic = topic, reason = reason, content = content,
            lang = lang, threadId = threadId, location = location, isOpen = isOpen,
            nowMs = now(),
        )
        db.collection(COLLECTION).document(new.id).set(DiscussionRules.firestorePayload(new)).await()
        return DiscussionRules.localCopy(new).also { dao.insertDiscussions(listOf(it)) }
    }

    /** Writes a border crossing report (a testimonial about a border post). */
    suspend fun createCrossingReport(
        borderPostId: String,
        direction: String,
        passportCountryId: String,
        vehicle: CrossingVehicle,
        timeSpent: String?,
        amountPaid: String?,
        crossingDateMs: Long?,
        content: String,
        lang: String?,
    ): Discussion {
        val user = auth.awaitUser()
        if (user.isAnonymous) throw SocialRepository.NotSignedInException()
        val report = DiscussionRules.NewCrossingReport(
            id = UUID.randomUUID().toString(),
            borderPostId = borderPostId, userId = user.uid, userName = userName(),
            direction = direction, passportCountryId = passportCountryId, vehicle = vehicle,
            timeSpent = timeSpent?.trim()?.takeIf { it.isNotEmpty() },
            amountPaid = amountPaid?.trim()?.takeIf { it.isNotEmpty() },
            crossingDateMs = crossingDateMs, content = content, lang = lang, nowMs = now(),
        )
        db.collection(COLLECTION).document(report.id).set(DiscussionRules.firestorePayload(report)).await()
        return DiscussionRules.localCopy(report).also { dao.insertDiscussions(listOf(it)) }
    }

    /** Deletes a discussion; Firestore's rules decide who may (the author). */
    suspend fun delete(documentId: String) {
        auth.awaitUser()
        db.collection(COLLECTION).document(documentId).delete().await()
        dao.deleteDiscussion(documentId)
    }

    private companion object {
        const val COLLECTION = "discussion"
        const val TAG = "DiscussionRepository"
        const val WEEK_MS = 7L * 24 * 3600 * 1000
    }
}
