package ch.overlandmap.map.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import ch.overlandmap.map.data.downloads.PackDownloadWorker
import ch.overlandmap.map.model.Asset
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What one pack download can contain, mirroring the Flutter asset types. */
enum class PackAssetKind { FREE_ITINERARY, OFFLINE_MAP, HILLSHADE, CONTOUR }

/** Progress of one pack's download, keyed by pack ID in [PackDownloadManager.progress]. */
data class PackDownloadProgress(
    /** Overall 0..1 fraction across all files; null while installing (indeterminate). */
    val fraction: Float? = 0f,
    /** Name of the item currently transferring or the current phase. */
    val message: String = "",
    val error: String? = null,
    val done: Boolean = false,
)

/**
 * Downloads pack assets through WorkManager, so a download keeps running when
 * the app is suspended or killed. This manager only enqueues the work and
 * mirrors its progress into [progress] for the pack detail overlay; the
 * transfer and installation live in [PackDownloadWorker]. Downloads running
 * from a previous app session are re-attached on creation.
 */
class PackDownloadManager(
    private val context: Context,
    private val scope: CoroutineScope,
    @Suppress("unused") private val library: LibraryRepository,
) {

    private val workManager = WorkManager.getInstance(context)

    val progress = MutableStateFlow<Map<String, PackDownloadProgress>>(emptyMap())

    /** Persistent, app-private root of the photos unpacked from zips. */
    val photoDir: File get() = File(context.filesDir, "photo")

    init {
        // Watch every pack download, including ones enqueued before this
        // process started.
        scope.launch {
            workManager.getWorkInfosByTagFlow(PackDownloadWorker.TAG).collect { infos ->
                infos.forEach { onWorkInfo(it) }
            }
        }
    }

    fun isDownloading(packId: String): Boolean =
        progress.value[packId]?.let { it.error == null && !it.done } == true

    /** Destination of a map asset; the file exists once it was downloaded. */
    fun mapFile(packId: String, kind: PackAssetKind, asset: Asset): File {
        val (dir, extension) = when (kind) {
            PackAssetKind.OFFLINE_MAP -> "osm_pmtiles/detail" to "pmtiles"
            PackAssetKind.HILLSHADE -> "hillshade" to "mbtiles"
            PackAssetKind.CONTOUR -> "contour" to "mbtiles"
            PackAssetKind.FREE_ITINERARY -> throw IllegalArgumentException("zip has no map file")
        }
        val name = asset.name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(File(context.filesDir, dir), "${packId}_$name.$extension")
    }

    /**
     * Enqueues the download of a whole purchased pack: the worker asks the
     * `downloadTrackPackUrl` cloud function for the zip's temporary URL and
     * installs it. No-op when this pack is already downloading.
     */
    fun startFullPack(packId: String, packName: String) {
        if (isDownloading(packId)) return
        setProgress(packId, PackDownloadProgress())
        val request = OneTimeWorkRequestBuilder<PackDownloadWorker>()
            .setInputData(
                workDataOf(
                    PackDownloadWorker.KEY_PACK_NAME to packName,
                    PackDownloadWorker.KEY_FULL_PACK_ID to packId,
                )
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(PackDownloadWorker.TAG)
            .addTag(PackDownloadWorker.PACK_TAG_PREFIX + packId)
            .build()
        workManager.enqueueUniqueWork("pack-$packId", ExistingWorkPolicy.KEEP, request)
    }

    /**
     * Enqueues the download of [selection] for the pack; no-op when this pack
     * is already downloading. Progress is weighted by the assets' sizes.
     */
    fun start(packId: String, selection: Map<PackAssetKind, Asset>) {
        if (selection.isEmpty() || isDownloading(packId)) return

        val entries = selection.entries.toList()
        val urls = mutableListOf<String>()
        val destinations = mutableListOf<String>()
        val names = mutableListOf<String>()
        val sizes = mutableListOf<Long>()
        var zipIndex = -1
        entries.forEachIndexed { index, (kind, asset) ->
            val url = asset.url ?: run {
                setProgress(packId, PackDownloadProgress(error = "No URL for ${asset.name}"))
                return
            }
            urls += url
            names += asset.name
            sizes += asset.fileSizeBytes
            destinations += when (kind) {
                PackAssetKind.FREE_ITINERARY -> {
                    zipIndex = index
                    File(context.cacheDir, "pack_$packId.zip").path
                }
                else -> mapFile(packId, kind, asset).path
            }
        }

        setProgress(packId, PackDownloadProgress())
        val request = OneTimeWorkRequestBuilder<PackDownloadWorker>()
            .setInputData(
                workDataOf(
                    PackDownloadWorker.KEY_PACK_NAME to packId,
                    PackDownloadWorker.KEY_URLS to urls.toTypedArray(),
                    PackDownloadWorker.KEY_DESTS to destinations.toTypedArray(),
                    PackDownloadWorker.KEY_NAMES to names.toTypedArray(),
                    PackDownloadWorker.KEY_SIZES to sizes.toLongArray(),
                    PackDownloadWorker.KEY_ZIP_INDEX to zipIndex,
                )
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag(PackDownloadWorker.TAG)
            .addTag(PackDownloadWorker.PACK_TAG_PREFIX + packId)
            .build()
        workManager.enqueueUniqueWork("pack-$packId", ExistingWorkPolicy.KEEP, request)
    }

    private fun onWorkInfo(info: WorkInfo) {
        val packId = info.tags
            .firstOrNull { it.startsWith(PackDownloadWorker.PACK_TAG_PREFIX) }
            ?.removePrefix(PackDownloadWorker.PACK_TAG_PREFIX)
            ?: return
        when (info.state) {
            WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                setProgress(packId, PackDownloadProgress())
            WorkInfo.State.RUNNING -> {
                val fraction = info.progress.getFloat(PackDownloadWorker.PROGRESS_FRACTION, 0f)
                setProgress(
                    packId,
                    PackDownloadProgress(
                        fraction = fraction.takeIf { it >= 0f },
                        message = info.progress.getString(PackDownloadWorker.PROGRESS_MESSAGE) ?: "",
                    ),
                )
            }
            WorkInfo.State.SUCCEEDED -> finish(
                info, packId, PackDownloadProgress(fraction = 1f, done = true), DONE_VISIBLE_MS,
            )
            WorkInfo.State.FAILED -> finish(
                info, packId,
                PackDownloadProgress(
                    error = info.outputData.getString(PackDownloadWorker.KEY_ERROR)
                        ?: "Download failed",
                ),
                ERROR_VISIBLE_MS,
            )
            WorkInfo.State.CANCELLED -> progress.update { it - packId }
        }
    }

    /** Shows the terminal state briefly, then forgets the finished work. */
    private fun finish(info: WorkInfo, packId: String, state: PackDownloadProgress, visibleMs: Long) {
        if (info.id in handledIds) return
        handledIds += info.id
        setProgress(packId, state)
        scope.launch {
            kotlinx.coroutines.delay(visibleMs)
            progress.update { it - packId }
            // Drop the finished record so it is not replayed next session.
            workManager.pruneWork()
        }
    }

    private val handledIds = mutableSetOf<java.util.UUID>()

    private fun setProgress(packId: String, value: PackDownloadProgress) {
        progress.update { it + (packId to value) }
    }

    private companion object {
        const val DONE_VISIBLE_MS = 3_000L
        const val ERROR_VISIBLE_MS = 8_000L
    }
}
