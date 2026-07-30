package ch.overlandmap.map.map

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * Localhost HTTP server for offline maps, a simplified port of the Flutter
 * app's MBTilesServer. It serves:
 *
 *  - `/tiles/{source}/{z}/{x}/{y}.pbf|mvt|webp|png` — tiles from the local
 *    archives (mbtiles and pmtiles) in [TileArchiveRegistry]
 *  - `/glyphs/{fontstack}/{range}.pbf` — font glyphs from `files/assets`,
 *    trying each font of a comma-separated stack in turn
 *  - any other path — static files (style JSON, sprites) from `files/assets`;
 *    style JSON gets `http://localhost:8000` rewritten to the bound port
 *
 * Archives and style assets are downloaded by the app into those directories;
 * the map widget then loads `http://localhost:{port}/styles/....json`.
 */
object LocalTileServer : Runnable {

    private const val PREFERRED_PORT = 8000

    /** The port styles are authored against; rewritten to [port] when served. */
    private const val AUTHORED_BASE_URL = "http://localhost:8000"

    /** The offline styles rewritten by [OfflineStyle] to render the pack tiles. */
    private const val OFFLINE_STYLE_PATH = "styles/detailed.json"
    private const val LIGHT_STYLE_PATH = "styles/simplified.json"

    private lateinit var assetsDirectory: File
    private var registry: TileArchiveRegistry? = null
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var isRunning = false

    val port: Int get() = serverSocket?.localPort ?: PREFERRED_PORT
    val baseUrl: String get() = "http://localhost:$port"

    fun start(context: Context) {
        if (isRunning) {
            Log.d("LocalTileServer", "start() called but already running on port $port")
            return
        }
        assetsDirectory = File(context.filesDir, "assets").apply { mkdirs() }
        Log.d("LocalTileServer", "Assets directory: $assetsDirectory (exists=${assetsDirectory.exists()})")
        registry = TileArchiveRegistry(context.filesDir).also { it.reload() }
        Log.d("LocalTileServer", "TileArchiveRegistry loaded: " +
            "hasPlanet=${registry?.hasPlanet()}, " +
            "hasDetail=${registry?.hasDetailTiles()}, " +
            "hasHillshade=${registry?.hasHillshade()}, " +
            "hasContour=${registry?.hasContour()}")
        serverSocket = bindSocket()
        if (serverSocket == null) {
            Log.e("LocalTileServer", "Failed to bind any port — server not started")
            return
        }
        isRunning = true
        Log.i("LocalTileServer", "Server started on port $port (baseUrl=$baseUrl)")
        Thread(this, "LocalTileServer").start()
    }

    fun stop() {
        isRunning = false
        serverSocket?.close()
        serverSocket = null
        registry?.closeAll()
        registry = null
    }

    /** Call after downloading or deleting a tile archive. */
    fun reloadArchives() {
        registry?.reload()
    }

    /** True when at least one detail map is available for offline rendering. */
    fun hasOfflineTiles(): Boolean = registry?.hasDetailTiles() == true

    /** True when the world base map (planet.pmtiles) is available. */
    fun hasPlanet(): Boolean = registry?.hasPlanet() == true

    private fun bindSocket(): ServerSocket? =
        try {
            ServerSocket(PREFERRED_PORT).also {
                Log.d("LocalTileServer", "Bound preferred port $PREFERRED_PORT")
            }
        } catch (e1: Exception) {
            Log.w("LocalTileServer", "Cannot bind port $PREFERRED_PORT: ${e1.message}")
            try {
                ServerSocket(0).also {
                    Log.d("LocalTileServer", "Bound fallback port ${it.localPort}")
                }
            } catch (e2: Exception) {
                Log.e("LocalTileServer", "Cannot bind any port", e2)
                null
            }
        }

    override fun run() {
        val socket = serverSocket ?: return
        Log.d("LocalTileServer", "Accept loop started on port ${socket.localPort}")
        try {
            while (isRunning) {
                val client = socket.accept()
                // Handle each request on its own thread so MapLibre/Mapbox
                // concurrent tile, sprite and glyph fetches don't queue up.
                Thread {
                    client.use { handle(it) }
                }.start()
            }
        } catch (e: Exception) {
            if (isRunning) Log.w("LocalTileServer", "Server loop ended", e)
        } finally {
            Log.d("LocalTileServer", "Accept loop exited (isRunning=$isRunning)")
            isRunning = false
        }
    }

