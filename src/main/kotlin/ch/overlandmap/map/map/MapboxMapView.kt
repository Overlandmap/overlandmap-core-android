package ch.overlandmap.map.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.Style

/**
 * Compose wrapper around a Mapbox [MapView] (the itinerary screen only; the
 * rest of the app uses [MapLibreMapView]). [onStyleLoaded] runs every time the
 * style finishes loading; add sources and layers there. Mirrors
 * [MapLibreMapView] so the itinerary map reads the same way.
 */
@Composable
fun MapboxMapView(
    modifier: Modifier = Modifier,
    styleUrl: String,
    onMapReady: (MapView) -> Unit = {},
    onStyleLoaded: (MapboxMap, Style) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    // The Mapbox SDK reads the public token from MapboxOptions; apply it before
    // the first map is created.
    MapboxInit.ensure(app.mapboxTokenManager.cachedTokenNow())

    val mapView = remember { MapView(context) }
    val map = mapView.mapboxMap

    LaunchedEffect(Unit) { onMapReady(mapView) }

    // (Re)load the style whenever [styleUrl] changes, so the style menu switches
    // live; the caller re-adds its sources/layers each time.
    LaunchedEffect(styleUrl) {
        map.loadStyle(styleUrl) { style -> onStyleLoaded(map, style) }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        ) {
            ZoomButton("+", stringResource(R.string.zoom_in)) {
                map.setCamera(cameraZoomBy(map, 1.0))
            }
            Spacer(modifier = Modifier.height(8.dp))
            ZoomButton("−", stringResource(R.string.zoom_out)) {
                map.setCamera(cameraZoomBy(map, -1.0))
            }
        }
    }
}

private fun cameraZoomBy(map: MapboxMap, delta: Double) =
    com.mapbox.maps.CameraOptions.Builder()
        .zoom((map.cameraState.zoom + delta))
        .build()

@Composable
private fun ZoomButton(glyph: String, label: String, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge)
    }
}
