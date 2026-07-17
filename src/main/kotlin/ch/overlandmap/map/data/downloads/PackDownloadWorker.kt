package ch.overlandmap.map.data.downloads

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.data.PackJsonImporter
import ch.overlandmap.map.map.LocalTileServer
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloads the selected files of one track pack and installs the pack zips
 * (photos to `files/photo`, `json/db.json` into Room). Two modes: the asset
 * selection of the free sample dialog, or — [KEY_FULL_PACK_ID] set — the full
 * zip of a purchased pack, whose temporary URL comes from the
 * `downloadTrackPackUrl` cloud function. Runs under WorkManager so the
 * download continues when the app is suspended or killed; progress is
 * exposed as work progress for the pack detail overlay.
 */
class PackDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val packName = inputData.getString(KEY_PACK_NAME) ?: ""
        inputData.getString(KEY_FULL_PACK_ID)?.let { packId ->
            return@withContext downloadFullPack(packId, packName)
        }
        val urls = inputData.getStringArray(KEY_URLS) ?: return@withContext Result.failure()
        val destinations = inputData.getStringArray(KEY_DESTS) ?: return@withContext Result.failure()
        val names = inputData.getStringArray(KEY_NAMES) ?: return@withContext Result.failure()
        val sizes = inputData.getLongArray(KEY_SIZES) ?: return@withContext Result.failure()
        val zipIndex = inputData.getInt(KEY_ZIP_INDEX, -1)

        runCatching { setForeground(foregroundInfo(packName, 0)) }

        try {
            val totalBytes = sizes.sum().coerceAtLeast(1)
            // The download callback is not suspendable: it feeds counters
            // that a side coroutine turns into progress and notifications.
            val transferredTotal = AtomicLong(0)
            val currentName = AtomicReference("")
            val reporter = launch {
                while (true) {
                    delay(1_000)
                    val fraction = transferredTotal.get().toFloat() / totalBytes
                    setProgress(
                        workDataOf(
                            PROGRESS_FRACTION to fraction,
                            PROGRESS_MESSAGE to currentName.get(),
                        )
                    )
                    runCatching {
                        setForeground(foregroundInfo(packName, (fraction * 100).toInt()))
                    }
                }
            }
            try {
                var doneBytes = 0L
                urls.indices.forEach { index ->
                    val target = File(destinations[index])
                    currentName.set(names[index])
                    val base = doneBytes
                    Downloads.download(urls[index], target) { transferred ->
                        transferredTotal.set(base + transferred)
                    }
                    doneBytes += sizes[index]
                    transferredTotal.set(doneBytes)
                    if (index == zipIndex) {
                        reporter.cancel() // installing shows as indeterminate
                        try {
                            installZip(target)
                        } finally {
                            target.delete()
                        }
                    }
                }
            } finally {
                reporter.cancel()
            }
            LocalTileServer.reloadArchives()
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (e.localizedMessage ?: "Download failed")))
            }
        }
    }

    /**
     * The whole purchased pack: asks `downloadTrackPackUrl` for the zip's
     * temporary URL, downloads and installs it. A backend refusal (not
     * purchased, …) fails immediately with its message; transient errors
     * retry, re-resolving the URL since it may have expired meanwhile.
     */
    private suspend fun downloadFullPack(packId: String, packName: String): Result {
        runCatching { setForeground(foregroundInfo(packName, 0)) }
        setProgress(workDataOf(PROGRESS_FRACTION to -1f, PROGRESS_MESSAGE to packName))
        val app = applicationContext as OverlandApp
        val target = File(applicationContext.cacheDir, "pack_full_$packId.zip")
        return try {
            val url = app.shopRepository.downloadPackUrl(packId)
            Downloads.download(url, target)
            try {
                installZip(target, fullPack = true)
            } finally {
                target.delete()
            }
            downloadExtraAssets(packName)
            LocalTileServer.reloadArchives()
            Result.success()
        } catch (e: DownloadRefusedException) {
            Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Download refused")))
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (e.localizedMessage ?: "Download failed")))
            }
        }
    }

    /**
     * The optional map files (offline/hillshade/contour) chosen alongside a
     * full pack. Their url/dest/name/size arrays ride in the same input as the
     * full-pack id; absent or empty means nothing extra to fetch.
     */
    private suspend fun downloadExtraAssets(packName: String) {
        val urls = inputData.getStringArray(KEY_URLS) ?: return
        if (urls.isEmpty()) return
        val destinations = inputData.getStringArray(KEY_DESTS) ?: return
        val names = inputData.getStringArray(KEY_NAMES) ?: emptyArray()
        val sizes = inputData.getLongArray(KEY_SIZES) ?: LongArray(urls.size)
        val totalBytes = sizes.sum().coerceAtLeast(1)
        var doneBytes = 0L
        urls.indices.forEach { index ->
            val name = names.getOrElse(index) { "" }
            setProgress(
                workDataOf(
                    PROGRESS_FRACTION to doneBytes.toFloat() / totalBytes,
                    PROGRESS_MESSAGE to name,
                )
            )
            runCatching {
                setForeground(foregroundInfo(packName, (doneBytes * 100 / totalBytes).toInt()))
            }
            Downloads.download(urls[index], File(destinations[index]))
            doneBytes += sizes.getOrElse(index) { 0L }
        }
    }

    /** Unzips the pack: `images/` into `files/photo`, `json/db.json` into Room. */
    private suspend fun installZip(zip: File, fullPack: Boolean = false) {
        setProgress(workDataOf(PROGRESS_FRACTION to -1f, PROGRESS_MESSAGE to ""))
        val app = applicationContext as OverlandApp
        val photoDir = File(applicationContext.filesDir, "photo")
        val unzipDir = File(applicationContext.cacheDir, "unzip_${id}")
        try {
            Downloads.unzip(zip, unzipDir)
            val images = File(unzipDir, "images")
            if (images.isDirectory) {
                photoDir.mkdirs()
                images.copyRecursively(photoDir, overwrite = true)
            }
            val dbJson = File(unzipDir, "json/db.json")
            if (!dbJson.isFile) throw IOException("Zip contains no json/db.json")
            val parsed = withContext(Dispatchers.Default) { PackJsonImporter.parse(dbJson, photoDir) }
            app.libraryRepository.importParsedPack(parsed, fullPack)
        } finally {
            unzipDir.deleteRecursively()
        }
    }

    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        AssetDownloadWorker.ensureChannel(applicationContext)
        val notification: Notification =
            NotificationCompat.Builder(applicationContext, AssetDownloadWorker.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(id.hashCode(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(id.hashCode(), notification)
        }
    }

    companion object {
        const val KEY_PACK_NAME = "packName"
        /** Set to a pack's document ID to download the whole purchased pack. */
        const val KEY_FULL_PACK_ID = "fullPackId"
        const val KEY_URLS = "urls"
        const val KEY_DESTS = "dests"
        const val KEY_NAMES = "names"
        const val KEY_SIZES = "sizes"
        const val KEY_ZIP_INDEX = "zipIndex"
        const val KEY_ERROR = "error"
        const val PROGRESS_FRACTION = "fraction"
        const val PROGRESS_MESSAGE = "message"

        /** Tag on every pack download; the pack ID rides in `PACK_TAG_PREFIX`. */
        const val TAG = "pack-download"
        const val PACK_TAG_PREFIX = "pack:"

        private const val MAX_ATTEMPTS = 3
    }
}