    private fun handle(socket: Socket) {
        val reader = socket.getInputStream().reader().buffered()
        val output = PrintStream(socket.getOutputStream())
        try {
            var path: String? = null
            do {
                val line = reader.readLine() ?: ""
                if (line.startsWith("GET ")) {
                    path = URLDecoder.decode(line.substringAfter("GET ").substringBefore(" "), "UTF-8")
                    break
                }
            } while (line.isNotEmpty())

            val bytes = path?.let { serve(it) }
            if (bytes == null) {
                output.println("HTTP/1.0 404 Not Found")
                output.println()
            } else {
                output.println("HTTP/1.0 200 OK")
                output.println("Content-Type: " + mimeType(path!!.substringAfterLast('.')))
                output.println("Content-Length: " + bytes.size)
                output.println()
                output.write(bytes)
            }
            output.flush()
        } catch (e: Exception) {
            Log.w("LocalTileServer", "Request failed", e)
        }
    }

    private fun serve(fullPath: String): ByteArray? {
        // The offline style URL carries the hillshade/contour toggles as a query.
        val path = fullPath.substringBefore('?')
        val query = fullPath.substringAfter('?', "")
        val tileMatch = Regex("/tiles/([^/]+)/(\\d+)/(\\d+)/(\\d+)\\.(pbf|mvt|webp|png)").matchEntire(path)
        if (tileMatch != null) {
            val (source, z, x, y, extension) = tileMatch.destructured
            return registry?.tile(
                source, z.toInt(), x.toInt(), y.toInt(),
                isRaster = extension == "webp" || extension == "png",
            )
        }
        // A glyph request names a font stack; serve the first font that has
        // the range (like the Flutter server).
        val glyphMatch = Regex("/glyphs/([^/]+)/(.+)").matchEntire(path)
        if (glyphMatch != null) {
            val (fontStack, range) = glyphMatch.destructured
            return fontStack.split(',')
                .firstNotNullOfOrNull { font -> staticFile("/glyphs/${font.trim()}/$range") }
        }
        val bytes = staticFile(path) ?: return null
        if (!path.endsWith(".json")) return bytes
        var json = bytes.toString(Charsets.UTF_8)
        // The offline styles ship rendering only the world `planet` source; they
        // are rewritten so the pack's downloaded detail/contour/hillshade tiles
        // are actually shown, honouring the ?hillshade=&contour= toggles (see
        // [OfflineStyle]).
        val relative = path.trimStart('/')
        if (relative == OFFLINE_STYLE_PATH || relative == LIGHT_STYLE_PATH) {
            val params = query.split('&').mapNotNull {
                val eq = it.indexOf('=')
                if (eq < 0) null else it.substring(0, eq) to it.substring(eq + 1)
            }.toMap()
            json = OfflineStyle.transform(
                json,
                showHillshade = params["hillshade"] != "0",
                showContour = params["contour"] != "0",
                hasOfflineHillshade = registry?.hasHillshade() == true,
                hasContour = registry?.hasContour() == true,
            )
            // Localize the labels to the chosen map language (see OfflineStyle).
            params["lang"]?.let { json = OfflineStyle.translateLabels(json, it) }
        }
        // Styles are authored against port 8000; always rewrite to the actual
        // bound port so MapLibre/Mapbox fetches tiles from the running server.
        json = json.replace(AUTHORED_BASE_URL, baseUrl)
        return json.toByteArray()
    }

    private fun staticFile(path: String): ByteArray? {
        val file = File(assetsDirectory, path.trimStart('/'))
        // Resolve ".." to keep requests inside the assets directory.
        if (!file.canonicalPath.startsWith(assetsDirectory.canonicalPath)) return null
        return if (file.isFile) file.readBytes() else null
    }

    private fun mimeType(extension: String): String = when (extension) {
        "pbf", "mvt" -> "application/x-protobuf"
        "json" -> "application/json"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "jpg" -> "image/jpeg"
        else -> "application/octet-stream"
    }
}
