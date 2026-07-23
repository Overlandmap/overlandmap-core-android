package ch.overlandmap.map.data

import android.content.Context
import android.widget.Toast
import ch.overlandmap.map.R
import com.mapbox.common.TileRegionLoadOptions
import com.mapbox.common.TileStore
import com.mapbox.geojson.Point
import com.mapbox.geojson.Polygon
import com.mapbox.maps.OfflineManager
import com.mapbox.maps.TilesetDescriptorOptions
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Geographic box + zoom range selected for an offline satellite download. */
data class TileArea(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
    val minZoom: Int,
    val maxZoom: Int,
)

/** Result of counting the tiles an area would need. */
data class TileEstimate(val tiles: Long, val sizeBytes: Long)

/** Progress of one satellite-tile region download, keyed by region id. */
data class SatelliteDownloadProgress(
    val name: String,
    val fraction: Float,
    val sizeBytes: Long = 0L,
    val done: Boolean = false,
    val error: String? = null,
)

/**
 * Downloads Mapbox satellite imagery for an area, using the Mapbox SDK's
 * offline mechanism ([TileStore] + [OfflineManager]). App-scoped, so a download
 * keeps running after the user leaves the itinerary screen; the Downloads
 * screen observes [progress], and a toast is shown on completion.
 */
class SatelliteTileManager(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val tileStore: TileStore by lazy { TileStore.create() }
    private val offlineManager: OfflineManager by lazy { OfflineManager() }

    private val _progress = MutableStateFlow<Map<String, SatelliteDownloadProgress>>(emptyMap())
    val progress: StateFlow<Map<String, SatelliteDownloadProgress>> = _progress

    /** Tiles (and an estimated byte size) the [area] would require. */
    fun estimate(area: TileArea): TileEstimate {
        var tiles = 0L
        for (z in area.minZoom..area.maxZoom) {
            val n = 1 shl z
            val x0 = lonToTileX(area.minLon, n)
            val x1 = lonToTileX(area.maxLon, n)
            val y0 = latToTileY(area.maxLat, n)
            val y1 = latToTileY(area.minLat, n)
            tiles += (abs(x1 - x0) + 1).toLong() * (abs(y1 - y0) + 1).toLong()
        }
        return TileEstimate(tiles, tiles * BYTES_PER_TILE)
    }

    /** Starts downloading [area] of [styleUri]; progress lands in [progress]. */
    fun download(name: String, styleUri: String, area: TileArea) {
        val regionId = "satellite-${System.currentTimeMillis()}"
        val descriptor = offlineManager.createTilesetDescriptor(
            TilesetDescriptorOptions.Builder()
                .styleURI(styleUri)
                .minZoom(area.minZoom.toByte())
                .maxZoom(area.maxZoom.toByte())
                .build()
        )
        val geometry = Polygon.fromLngLats(
            listOf(
                listOf(
                    Point.fromLngLat(area.minLon, area.minLat),
                    Point.fromLngLat(area.maxLon, area.minLat),
                    Point.fromLngLat(area.maxLon, area.maxLat),
                    Point.fromLngLat(area.minLon, area.maxLat),
                    Point.fromLngLat(area.minLon, area.minLat),
                )
            )
        )
        val options = TileRegionLoadOptions.Builder()
            .geometry(geometry)
            .descriptors(listOf(descriptor))
            .acceptExpired(true)
            .build()

        _progress.update { it + (regionId to SatelliteDownloadProgress(name, 0f)) }
        tileStore.loadTileRegion(
            regionId,
            options,
            { progress ->
                val required = progress.requiredResourceCount
                val fraction =
                    if (required > 0) progress.completedResourceCount.toFloat() / required else 0f
                _progress.update {
                    it + (regionId to SatelliteDownloadProgress(
                        name, fraction, sizeBytes = progress.completedResourceSize,
                    ))
                }
            },
        ) { expected ->
            scope.launch(Dispatchers.Main) {
                val size = _progress.value[regionId]?.sizeBytes ?: 0L
                if (expected.isValue) {
                    _progress.update {
                        it + (regionId to SatelliteDownloadProgress(name, 1f, size, done = true))
                    }
                    Toast.makeText(
                        context,
                        context.getString(R.string.satellite_download_complete, name),
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    _progress.update {
                        it + (regionId to SatelliteDownloadProgress(
                            name, 0f, size, error = expected.error?.message,
                        ))
                    }
                }
            }
        }
    }

    /** Removes a downloaded region from the tile store and the progress list. */
    fun delete(regionId: String) {
        tileStore.removeTileRegion(regionId)
        _progress.update { it - regionId }
    }

    private companion object {
        /** Rough average bytes per satellite tile, for the size estimate. */
        const val BYTES_PER_TILE = 30_000L

        fun lonToTileX(lon: Double, n: Int): Int =
            floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)

        fun latToTileY(lat: Double, n: Int): Int {
            val r = Math.toRadians(lat)
            return floor((1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * n)
                .toInt().coerceIn(0, n - 1)
        }
    }
}
