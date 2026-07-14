package ch.overlandmap.map.data

import ch.overlandmap.map.data.downloads.DownloadRefusedException
import ch.overlandmap.map.model.AppUser
import ch.overlandmap.map.model.Asset
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.UserPurchase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await

/**
 * The online shop: track packs and itineraries read straight from Firestore.
 * Unavailable offline by design — the local library (Room) is the offline side.
 * Every request waits for the startup sign-in ([AuthRepository.awaitUser]).
 */
class ShopRepository(
    private val auth: AuthRepository,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(),
) {

    suspend fun trackPacks(): List<TrackPack> {
        auth.awaitUser()
        return db.collection("track_pack")
            .whereEqualTo("online", true)
            .get().await()
            .documents
            .map { TrackPack.fromFirestore(it.id, it.data ?: emptyMap()) }
            .sortedBy { it.name }
    }

    suspend fun trackPack(id: String): TrackPack? {
        auth.awaitUser()
        val doc = db.collection("track_pack").document(id).get().await()
        return doc.data?.let { TrackPack.fromFirestore(doc.id, it) }
    }

    suspend fun itineraries(trackPackId: String): List<Itinerary> {
        auth.awaitUser()
        return db.collection("itinerary")
            .whereEqualTo("trackPackId", trackPackId)
            .get().await()
            .documents
            .map { Itinerary.fromFirestore(it.id, it.data ?: emptyMap()) }
            .sortedBy { it.name }
    }

    /** One downloadable asset (collection `asset`); null when missing. */
    suspend fun asset(id: String): Asset? {
        auth.awaitUser()
        val doc = db.collection("asset").document(id).get().await()
        return doc.data?.let { Asset.fromFirestore(doc.id, it) }
    }

    /**
     * Temporary download URL of a purchased pack's zip, from the
     * `downloadTrackPackUrl` cloud function (which checks the purchase).
     * Throws [DownloadRefusedException] with the backend's message when the
     * function answers `{error: …}` instead of `{url: …}`.
     */
    suspend fun downloadPackUrl(packId: String): String {
        auth.awaitUser()
        val response = functions.getHttpsCallable("downloadTrackPackUrl").call(packId).await()
        val data = response.getData() as? Map<*, *>
            ?: throw DownloadRefusedException("Unexpected response")
        (data["url"] as? String)?.let { return it }
        throw DownloadRefusedException(data["error"] as? String ?: "Unknown error")
    }

    /**
     * Comments on a track pack, newest first. Sorted client-side so the query
     * needs no composite index (the Flutter app orders server-side).
     */
    suspend fun comments(packId: String): List<Comment> {
        auth.awaitUser()
        return db.collection("comment")
            .whereEqualTo("objectId", packId)
            .limit(100)
            .get().await()
            .documents
            .map { Comment.fromFirestore(it.id, it.data ?: emptyMap()) }
            .sortedByDescending { it.createdAt ?: 0L }
    }

    /**
     * The signed-in user's profile document (`users/{uid}`), updated in real
     * time. Null for anonymous sessions and while no document exists yet;
     * only the backend's stored functions write it.
     */
    fun userDocFlow(): Flow<AppUser?> = flow {
        val user = auth.awaitUser()
        if (user.isAnonymous) {
            emit(null)
            return@flow
        }
        val listener = callbackFlow {
            val registration = db.collection("users").document(user.uid)
                .addSnapshotListener { snapshot, _ ->
                    trySend(
                        snapshot?.data?.let { AppUser.fromFirestore(snapshot.id, it) }
                    )
                }
            awaitClose { registration.remove() }
        }
        emitAll(listener)
    }

    /**
     * The signed-in user's validated purchases. Only the `validateGoogleToken`
     * cloud function writes to this collection. Anonymous sessions have none.
     */
    fun purchasesFlow(): Flow<List<UserPurchase>> = flow {
        val user = auth.awaitUser()
        if (user.isAnonymous) {
            emit(emptyList())
            return@flow
        }
        val listener = callbackFlow {
            val registration = db.collection("users").document(user.uid).collection("purchases")
                .addSnapshotListener { snapshot, _ ->
                    val purchases = snapshot?.documents
                        ?.map { UserPurchase.fromFirestore(it.id, it.data ?: emptyMap()) }
                        ?: emptyList()
                    trySend(purchases)
                }
            awaitClose { registration.remove() }
        }
        emitAll(listener)
    }
}
