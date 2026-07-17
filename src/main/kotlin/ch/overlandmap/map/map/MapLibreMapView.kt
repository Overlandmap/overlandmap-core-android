package ch.overlandmap.map.map

import android.content.Context
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
import androidx.compose.runtime.DisposableEffect
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
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Compose wrapper around a MapLibre [MapView], with zoom in/out buttons.
 * [onStyleLoaded] runs every time the style finishes loading; add sources and
 * layers there. [styleUrl] overrides the default offline-first style selection.
 */
@Composable
fun MapLibreMapView(
    modifier: Modifier = Modifier,
    styleUrl: String? = null,
    onMapReady: (MapLibreMap) -> Unit = {},
    onStyleLoaded: (MapLibreMap, Style) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val preferences = (context.applicationContext as OverlandApp).userPreferences
    val mapView = remember { createMapView(context) }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }

    DisposableEffect(Unit) {
        mapView.onStart()
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
        }
    }

    // (Re)load the style whenever the map becomes ready or [styleUrl] changes,
    // so the itinerary map's style menu can switch styles live. The style's
    // language is re-applied and the caller re-adds its sources/layers each
    // time via [onStyleLoaded].
    val resolvedUrl = styleUrl ?: defaultStyleUrl(context)
    LaunchedEffect(map, resolvedUrl) {
        val readyMap = map ?: return@LaunchedEffect
        // Ensure the (possibly later-fetched) Mapbox token is applied before a
        // mapbox:// style loads, in case MapLibre was first initialised before
        // the token had been cached.
        if (resolvedUrl.startsWith("mapbox://")) {
            (context.applicationContext as OverlandApp).mapboxTokenManager.cachedTokenNow()
                ?.let { MapLibre.setApiKey(it) }
        }
        readyMap.setStyle(resolvedUrl) { style ->
            applyStyleLanguage(style, resolvedUrl, preferences.mapLanguageNow())
            onStyleLoaded(readyMap, style)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                mapView.getMapAsync { readyMap ->
                    map = readyMap
                    onMapReady(readyMap)
                }
                mapView
            },
        )
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp),
        ) {
            ZoomButton("+", stringResource(R.string.zoom_in)) {
                map?.animateCamera(CameraUpdateFactory.zoomIn())
            }
            Spacer(modifier = Modifier.height(8.dp))
            ZoomButton("−", stringResource(R.string.zoom_out)) {
                map?.animateCamera(CameraUpdateFactory.zoomOut())
            }
        }
    }
}

@Composable
private fun ZoomButton(glyph: String, label: String, onClick: () -> Unit) {
    SmallFloatingActionButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = label },
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge)
    }
}

private fun createMapView(context: Context): MapView {
    // Initialise with the cached Mapbox token so mapbox:// styles resolve; the
    // token is warmed at startup, so it is present on all but the very first
    // run (offline styles need no token).
    val token = (context.applicationContext as OverlandApp).userPreferences.mapboxTokenNow()?.first
    if (token != null) {
        MapLibre.getInstance(context, token, WellKnownTileServer.Mapbox)
        MapLibre.setApiKey(token)
    } else {
        MapLibre.getInstance(context)
    }
    return MapView(context)
}

private fun defaultStyleUrl(context: Context): String =
    MapStyles.styleUrl(java.io.File(context.filesDir, "assets"))

/** Localizes labels using the schema of whichever style (offline vs Mapbox). */
private fun applyStyleLanguage(style: Style, styleUrl: String, language: String) {
    if (styleUrl.startsWith("mapbox://")) applyMapboxLanguage(style, language)
    else applyMapLanguage(style, language)
}

/** Bounds helper for fitting a map around min/max coordinates. */
fun boundsOf(latMin: Double, latMax: Double, lonMin: Double, lonMax: Double): LatLngBounds =
    LatLngBounds.from(latMax, lonMax, latMin, lonMin)
