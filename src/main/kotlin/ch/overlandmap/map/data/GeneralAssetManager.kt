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
import ch.overlandmap.map.model.Asset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/** Availability of one general (not pack-bound) asset. */
sealed interface GeneralAssetState {
    object Missing : GeneralAssetState
    data class Downloading(val fraction: Float?) : GeneralAssetState
    object Ready : GeneralAssetState
    data class Failed(val message: String) : GeneralAssetState
}

/** Version of an installed general asset, shown as "v.N (date)". */
data class InstalledAssetVersion(val version: Int, val dateMillis: Long?)

/**
 * Keeps one general asset (a Firestore `asset` document: the Overlanding country borders, the
 * country dump…) on the device: when the file is missing or the asset's version is newer than the
 * recorded one, a background download is enqueued; [onInstalled] (e.g. a database import) runs
 * once the file is in place, and only then is the version recorded. Offline starts keep whatever is
 * present. Modelled on [PlanetMapManager].
 */
class GeneralAssetManager(
    context: Context,
    private val scope: CoroutineScope,
    private val shop: ShopRepository,
    val assetId: String,
    val destination: File,
    private val title: String,
    private val reloadTiles: Boolean,
    private val onInstalled: (suspend (File) -> Unit)? = null,
) {
    private val workManager = WorkManager.getInstance(context)
    private val workName = "asset-$assetId"
    private val versionFile = File(destination.path + ".version")

    private val _state = MutableStateFlow<GeneralAssetState>(
        if (destination.isFile && versionFile.isFile) GeneralAssetState.Ready else GeneralAssetState.Missing
    )
    val state: StateFlow<GeneralAssetState> = _state

    private val _installed = MutableStateFlow(readInstalled())
    val installed: StateFlow<InstalledAssetVersion?> = _installed

    @Volatile private var started = false
    @Volatile private var pending: Asset? = null

    /** Checks the asset online and downloads it when needed. Safe to call repeatedly. */
    fun ensure() {
        if (!started) {
            started = true
            scope.launch { watchWork() }
        }
        scope.launch {
            val asset = try {
                shop.asset(assetId)
            } catch (e: Exception) {
                Log.i(TAG, "Asset $assetId not reachable (offline?): ${e.message}")
                null
            }
            val url = asset?.url
            if (url == null) {
                Log.w(TAG, "No asset $assetId or URL; keeping local state")
                return@launch
            }
            val local = readInstalled()
            if (destination.isFile && local != null && local.version >= asset.version) {
                _state.value = GeneralAssetState.Ready
                return@launch
            }
            pending = asset
            destination.parentFile?.mkdirs()
            val request = OneTimeWorkRequestBuilder<AssetDownloadWorker>()
                .setInputData(
                    workDataOf(
                        AssetDownloadWorker.KEY_URL to url,
                        AssetDownloadWorker.KEY_DEST to destination.path,
                        AssetDownloadWorker.KEY_TITLE to title,
                        AssetDownloadWorker.KEY_SIZE_BYTES to asset.fileSizeBytes,
                        AssetDownloadWorker.KEY_SHA256 to asset.hash,
                        AssetDownloadWorker.KEY_RELOAD_TILES to reloadTiles,
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            workManager.enqueueUniqueWork(workName, ExistingWorkPolicy.KEEP, request)
        }
    }

    private suspend fun watchWork() {
        workManager.getWorkInfosForUniqueWorkFlow(workName).collect { infos ->
            val info = infos.firstOrNull() ?: return@collect
            val usable = destination.isFile && versionFile.isFile
            when (info.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                    if (!usable) _state.value = GeneralAssetState.Downloading(null)
                WorkInfo.State.RUNNING -> {
                    val fraction = info.progress.getFloat(AssetDownloadWorker.PROGRESS_FRACTION, -1f)
                    _state.value = GeneralAssetState.Downloading(fraction.takeIf { it >= 0f })
                }
                WorkInfo.State.SUCCEEDED -> finishInstall()
                WorkInfo.State.FAILED ->
                    _state.value = if (usable) GeneralAssetState.Ready else GeneralAssetState.Failed(
                        info.outputData.getString(AssetDownloadWorker.KEY_ERROR) ?: "Download failed"
                    )
                WorkInfo.State.CANCELLED ->
                    _state.value = if (usable) GeneralAssetState.Ready else GeneralAssetState.Missing
            }
        }
    }

    private suspend fun finishInstall() {
        val asset = pending ?: return // a stale SUCCEEDED from an earlier run: already handled
        try {
            onInstalled?.invoke(destination)
            versionFile.writeText("${asset.version}\n${asset.versionDate ?: ""}")
            pending = null
            _installed.value = readInstalled()
            _state.value = GeneralAssetState.Ready
        } catch (e: Exception) {
            Log.w(TAG, "Installing $assetId failed", e)
            _state.value = GeneralAssetState.Failed(e.localizedMessage ?: "Install failed")
        }
    }

    private fun readInstalled(): InstalledAssetVersion? {
        if (!versionFile.isFile) return null
        val lines = versionFile.readText().lines()
        val version = lines.firstOrNull()?.trim()?.toIntOrNull() ?: return null
        return InstalledAssetVersion(version, lines.getOrNull(1)?.trim()?.toLongOrNull())
    }

    private companion object {
        const val TAG = "GeneralAssetManager"
    }
}
