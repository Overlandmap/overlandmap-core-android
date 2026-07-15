package ch.overlandmap.map.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.data.PackAssetKind
import ch.overlandmap.map.model.Asset
import ch.overlandmap.map.model.TrackPack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

/** One downloadable thing shown under a pack. */
enum class DownloadKind { ITINERARIES, OFFLINE_MAP, HILLSHADE, CONTOUR }

sealed interface DownloadStatus {
    /** Present on the device; [bytes] is its actual on-disk size. */
    data class Downloaded(val bytes: Long) : DownloadStatus
    /** Currently transferring; [fraction] is 0..1 or null while installing. */
    data class Downloading(val fraction: Float?) : DownloadStatus
    data class Failed(val message: String) : DownloadStatus
    object NotDownloaded : DownloadStatus
}

data class DownloadItem(
    val kind: DownloadKind,
    /** The map-asset kind for the download manager; null for itineraries. */
    val assetKind: PackAssetKind?,
    /** Its resolved asset document (size/url); null for itineraries or offline. */
    val asset: Asset?,
    /** Declared download size, in bytes (0 when unknown). */
    val sizeBytes: Long,
    val status: DownloadStatus,
)

data class PackDownloads(
    val packId: String,
    val packName: String,
    val items: List<DownloadItem>,
)

data class DownloadsState(
    val loading: Boolean = true,
    val packs: List<PackDownloads> = emptyList(),
    val freeSpaceBytes: Long = 0,
    val appStorageBytes: Long = 0,
)

/**
 * Aggregates the download state of every local pack's assets — itineraries
 * (the full-pack zip), offline map, hillshade and contour — for the Downloads
 * screen, and drives per-asset download / retry / delete.
 */
class DownloadsViewModel(private val app: OverlandApp) : ViewModel() {

    private val library = app.libraryRepository
    private val shop = app.shopRepository
    private val downloads = app.packDownloadManager

    val state = MutableStateFlow(DownloadsState())

