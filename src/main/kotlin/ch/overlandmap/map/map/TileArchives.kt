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
        // PMTiles v3 files begin with the ASCII magic "PMTiles" then a version
        // byte; SQLite (mbtiles) files begin with "SQLite format 3\u0000".
        private val PMTILES_MAGIC = "PMTiles".toByteArray(Charsets.US_ASCII)
        private val SQLITE_MAGIC = "SQLite format 3".toByteArray(Charsets.US_ASCII)

        /**
         * Opens [file] by its actual content (magic bytes), falling back to the
         * extension. Assets are sometimes delivered with a mismatched extension
         * — a PMTiles archive named `.mbtiles`, say — so trusting the extension
         * alone silently loses those tiles. Returns null when missing, of an
         * unknown format, or corrupt.
         */
        fun open(file: File): TileArchive? {
            if (!file.isFile) return null
            return try {
                when (detectFormat(file) ?: file.extension) {
                    "mbtiles" -> MbtilesArchive(file)
                    "pmtiles" -> PmtilesArchive(file)
                    else -> null
                }
            } catch (e: Exception) {
                Log.w("TileArchive", "Cannot open ${file.name}", e)
                null
            }
        }

        /** The archive format from the file's magic bytes, or null if unknown. */
        private fun detectFormat(file: File): String? {
            val header = ByteArray(16)
            val read = file.inputStream().use { it.read(header) }
            if (read < PMTILES_MAGIC.size) return null
            return when {
                header.startsWith(PMTILES_MAGIC) -> "pmtiles"
                header.startsWith(SQLITE_MAGIC) -> "mbtiles"
                else -> null
            }
        }

        private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
            if (size < prefix.size) return false
            for (i in prefix.indices) if (this[i] != prefix[i]) return false
            return true
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
    private var dem: List<TileArchive> = emptyList()

    val planetFile: File get() = File(filesDir, "$PMTILES_DIR/planet.pmtiles")
    val planetDemFile: File get() = File(filesDir, "$PMTILES_DIR/planet-dem.pmtiles")

    @Synchronized
    fun reload() {
        closeAll()
        val legacyPlanet = File(filesDir, "$MBTILES_DIR/planet.mbtiles")
        planet = TileArchive.open(planetFile) ?: TileArchive.open(legacyPlanet)
        detail = openAll(File(filesDir, "$PMTILES_DIR/detail")) +
            openAll(File(filesDir, MBTILES_DIR), exclude = legacyPlanet)
        contour = openAll(File(filesDir, "contour"))
        hillshade = openAll(File(filesDir, "hillshade"))
        // Terrain-RGB DEM tiles served (as raster PNGs) for the style's
        // `demSource` (raster-dem hillshade) and `demColorSource` (elevation
        // colour), matching the iOS tile server's `/tiles/dem` route. Per-pack
        // DEM (files/dem, z7–11) is searched first so its higher-zoom tiles win
        // where present; the global planet-dem (osm_pmtiles/planet-dem.pmtiles,
        // z0–6) is appended to cover the low zooms everywhere else.
        dem = openAll(File(filesDir, "dem")) +
            listOfNotNull(TileArchive.open(planetDemFile))
    }

    @Synchronized
    fun hasPlanet(): Boolean = planet != null

    @Synchronized
    fun hasDetailTiles(): Boolean = detail.isNotEmpty()

    @Synchronized
    fun hasHillshade(): Boolean = hillshade.isNotEmpty()

    @Synchronized
    fun hasContour(): Boolean = contour.isNotEmpty()

    @Synchronized
    fun hasDem(): Boolean = dem.isNotEmpty()

    /**
     * The highest zoom for which any DEM archive holds tiles, or null when no
     * DEM is installed. The bundled style declares the DEM sources as
     * `maxzoom 11` (assuming per-pack DEM), but when only the global
     * `planet-dem` (z0–6) is present there are no z7+ tiles — so the tile
     * server caps the served style's DEM `maxzoom` to this, letting the map
     * SDK overzoom the coarser tiles instead of requesting 404s. Read from each
     * archive's own metadata so it stays correct as new DEM packs arrive.
     */
    @Synchronized
    fun demMaxZoom(): Int? = dem.mapNotNull { maxZoomOf(it.file) }.maxOrNull()

    @Synchronized
    fun tile(source: String, zoom: Int, x: Int, y: Int, isRaster: Boolean): ByteArray? {
        val archives = when (source) {
            "planet" -> listOfNotNull(planet)
            "detail" -> detail
            "contour" -> contour
            "hillshade" -> hillshade
            "dem" -> dem
            else -> (detail + contour + hillshade + dem).filter { it.file.nameWithoutExtension == source }
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
        (listOfNotNull(planet) + detail + contour + hillshade + dem).forEach {
            runCatching { it.close() }
        }
        planet = null
        detail = emptyList()
        contour = emptyList()
        hillshade = emptyList()
        dem = emptyList()
    }

    private fun openAll(directory: File, exclude: File? = null): List<TileArchive> =
        directory.listFiles()
            ?.filter { it.isFile && it != exclude }
            ?.sortedBy { it.name }
            ?.mapNotNull { TileArchive.open(it) }
            ?: emptyList()

    /** Max zoom declared by an archive's own metadata, or null if unreadable. */
    private fun maxZoomOf(file: File): Int? = runCatching {
        val header = ByteArray(128)
        val read = file.inputStream().use { it.read(header) }
        if (read >= 8 && header.copyOfRange(0, 7).toString(Charsets.US_ASCII) == "PMTiles") {
            // PMTiles v3 header: max_zoom is a single byte at offset 101.
            if (read > 101) (header[101].toInt() and 0xFF) else null
        } else {
            // MBTiles: read the maxzoom from the metadata table.
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("SELECT value FROM metadata WHERE name='maxzoom'", null).use { c ->
                    if (c.moveToFirst()) c.getString(0)?.trim()?.toIntOrNull() else null
                }
            }
        }
    }.getOrNull()

    private companion object {
        const val PMTILES_DIR = "osm_pmtiles"
        const val MBTILES_DIR = "osm"
    }
}
