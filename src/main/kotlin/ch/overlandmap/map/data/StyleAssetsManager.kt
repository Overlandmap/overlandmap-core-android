package ch.overlandmap.map.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import ch.overlandmap.map.data.downloads.AssetDownloadWorker
import java.io.File

/**
 * Keeps the offline style's static assets under `files/assets`, where
 * [ch.overlandmap.map.map.LocalTileServer] serves them:
 *
 *  - `styles/detailed.json` — copied from the APK's bundled assets
 *  - `glyphs/` and `sprites/` — the zips the Flutter app also uses, fetched
 *    once in the background from the overlanding.io asset store
 *
 * All pieces must be present before [ch.overlandmap.map.map.MapStyles] offers
 * the offline style: a missing glyph range would blank symbol sources.
 */
class StyleAssetsManager(private val context: Context) {

    private val assetsDir = File(context.filesDir, "assets")

    /** Call once at startup. Cheap when everything is already in place. */
    fun ensure() {
        copyBundledStyles()
        val glyphsDir = File(assetsDir, "glyphs")
        GLYPH_ZIPS.forEach { name ->
            // Each zip unpacks its font families as directories.
            enqueueZip(name, glyphsDir)
        }
        enqueueZip(SPRITE_ZIP, File(assetsDir, "sprites"))
    }

    /** True when the style, fonts and sprites are all available locally. */
    fun ready(): Boolean =
        File(assetsDir, STYLE_PATH).isFile &&
            REQUIRED_FONTS.all { File(assetsDir, "glyphs/$it").isDirectory } &&
            File(assetsDir, "sprites/sprite.json").isFile

    /** (Re)copies the bundled style files whenever they differ from the APK's. */
    private fun copyBundledStyles() {
        try {
            val styles = context.assets.list("styles") ?: return
            val targetDir = File(assetsDir, "styles").apply { mkdirs() }
            styles.forEach { name ->
                val bundled = context.assets.open("styles/$name").use { it.readBytes() }
                val target = File(targetDir, name)
                if (!target.isFile || !bundled.contentEquals(target.readBytes())) {
                    target.writeBytes(bundled)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot copy bundled styles", e)
        }
    }

    private fun enqueueZip(name: String, targetDir: File) {
        if (targetDir.isDirectory && markerOf(name, targetDir).isFile) return
        val request = OneTimeWorkRequestBuilder<AssetDownloadWorker>()
            .setInputData(
                workDataOf(
                    AssetDownloadWorker.KEY_URL to "$ASSET_STORE/$name.zip",
                    AssetDownloadWorker.KEY_DEST to File(context.cacheDir, "$name.zip").path,
                    AssetDownloadWorker.KEY_TITLE to name,
                    AssetDownloadWorker.KEY_UNZIP_TO to targetDir.path,
                )
            )
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("style-asset-$name", ExistingWorkPolicy.KEEP, request)
        // The zip's own content marks completion: write the marker after the
        // fact is unnecessary — presence of unzipped content is checked below.
    }

    /** A file whose presence proves the zip was unpacked. */
    private fun markerOf(name: String, targetDir: File): File = when (name) {
        SPRITE_ZIP -> File(targetDir, "sprite.json")
        "roboto" -> File(targetDir, "Roboto Regular/0-255.pbf")
        "noto_sans" -> File(targetDir, "Noto Sans Regular/0-255.pbf")
        "noto-sans-arabic-regular" -> File(targetDir, "Noto Sans Arabic Regular/0-255.pbf")
        else -> File(targetDir, name)
    }

    private companion object {
        const val TAG = "StyleAssetsManager"
        const val ASSET_STORE = "https://overlanding.io/assets"
        const val STYLE_PATH = "styles/detailed.json"
        const val SPRITE_ZIP = "sprite"
        val GLYPH_ZIPS = listOf("roboto", "noto_sans", "noto-sans-arabic-regular")
        val REQUIRED_FONTS = listOf(
            "Roboto Regular",
            "Roboto Medium",
            "Roboto Condensed Italic",
            "Noto Sans Regular",
            "Noto Sans Arabic Regular",
        )
    }
}
