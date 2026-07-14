package ch.overlandmap.map.data.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import ch.overlandmap.map.R
import ch.overlandmap.map.map.LocalTileServer
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloads one asset file in the background, surviving the app being
 * suspended or killed: WorkManager re-runs it, and the `.part` resume in
 * [Downloads] continues the transfer. Optionally verifies a sha-256, unzips
 * archives into a directory, and reloads the tile server's archives.
 */
class AssetDownloadWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val destination = File(inputData.getString(KEY_DEST) ?: return@withContext Result.failure())
        val title = inputData.getString(KEY_TITLE) ?: destination.name
        val sizeBytes = inputData.getLong(KEY_SIZE_BYTES, 0L)
        val sha256 = inputData.getString(KEY_SHA256)
        val unzipTo = inputData.getString(KEY_UNZIP_TO)

        // Long downloads run as a foreground service where allowed; when the
        // OS refuses (app deep in background) the worker still runs normally.
        runCatching { setForeground(foregroundInfo(title, 0)) }

        try {
            // The download callback is not suspendable: it feeds a counter
            // that a side coroutine turns into progress and notifications.
            val transferredBytes = AtomicLong(-1)
            val reporter = launch {
                while (true) {
                    delay(1_000)
                    val transferred = transferredBytes.get()
                    if (transferred < 0) continue
                    val fraction = if (sizeBytes > 0) {
                        (transferred.toFloat() / sizeBytes).coerceAtMost(1f)
                    } else {
                        -1f
                    }
                    setProgress(workDataOf(PROGRESS_FRACTION to fraction))
                    runCatching {
                        setForeground(foregroundInfo(title, (fraction * 100).toInt()))
                    }
                }
            }
            try {
                Downloads.download(url, destination, sha256) { transferredBytes.set(it) }
            } finally {
                reporter.cancel()
            }
            if (unzipTo != null) {
                try {
                    Downloads.unzip(destination, File(unzipTo))
                } finally {
                    destination.delete()
                }
            }
            if (inputData.getBoolean(KEY_RELOAD_TILES, false)) {
                LocalTileServer.reloadArchives()
            }
            Result.success()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                Result.retry()
            } else {
                Result.failure(workDataOf(KEY_ERROR to (e.localizedMessage ?: "Download failed")))
            }
        }
    }

    private fun foregroundInfo(title: String, percent: Int): ForegroundInfo {
        ensureChannel(applicationContext)
        val notification: Notification =
            NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent.coerceIn(0, 100), percent <= 0)
                .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId(), notification)
        }
    }

    private fun notificationId(): Int = id.hashCode()

    companion object {
        const val KEY_URL = "url"
        const val KEY_DEST = "dest"
        const val KEY_TITLE = "title"
        const val KEY_SIZE_BYTES = "sizeBytes"
        const val KEY_SHA256 = "sha256"
        const val KEY_UNZIP_TO = "unzipTo"
        const val KEY_RELOAD_TILES = "reloadTiles"
        const val KEY_ERROR = "error"
        const val PROGRESS_FRACTION = "fraction"

        const val CHANNEL_ID = "downloads"
        private const val MAX_ATTEMPTS = 3

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.download_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
    }
}
