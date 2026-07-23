package ch.overlandmap.map.data

import ch.overlandmap.map.data.local.FtsDoc
import ch.overlandmap.map.data.local.FtsIndex
import ch.overlandmap.map.data.local.LibraryDao
import ch.overlandmap.map.model.Asset
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.PackAsset
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
    private val fts: FtsIndex,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    /** Step + waypoint lookups by document ID, to open a search hit. */
    suspend fun stepByDocumentId(documentId: String) = dao.stepByDocumentId(documentId)

    suspend fun waypointByDocumentId(documentId: String) = dao.waypointByDocumentId(documentId)

    fun observeTrackPacks() = dao.observeTrackPacks()

    fun observeItineraries(trackPackId: String) = dao.observeItineraries(trackPackId)

    /** The most recently opened local itineraries, newest first. */
    fun observeLastOpened(limit: Int = 5) = dao.observeLastOpened(limit)

    /** Records that the user opened this itinerary (drives "Last used"). */
    suspend fun markOpened(itineraryId: String) =
        dao.touchItinerary(itineraryId, System.currentTimeMillis())

    suspend fun itinerariesOf(trackPackId: String) = dao.itinerariesOf(trackPackId)

    suspend fun trackPack(id: String) = dao.trackPack(id)

    /** All locally downloaded track pack IDs. */
    suspend fun allTrackPackIds(): List<String> = dao.allTrackPackIds()

    /** The pack's asset catalogue (itinerary zip, offline map, hillshade, contour). */
    suspend fun packAssets(trackPackId: String) = dao.packAssets(trackPackId)

    /**
     * Records the pack's assets so the Downloads screen can list every one —
     * downloaded or not. Called when a download is started with the full set of
     * fetched assets, regardless of which were selected.
     */
    suspend fun savePackAssets(trackPackId: String, assets: Map<PackAssetKind, Asset>) {
        dao.insertPackAssets(
            assets.map { (kind, asset) ->
                PackAsset(
                    trackPackId = trackPackId,
                    kind = kind.name,
                    assetId = asset.documentId,
                    name = asset.name,
                    fileSizeBytes = asset.fileSizeBytes,
                )
            }
        )
    }

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

    suspend fun sidebarById(documentId: String) = dao.sidebarById(documentId)

    /**
     * Removes the pack from the device: its rows (pack, itineraries, steps,
     * tracks, waypoints, sidebars) and the photos its zips had unpacked.
     */
    suspend fun deletePack(trackPackId: String) {
        val itineraries = dao.itinerariesOf(trackPackId)
        val steps = dao.stepsOfPack(trackPackId)
        val sidebars = dao.sidebars(trackPackId)
        val waypointIds = dao.waypointIdsOfPack(trackPackId)
        val photos = buildList {
            dao.trackPack(trackPackId)?.localPhotoPath?.let(::add)
            itineraries.forEach { itinerary ->
                itinerary.localPhotoPath?.let(::add)
                itinerary.localOtherPhotoPaths?.let(::addAll)
            }
            steps.forEach { it.localPhotoPath?.let(::add) }
            sidebars.forEach { it.localPhotoPath?.let(::add) }
        }
        // Every object's search-index entry goes with it.
        val indexedIds = buildList {
            add(trackPackId)
            itineraries.forEach { add(it.documentId) }
            steps.forEach { add(it.documentId) }
            sidebars.forEach { add(it.documentId) }
            addAll(waypointIds)
        }
        dao.deletePackContent(trackPackId)
        fts.deleteDocuments(indexedIds)
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
        indexPack(parsed)
    }

    /** Adds every text-bearing object of a freshly imported pack to the search index. */
    private suspend fun indexPack(parsed: ParsedPack) {
        val docs = buildList {
            val pack = parsed.pack
            add(FtsDoc(FtsIndex.TYPE_TRACK_PACK, pack.documentId, pack::name) { pack.description(it).orEmpty() })
            parsed.itineraries.forEach { i ->
                add(FtsDoc(FtsIndex.TYPE_ITINERARY, i.documentId, i::name, i::indexableDescription))
            }
            parsed.steps.forEach { s ->
                add(FtsDoc(FtsIndex.TYPE_STEP, s.documentId, s::name) { s.description(it).orEmpty() })
            }
            parsed.waypoints.forEach { w ->
                add(FtsDoc(FtsIndex.TYPE_WAYPOINT, w.documentId, w::name) { w.description(it).orEmpty() })
            }
            parsed.sidebars.forEach { sb ->
                add(FtsDoc(FtsIndex.TYPE_SIDEBAR, sb.documentId, sb::name) { sb.description(it).orEmpty() })
            }
        }
        fts.index(docs)
    }
}
