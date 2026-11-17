package ch.overlandmap.map.data.routes

import android.content.Context
import ch.overlandmap.map.data.GeneralAssetManager
import ch.overlandmap.map.data.InstalledAssetVersion
import ch.overlandmap.map.data.ShopRepository
import ch.overlandmap.map.planning.PlannerGraphFile
import ch.overlandmap.map.planning.RouteGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

/** The route network sources (iOS `RouteDataSource`). */
object RouteDataSource {
    /**
     * The online vector tiles of the network (layers `segments` and `nodes`), used by the map
     * until the `routes-pmtiles` asset is installed; it then reads the local copy.
     */
    const val TILE_URL_TEMPLATE = "https://pmtiles-cloudflare.overlandmap.workers.dev/routes/{z}/{x}/{y}.mvt"
    const val TILE_MAX_ZOOM = 12
}

/**
 * Keeps the planner's graph (`routes.pmtiles.graph.json`) on the device and parses it.
 *
 * The graph is the Firestore asset `routes-graph`, a small zip, downloaded at startup with the
 * other general assets. The zip stays next to the extracted file: [GeneralAssetManager] uses it
 * to tell whether the asset is installed and which version.
 */
class RouteDataManager(
    context: Context,
    private val scope: CoroutineScope,
    shop: ShopRepository,
) {
    private val directory = File(context.filesDir, "routes")
    private val graphFile = File(directory, GRAPH_FILE_NAME)

    val asset = GeneralAssetManager(
        context, scope, shop,
        assetId = ASSET_ID,
        destination = File(directory, "routes-graph.zip"),
        title = "Routing data",
        reloadTiles = false,
        onInstalled = { zip -> withContext(Dispatchers.IO) { installGraph(zip, graphFile) } },
    )

    private val _plannerDataInstalled = MutableStateFlow(graphFile.length() > 0)

    /** The graph file is on disk: the planner can run. */
    val plannerDataInstalled: StateFlow<Boolean> = _plannerDataInstalled.asStateFlow()

    /** The installed asset version; it changes when a (new) graph has been installed. */
    val installedVersion: StateFlow<InstalledAssetVersion?> get() = asset.installed

    init {
        // Both the first install and a version upgrade end by recording the installed version.
        scope.launch { asset.installed.collect { refreshState() } }
    }

    /** Checks the asset online and downloads it when missing or outdated. */
    fun ensure() = asset.ensure()

    fun refreshState() {
        _plannerDataInstalled.value = graphFile.length() > 0
    }

    /** Parses the graph file off the main thread; it is about 450 KB. */
    suspend fun loadGraph(): RouteGraph = withContext(Dispatchers.Default) {
        refreshState()
        if (!graphFile.isFile || graphFile.length() == 0L) throw IOException("The route graph is not installed.")
        RouteGraph(PlannerGraphFile.parse(graphFile.readText()))
    }

    companion object {
        const val ASSET_ID = "routes-graph"
        const val GRAPH_FILE_NAME = "routes.pmtiles.graph.json"

        /**
         * Extracts the `*.graph.json` the zip contains (at any depth) to [destination], replacing
         * the previous one. The new file is complete before it takes the old one's place.
         */
        internal fun installGraph(zip: File, destination: File) {
            destination.parentFile?.mkdirs()
            val staging = File(destination.path + ".part")
            var found = false
            ZipInputStream(zip.inputStream().buffered()).use { input ->
                var fallback: ByteArray? = null
                while (true) {
                    val entry = input.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || name.contains("__MACOSX") || !name.endsWith(".json")) continue
                    if (name.endsWith(".graph.json")) {
                        staging.outputStream().use { input.copyTo(it) }
                        found = true
                        break
                    }
                    if (fallback == null) fallback = input.readBytes()
                }
                if (!found && fallback != null) {
                    staging.writeBytes(fallback)
                    found = true
                }
            }
            if (!found) throw IOException("No graph in the route network archive")
            destination.delete()
            if (!staging.renameTo(destination)) {
                staging.copyTo(destination, overwrite = true)
                staging.delete()
            }
        }
    }
}
