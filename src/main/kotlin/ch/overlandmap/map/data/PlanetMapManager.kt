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

/** Availability of the world base map used by the track pack maps. */
sealed interface PlanetMapState {
    object Missing : PlanetMapState
    data class Downloading(val fraction: Float?) : PlanetMapState
    object Ready : PlanetMapState
}

/**
 * Keeps the world base map (`files/osm_pmtiles/planet.pmtiles`) on the
 * device. On every start it checks the Firestore asset `/asset/planet-pmtiles`
 * and, when the file is missing or the asset's version is newer than the
 * downloaded one, enqueues a background download (WorkManager, so it survives
 * the app being suspended). Offline starts keep whatever file is present.
 */
class PlanetMapManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val shop: ShopRepository,
) {

    private val workManager = WorkManager.getInstance(context)

    val planetFile: File get() = File(context.filesDir, "osm_pmtiles/planet.pmtiles")

    /** Version of the downloaded file, recorded next to it. */
    private val versionFile: File get() = File(planetFile.path + ".version")

    private val _state = MutableStateFlow<PlanetMapState>(
        if (planetFile.isFile) PlanetMapState.Ready else PlanetMapState.Missing
    )
    val state: StateFlow<PlanetMapState> = _state

    /** Call once at startup (and at will after connectivity returns). */
    fun ensurePlanet() {
        scope.launch { watchWork() }
        scope.launch {
            val asset = try {
                shop.asset(ASSET_ID)
            } catch (e: Exception) {
                Log.i(TAG, "Planet asset not reachable (offline?): ${e.message}")
                null
            }
            val url = asset?.url
            if (url == null) {
                Log.w(TAG, "No planet asset or URL; keeping local state")
                return@launch
            }
            if (planetFile.isFile && localVersion() >= asset.version) {
                _state.value = PlanetMapState.Ready
                return@launch
            }
            val request = OneTimeWorkRequestBuilder<AssetDownloadWorker>()
                .setInputData(
                    workDataOf(
                        AssetDownloadWorker.KEY_URL to url,
                        AssetDownloadWorker.KEY_DEST to planetFile.path,
                        AssetDownloadWorker.KEY_TITLE to "World map",
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
            // Record what the finished download will contain; committed to
            // the version file only on success (see watchWork).
            pendingVersion = asset.version
        }
    }

    private var pendingVersion: Int? = null

    private suspend fun watchWork() {
        workManager.getWorkInfosForUniqueWorkFlow(WORK_NAME).collect { infos ->
            val info = infos.firstOrNull() ?: return@collect
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                    if (!planetFile.isFile) _state.value = PlanetMapState.Downloading(null)
                WorkInfo.State.RUNNING -> {
                    val fraction = info.progress.getFloat(AssetDownloadWorker.PROGRESS_FRACTION, -1f)
                    _state.value = PlanetMapState.Downloading(fraction.takeIf { it >= 0f })
                }
                WorkInfo.State.SUCCEEDED -> {
                    pendingVersion?.let { versionFile.writeText(it.toString()) }
                    _state.value = PlanetMapState.Ready
                }
                WorkInfo.State.FAILED, WorkInfo.State.CANCELLED ->
                    _state.value =
                        if (planetFile.isFile) PlanetMapState.Ready else PlanetMapState.Missing
            }
        }
    }

    private fun localVersion(): Int =
        versionFile.takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull() ?: 0

    private companion object {
        const val TAG = "PlanetMapManager"
        const val ASSET_ID = "planet-pmtiles"
        const val WORK_NAME = "asset-planet-pmtiles"
    }
}
