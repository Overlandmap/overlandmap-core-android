package ch.overlandmap.map.map

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import ch.overlandmap.map.tiles.Constants
import ch.overlandmap.map.tiles.Reader
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * A local tile container the [LocalTileServer] can read: an SQLite `.mbtiles`
 * file or a `.pmtiles` v3 archive. Both return tiles in XYZ addressing.
 */
sealed interface TileArchive : AutoCloseable {
    val file: File

    /** The tile's bytes, decompressed for vector tiles; null when absent. */
    fun tile(zoom: Int, x: Int, y: Int, isRaster: Boolean): ByteArray?

    companion object {
        /** Opens [file] by extension; null when missing, unknown or corrupt. */
        fun open(file: File): TileArchive? {
            if (!file.isFile) return null
            return try {
                when (file.extension) {
                    "mbtiles" -> MbtilesArchive(file)
                    "pmtiles" -> PmtilesArchive(file)
                    else -> null
                }
            } catch (e: Exception) {
                Log.w("TileArchive", "Cannot open ${file.name}", e)
                null
            }
        }
    }
}

/** MBTiles: SQLite storing gzipped vector (or raw raster) tiles in TMS rows. */
private class MbtilesArchive(override val file: File) : TileArchive {

    private val db: SQLiteDatabase =
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)

    override fun tile(zoom: Int, x: Int, y: Int, isRaster: Boolean): ByteArray? {
        // MBTiles uses TMS row order for vector tiles; requests come in XYZ.
        val row = if (isRaster) y else (1 shl zoom) - 1 - y
        db.rawQuery(
            "SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",
            arrayOf(zoom.toString(), x.toString(), row.toString()),
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            val data = cursor.getBlob(0)
            return if (isRaster) data else GZIPInputStream(data.inputStream()).readBytes()
        }
    }

    override fun close() = db.close()
}

/** PMTiles v3, read through the [Reader] ported from the Flutter app. */
private class PmtilesArchive(override val file: File) : TileArchive {

    private val reader = Reader(file)

    // The Reader caches directories and is not thread-safe.
    @Synchronized
    override fun tile(zoom: Int, x: Int, y: Int, isRaster: Boolean): ByteArray? {
        val data = reader.getTile(zoom, x, y) ?: return null
        // The reader returns tiles as stored; gunzip when the archive says so.
        return if (reader.tileCompression == Constants.COMPRESSION_GZIP) {
            GZIPInputStream(data.inputStream()).readBytes()
        } else {
            data
        }
    }

    @Synchronized
    override fun close() = reader.close()
}

/**
 * The local tile archives, laid out like the Flutter app's:
 *
 *  - `files/osm_pmtiles/planet.pmtiles` — the world base map (`planet`)
 *  - pmtiles in `files/osm_pmtiles/detail/` — per-pack detail maps (`detail`)
 *  - mbtiles in `files/osm/` — legacy detail maps (`detail`, `planet.mbtiles`)
 *  - mbtiles in `files/contour/` and `files/hillshade/`
 *
 * A source name resolves to one archive (`planet`) or to a group searched in
 * order (`detail`, `contour`, `hillshade`), so styles never care which pack's
 * file holds a tile, nor whether it is mbtiles or pmtiles.
 */
class TileArchiveRegistry(private val filesDir: File) {

    private var planet: TileArchive? = null
    private var detail: List<TileArchive> = emptyList()
    private var contour: List<TileArchive> = emptyList()
    private var hillshade: List<TileArchive> = emptyList()

    val planetFile: File get() = File(filesDir, "$PMTILES_DIR/planet.pmtiles")

    @Synchronized
    fun reload() {
        closeAll()
        val legacyPlanet = File(filesDir, "$MBTILES_DIR/planet.mbtiles")
        planet = TileArchive.open(planetFile) ?: TileArchive.open(legacyPlanet)
        detail = openAll(File(filesDir, "$PMTILES_DIR/detail")) +
            openAll(File(filesDir, MBTILES_DIR), exclude = legacyPlanet)
        contour = openAll(File(filesDir, "contour"))
        hillshade = openAll(File(filesDir, "hillshade"))
    }

    @Synchronized
    fun hasPlanet(): Boolean = planet != null

    @Synchronized
    fun hasDetailTiles(): Boolean = detail.isNotEmpty()

    @Synchronized
    fun tile(source: String, zoom: Int, x: Int, y: Int, isRaster: Boolean): ByteArray? {
        val archives = when (source) {
            "planet" -> listOfNotNull(planet)
            "detail" -> detail
            "contour" -> contour
            "hillshade" -> hillshade
            else -> (detail + contour + hillshade).filter { it.file.nameWithoutExtension == source }
        }
        for (archive in archives) {
            try {
                archive.tile(zoom, x, y, isRaster)?.let { return it }
            } catch (e: Exception) {
                Log.w("TileArchive", "Tile read failed in ${archive.file.name}", e)
            }
        }
        return null
    }

    @Synchronized
    fun closeAll() {
        (listOfNotNull(planet) + detail + contour + hillshade).forEach {
            runCatching { it.close() }
        }
        planet = null
        detail = emptyList()
        contour = emptyList()
        hillshade = emptyList()
    }

    private fun openAll(directory: File, exclude: File? = null): List<TileArchive> =
        directory.listFiles()
            ?.filter { it.isFile && it != exclude }
            ?.sortedBy { it.name }
            ?.mapNotNull { TileArchive.open(it) }
            ?: emptyList()

    private companion object {
        const val PMTILES_DIR = "osm_pmtiles"
        const val MBTILES_DIR = "osm"
    }
}
