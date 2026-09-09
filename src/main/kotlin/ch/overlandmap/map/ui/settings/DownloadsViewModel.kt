package ch.overlandmap.map.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkManager
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.data.PackAssetKind
import ch.overlandmap.map.data.PlanetDemState
import ch.overlandmap.map.data.PlanetMapState
import ch.overlandmap.map.data.local.AppDatabase
import ch.overlandmap.map.model.Asset
import ch.overlandmap.map.model.TrackPack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One downloadable thing shown under a pack. */
enum class DownloadKind { ITINERARIES, OFFLINE_MAP, HILLSHADE, DEM, CONTOUR }

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

/**
 * The "general" (non-pack) assets every map needs: the world base map and
 * relief, and the fonts and sprite sheet the styles reference. Fetched at
 * startup, but surfaced here so the user can see their status and retry a
 * failed one.
 */
enum class GeneralAssetKind { WORLD_MAP, WORLD_RELIEF, FONTS, SPRITES }

data class GeneralItem(
    val kind: GeneralAssetKind,
    /** Declared download size in bytes, or 0 when unknown. */
    val sizeBytes: Long,
    val status: DownloadStatus,
)

data class DownloadsState(
    val loading: Boolean = true,
    val general: List<GeneralItem> = emptyList(),
    val packs: List<PackDownloads> = emptyList(),
    val freeSpaceBytes: Long = 0,
    val appStorageBytes: Long = 0,
)

