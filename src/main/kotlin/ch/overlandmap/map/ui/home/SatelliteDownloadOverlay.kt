package ch.overlandmap.map.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.TileArea
import ch.overlandmap.map.data.TileEstimate
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.ScreenCoordinate
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Above this many tiles the area is refused (must zoom in). */
private const val TILE_THRESHOLD = 10_000

/** Zoom levels downloaded: the current view up to this satellite detail level. */
private const val MAX_ZOOM = 16

/**
 * Overlay for selecting a satellite area to download offline: a semi-transparent
 * square inset 10% on each side delimits the area, with a Calculate button below
 * it. Calculate counts the tiles; under [TILE_THRESHOLD] the button becomes
 * "Download XXX MB", otherwise it is disabled with a "too large" message.
 */
@Composable
fun SatelliteDownloadOverlay(
    map: MapboxMap,
    onEstimate: (TileArea) -> TileEstimate,
    onDownload: (TileArea) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var area by remember { mutableStateOf<TileArea?>(null) }
    var estimate by remember { mutableStateOf<TileEstimate?>(null) }

    Box(modifier = modifier.fillMaxSize().onSizeChanged { size = it }) {
        // The selection square: central 80% (10% padding each side).
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.8f)
                .fillMaxHeight(0.8f)
                .border(2.dp, Color.White)
                .background(Color.White.copy(alpha = 0.12f)),
        )

        IconButton(
            onClick = onClose,
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) {
            Surface(shape = MaterialTheme.shapes.small, color = Color.White.copy(alpha = 0.85f)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.close),
                    modifier = Modifier.padding(4.dp),
                )
            }
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            val current = estimate
            val tooLarge = current != null && current.tiles >= TILE_THRESHOLD
            if (current != null) {
                Surface(shape = MaterialTheme.shapes.small, color = Color.White.copy(alpha = 0.85f)) {
                    Text(
                        stringResource(R.string.tiles_count, "%,d".format(current.tiles)),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            // Too large: show the message and keep the Calculate button so the
            // user can zoom in and recompute.
            if (tooLarge) {
                Surface(shape = MaterialTheme.shapes.small, color = Color.White.copy(alpha = 0.9f)) {
                    Text(
                        stringResource(R.string.satellite_area_too_large),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            val calculating = current == null || tooLarge
            Button(
                onClick = {
                    if (calculating) {
                        val selected = areaFor(map, size)
                        area = selected
                        estimate = selected?.let(onEstimate)
                    } else {
                        area?.let(onDownload)
                    }
                },
            ) {
                Text(
                    if (calculating) stringResource(R.string.calculate)
                    else stringResource(
                        R.string.download_size_mb,
                        "%.1f".format(current!!.sizeBytes / 1_000_000.0),
                    )
                )
            }
        }
    }
}

/** Geographic box of the central-80% square, at the current view's zoom range. */
private fun areaFor(map: MapboxMap, size: IntSize): TileArea? {
    if (size.width == 0 || size.height == 0) return null
    val w = size.width.toDouble()
    val h = size.height.toDouble()
    val nw = map.coordinateForPixel(ScreenCoordinate(0.1 * w, 0.1 * h))
    val se = map.coordinateForPixel(ScreenCoordinate(0.9 * w, 0.9 * h))
    val minZoom = floor(map.cameraState.zoom).toInt().coerceIn(0, MAX_ZOOM)
    return TileArea(
        minLat = min(nw.latitude(), se.latitude()),
        maxLat = max(nw.latitude(), se.latitude()),
        minLon = min(nw.longitude(), se.longitude()),
        maxLon = max(nw.longitude(), se.longitude()),
        minZoom = minZoom,
        maxZoom = MAX_ZOOM,
    )
}