    /** Which asset a pack's in-flight download belongs to (for progress). */
    private val inFlight = mutableMapOf<String, DownloadKind>()
    /** Cached asset documents, so progress ticks don't refetch Firestore. */
    private val assetCache = mutableMapOf<String, Asset?>()
    /** Bumped to force a rebuild after a file-only change (map delete). */
    private val refresh = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        viewModelScope.launch {
            combine(library.observeTrackPacks(), downloads.progress, refresh) { packs, progress, _ ->
                packs to progress
            }.collect { (packs, progress) -> rebuild(packs, progress) }
        }
    }

    private suspend fun rebuild(packs: List<TrackPack>, progress: Map<String, ch.overlandmap.map.data.PackDownloadProgress>) {
        val built = packs.sortedBy { it.name }.map { pack ->
            PackDownloads(pack.documentId, pack.name, itemsFor(pack, progress[pack.documentId]))
        }
        state.value = DownloadsState(
            loading = false,
            packs = built,
            freeSpaceBytes = app.filesDir.usableSpace,
            appStorageBytes = dirSize(app.filesDir) + dirSize(app.cacheDir),
        )
    }

    private suspend fun itemsFor(
        pack: TrackPack,
        progress: ch.overlandmap.map.data.PackDownloadProgress?,
    ): List<DownloadItem> {
        // The persisted catalogue (written when a pack is downloaded) is the
        // source of truth; a purchased pack's refs are the fallback.
        val recorded = library.packAssets(pack.documentId).associateBy { it.kind }
        val hasItineraries = library.itinerariesOf(pack.documentId).any { !it.isBuyable }
        val itinSize = recorded[PackAssetKind.FREE_ITINERARY.name]?.fileSizeBytes
            ?: pack.trackPackZip?.let { assetDoc(it)?.fileSizeBytes } ?: 0L
        return buildList {
            add(
                DownloadItem(
                    kind = DownloadKind.ITINERARIES,
                    assetKind = null,
                    asset = null,
                    sizeBytes = itinSize,
                    status = status(pack.documentId, DownloadKind.ITINERARIES, progress, hasItineraries, 0L),
                )
            )
            mapItem(pack, DownloadKind.OFFLINE_MAP, PackAssetKind.OFFLINE_MAP, pack.pmtilesMap, recorded, progress)?.let(::add)
            mapItem(pack, DownloadKind.HILLSHADE, PackAssetKind.HILLSHADE, pack.hillshade, recorded, progress)?.let(::add)
            mapItem(pack, DownloadKind.CONTOUR, PackAssetKind.CONTOUR, pack.contour, recorded, progress)?.let(::add)
        }
    }

    private suspend fun mapItem(
        pack: TrackPack,
        kind: DownloadKind,
        assetKind: PackAssetKind,
        refId: String?,
        recorded: Map<String, ch.overlandmap.map.model.PackAsset>,
        progress: ch.overlandmap.map.data.PackDownloadProgress?,
    ): DownloadItem? {
        val rec = recorded[assetKind.name]
        // Build a minimal Asset from the record (name locates the file); fall
        // back to fetching the pack's ref online for a not-yet-recorded pack.
        val asset = when {
            rec != null -> Asset(
                documentId = rec.assetId,
                name = rec.name,
                fileSizeMb = (rec.fileSizeBytes / 1_000_000L).toInt(),
            )
            refId != null -> assetDoc(refId)
            else -> null
        } ?: return null
        val onDisk = downloads.hasMapAsset(pack.documentId, assetKind, asset)
        val actual = if (onDisk) downloads.mapAssetBytes(pack.documentId, assetKind, asset) else 0L
        val declared = rec?.fileSizeBytes ?: asset.fileSizeBytes
        return DownloadItem(
            kind = kind,
            assetKind = assetKind,
            asset = asset,
            sizeBytes = if (actual > 0) actual else declared,
            status = status(pack.documentId, kind, progress, onDisk, actual),
        )
    }

    private fun status(
        packId: String,
        kind: DownloadKind,
        progress: ch.overlandmap.map.data.PackDownloadProgress?,
        onDisk: Boolean,
        actualBytes: Long,
    ): DownloadStatus {
        val active = progress != null && inFlight[packId] == kind
        return when {
            active && progress!!.error != null -> DownloadStatus.Failed(progress.error!!)
            active && !progress!!.done -> DownloadStatus.Downloading(progress.fraction)
            onDisk -> DownloadStatus.Downloaded(actualBytes)
            else -> DownloadStatus.NotDownloaded
        }
    }

    fun download(pack: PackDownloads, item: DownloadItem) {
        inFlight[pack.packId] = item.kind
        if (item.kind == DownloadKind.ITINERARIES) {
            downloads.startFullPack(pack.packId, pack.packName)
            return
        }
        val assetKind = item.assetKind ?: return
        val assetId = item.asset?.documentId ?: return
        viewModelScope.launch {
            // The recorded asset carries no URL; re-fetch for a fresh one.
            val fresh = runCatching { shop.asset(assetId) }.getOrNull() ?: return@launch
            downloads.start(pack.packId, mapOf(assetKind to fresh))
        }
    }

    fun delete(pack: PackDownloads, item: DownloadItem) {
        viewModelScope.launch {
            if (item.kind == DownloadKind.ITINERARIES) {
                library.deletePack(pack.packId)
            } else if (item.assetKind != null && item.asset != null) {
                downloads.deleteMapAsset(pack.packId, item.assetKind, item.asset)
                refresh.value++
            }
        }
    }

    private suspend fun assetDoc(id: String): Asset? {
        if (assetCache.containsKey(id)) return assetCache[id]
        val a = runCatching { shop.asset(id) }.getOrNull()
        assetCache[id] = a
        return a
    }

    private fun dirSize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
