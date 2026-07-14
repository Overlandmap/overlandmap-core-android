package ch.overlandmap.map.data

import ch.overlandmap.map.data.local.LibraryDao
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.Waypoint
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import kotlinx.coroutines.tasks.await

/**
 * The local library: track packs downloaded into Room, and the download
 * itself (Firestore → Room). Everything read from here works offline.
 */
class LibraryRepository(
    private val dao: LibraryDao,
    private val auth: AuthRepository,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    fun observeTrackPacks() = dao.observeTrackPacks()

    fun observeItineraries(trackPackId: String) = dao.observeItineraries(trackPackId)

    /** The most recently opened local itineraries, newest first. */
    fun observeLastOpened(limit: Int = 5) = dao.observeLastOpened(limit)

    /** Records that the user opened this itinerary (drives "Last used"). */
    suspend fun markOpened(itineraryId: String) =
        dao.touchItinerary(itineraryId, System.currentTimeMillis())

    suspend fun itinerariesOf(trackPackId: String) = dao.itinerariesOf(trackPackId)

    suspend fun trackPack(id: String) = dao.trackPack(id)

    suspend fun itinerary(id: String) = dao.itinerary(id)

    suspend fun steps(itineraryId: String) = dao.steps(itineraryId)

    suspend fun tracks(itineraryId: String) = dao.tracks(itineraryId)

    suspend fun waypoints(itineraryId: String) = dao.waypoints(itineraryId)

    suspend fun sidebars(trackPackId: String) = dao.sidebars(trackPackId)

    // Markup-link lookups (see ui/markup/MarkupRouter).

    suspend fun trackPackByName(name: String) = dao.trackPackByName(name)

    suspend fun itineraryBySlug(trackPackId: String, slug: String) =
        dao.itineraryBySlug(trackPackId, slug)

    suspend fun waypointByName(trackPackId: String, name: String) =
        dao.waypointByName(trackPackId, name)

    suspend fun waypointByGeohash(geohash: String) = dao.waypointByGeohash(geohash)

    suspend fun sidebarByName(trackPackId: String, name: String) =
        dao.sidebarByName(trackPackId, name)

    /**
     * Removes the pack from the device: its rows (pack, itineraries, steps,
     * tracks, waypoints, sidebars) and the photos its zips had unpacked.
     */
    suspend fun deletePack(trackPackId: String) {
        val photos = buildList {
            dao.trackPack(trackPackId)?.localPhotoPath?.let(::add)
            dao.itinerariesOf(trackPackId).forEach { itinerary ->
                itinerary.localPhotoPath?.let(::add)
                itinerary.localOtherPhotoPaths?.let(::addAll)
            }
            dao.stepsOfPack(trackPackId).forEach { it.localPhotoPath?.let(::add) }
            dao.sidebars(trackPackId).forEach { it.localPhotoPath?.let(::add) }
        }
        dao.deletePackContent(trackPackId)
        photos.forEach { runCatching { File(it).delete() } }
    }

    /**
     * Removes every purchased pack from the device, keeping the free samples.
     * Used on sign-out: purchased content can be re-downloaded once signed back
     * in, whereas the free samples aren't tied to the account.
     */
    suspend fun deletePurchasedPacks() {
        dao.purchasedPackIds().forEach { deletePack(it) }
    }

    /** Flags the pack as having (or not) a newer zip available online. */
    suspend fun setNeedsUpdate(trackPackId: String, value: Boolean) =
        dao.setNeedsUpdate(trackPackId, value)

    /** The cached comments on a pack or itinerary, newest first. */
    fun observeComments(objectId: String) = dao.observeComments(objectId)

    /**
     * Fetches the comments on [objectId] from Firestore into the local cache.
     * Callers observe the cache; offline this just fails silently at their
     * discretion (the cache keeps its last content).
     */
    suspend fun refreshComments(objectId: String) {
        auth.awaitUser()
        val comments = db.collection("comment")
            .whereEqualTo("objectId", objectId)
            .limit(100)
            .get().await()
            .documents
            .map { Comment.fromFirestore(it.id, it.data ?: emptyMap()).copy(objectId = objectId) }
        dao.replaceComments(objectId, comments)
    }

    /**
     * Stores a pack parsed from a downloaded zip. A full-pack zip
     * ([fullPack] true, obtained through `downloadTrackPackUrl` after a
     * purchase) marks the pack as full; the free sample keeps an existing
     * full download's status.
     */
    suspend fun importParsedPack(parsed: ParsedPack, fullPack: Boolean = false) {
        val existing = dao.trackPack(parsed.pack.documentId)
        val isFreeSample = if (fullPack) false else existing?.isFreeSample ?: true
        dao.insertTrackPack(parsed.pack.copy(isFreeSample = isFreeSample))
        dao.insertItineraries(parsed.itineraries)
        dao.insertSteps(parsed.steps)
        dao.insertTracks(parsed.tracks)
        dao.insertWaypoints(parsed.waypoints)
        dao.insertSidebars(parsed.sidebars)
    }
}