/** The app's private-storage footprint broken down by category, in bytes. */
data class StorageBreakdown(
    val itinerariesBytes: Long,
    val photosBytes: Long,
    val offlineMapsBytes: Long,
    val cacheBytes: Long,
    val freeSpaceBytes: Long,
) {
    val totalBytes: Long get() = itinerariesBytes + photosBytes + offlineMapsBytes + cacheBytes
}

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

    /** Declared sizes of the world map / relief assets, fetched once. */
    private var worldMapSize: Long = 0L
    private var worldReliefSize: Long = 0L

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        // The per-pack (and satellite) sections.
        viewModelScope.launch {
            combine(library.observeTrackPacks(), downloads.progress, refresh) { packs, progress, _ ->
                packs to progress
            }.collect { (packs, progress) -> rebuildPacks(packs, progress) }
        }
        // The general (non-pack) assets: world map, world relief, fonts, sprites.
        viewModelScope.launch {
            // Declared sizes come from Firestore; fetch once (best effort).
            worldMapSize = runCatching { app.planetMapManager.assetSizeBytes() }.getOrNull() ?: 0L
            worldReliefSize = runCatching { app.planetDemManager.assetSizeBytes() }.getOrNull() ?: 0L
        }
        viewModelScope.launch {
            combine(
                app.planetMapManager.state,
                app.planetDemManager.state,
                refresh,
            ) { mapState, demState, _ -> mapState to demState }
                .collect { (mapState, demState) -> rebuildGeneral(mapState, demState) }
        }
    }

    private fun rebuildPacks(packs: List<TrackPack>, progress: Map<String, ch.overlandmap.map.data.PackDownloadProgress>) {
        viewModelScope.launch { rebuildPacksInner(packs, progress) }
    }

    private suspend fun rebuildPacksInner(packs: List<TrackPack>, progress: Map<String, ch.overlandmap.map.data.PackDownloadProgress>) {
        val built = packs.sortedBy { it.name }.map { pack ->
            PackDownloads(pack.documentId, pack.name, itemsFor(pack, progress[pack.documentId]))
        }
        state.value = state.value.copy(
            loading = false,
            packs = built,
            freeSpaceBytes = app.filesDir.usableSpace,
            appStorageBytes = dirSize(app.filesDir) + dirSize(app.cacheDir),
        )
    }

    private fun rebuildGeneral(mapState: PlanetMapState, demState: PlanetDemState) {
        val items = listOf(
            GeneralItem(GeneralAssetKind.WORLD_MAP, worldMapSize, planetStatus(mapState)),
            GeneralItem(GeneralAssetKind.WORLD_RELIEF, worldReliefSize, demStatus(demState)),
            GeneralItem(GeneralAssetKind.FONTS, 0L, fontsStatus()),
            GeneralItem(GeneralAssetKind.SPRITES, 0L, spritesStatus()),
        )
        state.value = state.value.copy(loading = false, general = items)
    }

    private fun planetStatus(s: PlanetMapState): DownloadStatus = when (s) {
        is PlanetMapState.Ready -> DownloadStatus.Downloaded(app.planetMapManager.planetFile.length())
        is PlanetMapState.Downloading -> DownloadStatus.Downloading(s.fraction)
        is PlanetMapState.Failed -> DownloadStatus.Failed(s.message)
        is PlanetMapState.Missing -> DownloadStatus.NotDownloaded
    }

    private fun demStatus(s: PlanetDemState): DownloadStatus = when (s) {
        is PlanetDemState.Ready -> DownloadStatus.Downloaded(app.planetDemManager.demFile.length())
        is PlanetDemState.Downloading -> DownloadStatus.Downloading(s.fraction)
        is PlanetDemState.Failed -> DownloadStatus.Failed(s.message)
        is PlanetDemState.Missing -> DownloadStatus.NotDownloaded
    }

    /** Fonts/sprites have no state flow; derive from disk (they are fetch-once). */
    private fun fontsStatus(): DownloadStatus =
        if (app.styleAssetsManager.fontsReady()) DownloadStatus.Downloaded(0L)
        else DownloadStatus.NotDownloaded

    private fun spritesStatus(): DownloadStatus =
        if (app.styleAssetsManager.spritesReady()) DownloadStatus.Downloaded(0L)
        else DownloadStatus.NotDownloaded

    /** Re-fetch any missing general asset (world map/relief, fonts, sprites). */
    fun retryGeneral(kind: GeneralAssetKind) {
        when (kind) {
            GeneralAssetKind.WORLD_MAP -> app.planetMapManager.ensurePlanet()
            GeneralAssetKind.WORLD_RELIEF -> app.planetDemManager.ensurePlanetDem()
            GeneralAssetKind.FONTS, GeneralAssetKind.SPRITES -> {
                app.styleAssetsManager.retryMissing()
                refresh.value++
            }
        }
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
            mapItem(pack, DownloadKind.DEM, PackAssetKind.DEM, pack.dem, recorded, progress)?.let(::add)
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

    /**
     * The app's private-storage footprint split into the categories the reset
     * sheet shows: the itineraries database, unpacked photos, offline map
     * archives (every `.pmtiles`), and the render cache (the Mapbox tile store
     * plus the app cache dir).
     */
    fun computeStorageBreakdown(onResult: (StorageBreakdown) -> Unit) {
        viewModelScope.launch {
            val breakdown = withContext(Dispatchers.IO) {
                val files = app.filesDir
                val dbBytes = listOf("", "-wal", "-shm").sumOf { suffix ->
                    File(app.getDatabasePath("overlandmap.db").path + suffix)
                        .takeIf { it.isFile }?.length() ?: 0L
                }
                val photos = dirSize(app.packDownloadManager.photoDir)
                val offlineMaps = if (!files.exists()) 0L
                else files.walkTopDown()
                    .filter { it.isFile && it.extension.equals("pmtiles", ignoreCase = true) }
                    .sumOf { it.length() }
                // Mapbox keeps its render cache under files/.mapbox; the app's
                // own cacheDir holds transient download scratch files.
                val cache = dirSize(File(files, ".mapbox")) + dirSize(app.cacheDir)
                StorageBreakdown(
                    itinerariesBytes = dbBytes,
                    photosBytes = photos,
                    offlineMapsBytes = offlineMaps,
                    cacheBytes = cache,
                    freeSpaceBytes = files.usableSpace,
                )
            }
            onResult(breakdown)
        }
    }

    /**
     * Wipes all app-private storage — the database, downloaded maps, photos and
     * caches — then recreates the database and re-fetches the required general
     * assets. Everything under `filesDir` and `cacheDir` is removed (except the
     * database file, deleted through Room so its open handle is closed first).
     *
     * [onComplete] runs on the main thread once the wipe and re-fetch kick-off
     * finish, so the caller can dismiss UI or restart.
     */
    fun resetStorage(onComplete: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // Stop any in-flight downloads so they can't rewrite files mid-wipe.
                runCatching { WorkManager.getInstance(app).cancelAllWork() }
                // Close and delete the Room database (handles its open file lock).
                AppDatabase.deleteDatabase(app)
                // Remove everything else under private storage.
                app.filesDir.listFiles()?.forEach { it.deleteRecursively() }
                app.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            onComplete()
        }
    }

    private fun dirSize(dir: File): Long =
        if (!dir.exists()) 0L else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
