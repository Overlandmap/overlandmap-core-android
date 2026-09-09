package ch.overlandmap.map.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import ch.overlandmap.map.data.downloads.AssetDownloadWorker
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Availability of the global relief (Terrain-RGB DEM) used by the maps. */
sealed interface PlanetDemState {
    object Missing : PlanetDemState
    data class Downloading(val fraction: Float?) : PlanetDemState
    object Ready : PlanetDemState
    /** The last download attempt failed (e.g. checksum mismatch, network). */
    data class Failed(val message: String) : PlanetDemState
}

/**
 * Keeps the global low-zoom relief map
 * (`files/osm_pmtiles/planet-dem.pmtiles`) on the device — the Terrain-RGB DEM
 * the styles' `demSource`/`demColorSource` read (via `/tiles/dem`) to draw
 * hillshade and the elevation-colour tint at zoom 0–6. Per-pack DEM archives
 * (in `files/dem/`) cover the higher zooms where they exist.
 *
 * Mirrors [PlanetMapManager]: on every start it checks the Firestore asset
 * `/asset/planet-dem` and, when the file is missing or the asset's version is
 * newer than the downloaded one, enqueues a background download (WorkManager,
 * so it survives the app being suspended). Offline starts keep whatever file is
 * present.
 */
class PlanetDemManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val shop: ShopRepository,
) {

    private val workManager = WorkManager.getInstance(context)

    val demFile: File get() = File(context.filesDir, "osm_pmtiles/planet-dem.pmtiles")

    /** Version of the downloaded file, recorded next to it. */
    private val versionFile: File get() = File(demFile.path + ".version")

    private val _state = MutableStateFlow<PlanetDemState>(
        if (demFile.isFile) PlanetDemState.Ready else PlanetDemState.Missing
    )
    val state: StateFlow<PlanetDemState> = _state

    /** Declared size of the DEM asset, for onboarding/downloads (null offline). */
    suspend fun assetSizeBytes(): Long? = try {
        shop.asset(ASSET_ID)?.fileSizeBytes
    } catch (e: Exception) {
        Log.i(TAG, "Planet DEM asset size not reachable (offline?): ${e.message}")
        null
    }

    /** Call once at startup (and at will after connectivity returns). */
    fun ensurePlanetDem() {
        scope.launch { watchWork() }
        scope.launch {
            val asset = try {
                shop.asset(ASSET_ID)
            } catch (e: Exception) {
                Log.i(TAG, "Planet DEM asset not reachable (offline?): ${e.message}")
                null
            }
            val url = asset?.url
            if (url == null) {
                Log.w(TAG, "No planet-dem asset or URL; keeping local state")
                return@launch
            }
            if (demFile.isFile && localVersion() >= asset.version) {
                _state.value = PlanetDemState.Ready
                return@launch
            }
            val request = OneTimeWorkRequestBuilder<AssetDownloadWorker>()
                .setInputData(
                    workDataOf(
                        AssetDownloadWorker.KEY_URL to url,
                        AssetDownloadWorker.KEY_DEST to demFile.path,
                        AssetDownloadWorker.KEY_TITLE to "World relief",
                        AssetDownloadWorker.KEY_SIZE_BYTES to asset.fileSizeBytes,
                        AssetDownloadWorker.KEY_SHA256 to asset.hash,
                        AssetDownloadWorker.KEY_RELOAD_TILES to true,
                    )
                )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
            // Record what the finished download will contain; committed to the
            // version file only on success (see watchWork).
            pendingVersion = asset.version
        }
    }

    private var pendingVersion: Int? = null

    private suspend fun watchWork() {
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).collect { infos ->
            val info = infos.firstOrNull() ?: return@collect
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                    if (!demFile.isFile) _state.value = PlanetDemState.Downloading(null)
                WorkInfo.State.RUNNING -> {
                    val fraction = info.progress.getFloat(AssetDownloadWorker.PROGRESS_FRACTION, -1f)
                    _state.value = PlanetDemState.Downloading(fraction.takeIf { it >= 0f })
                }
                WorkInfo.State.SUCCEEDED -> {
                    pendingVersion?.let { versionFile.writeText(it.toString()) }
                    _state.value = PlanetDemState.Ready
                }
                WorkInfo.State.FAILED ->
                    _state.value = when {
                        demFile.isFile -> PlanetDemState.Ready
                        else -> PlanetDemState.Failed(
                            info.outputData.getString(AssetDownloadWorker.KEY_ERROR)
                                ?: "Download failed"
                        )
                    }
                WorkInfo.State.CANCELLED ->
                    _state.value =
                        if (demFile.isFile) PlanetDemState.Ready else PlanetDemState.Missing
            }
        }
    }

    private fun localVersion(): Int =
        versionFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0

    private companion object {
        const val TAG = "PlanetDemManager"
        const val ASSET_ID = "planet-dem"
        const val WORK_NAME = "asset-planet-dem"
    }
}
