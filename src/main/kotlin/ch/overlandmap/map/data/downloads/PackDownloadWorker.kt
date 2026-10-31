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
import ch.overlandmap.map.data.PackAssetKind
import ch.overlandmap.map.map.LocalTileServer
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
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
        val kinds = inputData.getStringArray(KEY_KINDS) ?: emptyArray()
        val hashes = inputData.getStringArray(KEY_HASHES) ?: emptyArray()
        val zipIndex = inputData.getInt(KEY_ZIP_INDEX, -1)

        runCatching { setForeground(foregroundInfo(packName, -1)) }

        try {
            urls.indices.forEach { index ->
                    val target = File(destinations[index])
                    downloadAsset(urls[index], target, hashes.hashAt(index), packName,
                        assetKind(kinds.getOrNull(index), target, index == zipIndex), index + 1, urls.size)
                    if (index == zipIndex) {
                        val assetVersion = inputData.getInt(KEY_ASSET_VERSION, 0)
                            .takeIf { it > 0 }
                        val assetVersionDate = inputData.getLong(KEY_ASSET_VERSION_DATE, 0L)
                            .takeIf { it > 0L }
                        try {
                            installZip(
                                target,
                                assetVersion = assetVersion,
                                assetVersionDate = assetVersionDate,
                            )
                        } finally {
                            target.delete()
                        }
                    }
            }
            LocalTileServer.reloadArchives()
            Result.success()
        } catch (e: CancellationException) {
            throw e
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
        runCatching { setForeground(foregroundInfo(packName, -1)) }
        setProgress(workDataOf(PROGRESS_FRACTION to -1f, PROGRESS_KIND to PackAssetKind.FREE_ITINERARY.name))
        val app = applicationContext as OverlandApp
        val target = File(applicationContext.cacheDir, "pack_full_$packId.zip")
        return try {
            // Fetch the asset metadata (version/date) from the trackPackZip field.
            val onlinePack = runCatching { app.shopRepository.trackPack(packId) }.getOrNull()
            val asset = onlinePack?.trackPackZip?.let { assetId ->
                runCatching { app.shopRepository.asset(assetId) }.getOrNull()
            }
            val url = app.shopRepository.downloadPackUrl(packId)
            // Verified like every other asset, zip included (iOS checks the archive's SHA-256).
            val extrasCount = inputData.getStringArray(KEY_URLS)?.size ?: 0
            downloadAsset(url, target, asset?.hash?.takeIf { it.isNotBlank() }, packName,
                PackAssetKind.FREE_ITINERARY, 1, extrasCount + 1)
            try {
                installZip(
                    target,
                    fullPack = true,
                    assetVersion = asset?.version,
                    assetVersionDate = asset?.versionDate,
                )
            } finally {
                target.delete()
            }
            downloadExtraAssets(packName)
            LocalTileServer.reloadArchives()
            Result.success()
        } catch (e: CancellationException) {
            throw e
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
        val kinds = inputData.getStringArray(KEY_KINDS) ?: emptyArray()
        val hashes = inputData.getStringArray(KEY_HASHES) ?: emptyArray()
        urls.indices.forEach { index ->
            val target = File(destinations[index])
            downloadAsset(urls[index], target, hashes.hashAt(index), packName,
                assetKind(kinds.getOrNull(index), target, false), index + 2, urls.size + 1)
        }
    }

    /** Reports the current file's actual transfer progress, including a resumed prefix. */
    private suspend fun downloadAsset(
        url: String, target: File, hash: String?, packName: String, kind: PackAssetKind?, index: Int, count: Int,
    ) = coroutineScope {
        val transferred = AtomicLong(0)
        val length = AtomicLong(-1)
        suspend fun report() {
            val total = length.get()
            val fraction = if (total > 0) (transferred.get().toDouble() / total).toFloat().coerceIn(0f, 1f) else -1f
            setProgress(workDataOf(PROGRESS_FRACTION to fraction, PROGRESS_KIND to (kind?.name ?: ""),
                PROGRESS_ASSET_INDEX to index, PROGRESS_ASSET_COUNT to count))
            runCatching { setForeground(foregroundInfo(packName, if (fraction < 0) -1 else (fraction * 100).toInt())) }
        }
        report()
        val reporter = launch {
            while (true) { delay(500); report() }
        }
        try {
            Downloads.download(url, target, hash, onLength = { length.set(it ?: -1) }) { transferred.set(it) }
        } finally {
            reporter.cancelAndJoin()
        }
        report()
    }

    /** Unzips the pack: `images/` into `files/photo`, `json/db.json` into Room. */
    private suspend fun installZip(
        zip: File,
        fullPack: Boolean = false,
        assetVersion: Int? = null,
        assetVersionDate: Long? = null,
    ) {
        setProgress(workDataOf(PROGRESS_FRACTION to -1f, PROGRESS_INSTALLING to true))
        runCatching { setForeground(foregroundInfo(inputData.getString(KEY_PACK_NAME).orEmpty(), -1)) }
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
            app.libraryRepository.importParsedPack(parsed, fullPack, assetVersion, assetVersionDate)
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
                .setProgress(100, percent.coerceIn(0, 100), percent < 0)
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
        const val KEY_KINDS = "kinds"
        const val KEY_SIZES = "sizes"
        /** Expected SHA-256 per file ("" = the backend recorded none). */
        const val KEY_HASHES = "hashes"
        const val KEY_ZIP_INDEX = "zipIndex"
        const val KEY_ASSET_VERSION = "assetVersion"
        const val KEY_ASSET_VERSION_DATE = "assetVersionDate"
        const val KEY_ERROR = "error"
        const val PROGRESS_FRACTION = "fraction"
        const val PROGRESS_MESSAGE = "message"
        const val PROGRESS_KIND = "kind"
        const val PROGRESS_INSTALLING = "installing"
        const val PROGRESS_ASSET_INDEX = "assetIndex"
        const val PROGRESS_ASSET_COUNT = "assetCount"

        /** Tag on every pack download; the pack ID rides in `PACK_TAG_PREFIX`. */
        const val TAG = "pack-download"
        const val PACK_TAG_PREFIX = "pack:"

        private const val MAX_ATTEMPTS = 3
    }
}

/** The expected hash for file [index], or null when none was recorded. */
private fun Array<String>.hashAt(index: Int): String? = getOrNull(index)?.takeIf { it.isNotBlank() }

/** Older queued work has no kinds, so infer them from its destination. */
internal fun assetKind(name: String?, target: File, isZip: Boolean): PackAssetKind? {
    PackAssetKind.entries.firstOrNull { it.name == name }?.let { return it }
    if (isZip) return PackAssetKind.FREE_ITINERARY
    return when (target.parentFile?.name) {
        "detail" -> PackAssetKind.OFFLINE_MAP
        "hillshade" -> PackAssetKind.HILLSHADE
        "dem" -> PackAssetKind.DEM
        "contour" -> PackAssetKind.CONTOUR
        else -> null
    }
}
