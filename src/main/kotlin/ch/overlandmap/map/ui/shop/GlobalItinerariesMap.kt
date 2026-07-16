package ch.overlandmap.map.ui.shop

import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import ch.overlandmap.map.map.MapLibreMapView
import ch.overlandmap.map.map.MapStyles
import ch.overlandmap.map.map.boundsOf
import ch.overlandmap.map.map.ensureTracksLayer
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.TrackPack
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.interpolate
import org.maplibre.android.style.expressions.Expression.linear
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.stop
import org.maplibre.android.style.expressions.Expression.zoom
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property.LINE_CAP_ROUND
import org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.geojson.Feature

/** The style's own tracks layer (blue lines), used for hit testing. */
private const val STYLE_TRACKS_LAYER = "tracks"

/** Our overlay drawing the selected pack's tracks in red. */
private const val SELECTED_LAYER = "shop-selected-tracks"

private const val TRACK_PACK_ID = "trackPackId"

private const val ITINERARY_ID = "itineraryId"

/**
 * World map of the shop. The global style already draws every track (blue);
 * tapping one reports its pack, itinerary slug and tap position via
 * [onTrackTapped], and the tracks of [selectedPackId] are re-drawn in red by
 * a filtered overlay layer.
 */
@Composable
fun GlobalItinerariesMap(
    selectedPackId: String?,
    onTrackTapped: (packId: String, itinerarySlug: String?, position: Offset) -> Unit,
    onMapReady: (MapLibreMap) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    val context = LocalContext.current

    MapLibreMapView(
        modifier = modifier,
        styleUrl = MapStyles.globalStyleUrl(context),
        onMapReady = onMapReady,
        onStyleLoaded = { map, style ->
            ensureTracksLayer(style)
            addSelectedTracksLayer(style)
            installTapHandler(map) { feature, position ->
                feature.getStringProperty(TRACK_PACK_ID)?.let { packId ->
                    onTrackTapped(packId, feature.getStringProperty(ITINERARY_ID), position)
                }
            }
            loadedStyle = style
        },
    )

    LaunchedEffect(selectedPackId, loadedStyle) {
        loadedStyle?.getLayerAs<LineLayer>(SELECTED_LAYER)?.setFilter(
            if (selectedPackId == null) literal(false)
            else eq(get(TRACK_PACK_ID), literal(selectedPackId)),
        )
    }
}

/**
 * Detail map of one pack: the style's tracks layer is filtered down to the
 * pack's own itineraries (blue). Tapping one reports its `itineraryId`, and
 * the selected itinerary is re-drawn in red by the overlay layer.
 */
@Composable
fun PackTracksMap(
    packId: String,
    selectedItineraryId: String?,
    onItineraryTapped: (itinerarySlug: String, position: Offset) -> Unit,
    onMapReady: (MapLibreMap) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var loadedStyle by remember { mutableStateOf<Style?>(null) }
    val context = LocalContext.current

    MapLibreMapView(
        modifier = modifier,
        styleUrl = MapStyles.globalStyleUrl(context),
        onMapReady = onMapReady,
        onStyleLoaded = { map, style ->
            ensureTracksLayer(style)
            style.getLayerAs<LineLayer>(STYLE_TRACKS_LAYER)
                ?.setFilter(eq(get(TRACK_PACK_ID), literal(packId)))
            addSelectedTracksLayer(style)
            installTapHandler(map) { feature, position ->
                feature.getStringProperty(ITINERARY_ID)?.let { onItineraryTapped(it, position) }
            }
            loadedStyle = style
        },
    )

    LaunchedEffect(selectedItineraryId, loadedStyle) {
        loadedStyle?.getLayerAs<LineLayer>(SELECTED_LAYER)?.setFilter(
            if (selectedItineraryId == null) literal(false)
            else eq(get(ITINERARY_ID), literal(selectedItineraryId)),
        )
    }
}

/** Animates the camera to the pack's bounding box; no-op when bounds are missing. */
fun zoomToPack(map: MapLibreMap, pack: TrackPack) {
    val latMin = pack.latMin ?: return
    val latMax = pack.latMax ?: return
    val lonMin = pack.lonMin ?: return
    val lonMax = pack.lonMax ?: return
    map.animateCamera(
        CameraUpdateFactory.newLatLngBounds(boundsOf(latMin, latMax, lonMin, lonMax), 40),
    )
}

/** Animates the camera to the itinerary's bounding box; no-op when bounds are missing. */
fun zoomToItinerary(map: MapLibreMap, itinerary: Itinerary) {
    val latMin = itinerary.latMin ?: return
    val latMax = itinerary.latMax ?: return
    val lonMin = itinerary.lonMin ?: return
    val lonMax = itinerary.lonMax ?: return
    map.animateCamera(
        CameraUpdateFactory.newLatLngBounds(boundsOf(latMin, latMax, lonMin, lonMax), 60),
    )
}

/** Animates the camera onto a point object (step, waypoint). */
fun zoomToPoint(map: MapLibreMap, lat: Double, lon: Double) {
    map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 11.0))
}

/**
 * Red overlay on the style's tracks source, initially matching nothing.
 * Selection changes only swap its filter — no data is reloaded.
 */
private fun addSelectedTracksLayer(style: Style) {
    val layer = LineLayer(SELECTED_LAYER, "tracks").apply {
        sourceLayer = "tracks"
        setFilter(literal(false))
        setProperties(
            lineColor("#FF0000"),
            lineJoin(LINE_JOIN_ROUND),
            lineCap(LINE_CAP_ROUND),
            lineWidth(
                interpolate(
                    linear(), zoom(),
                    stop(0, 1f),
                    stop(5, 3f),
                    stop(8, 9f),
                )
            ),
        )
    }
    style.addLayer(layer)
}

/** Reports the track feature under a tap and the tap's view position, if any. */
private fun installTapHandler(
    map: MapLibreMap,
    onTapped: (feature: Feature, position: Offset) -> Unit,
) {
    map.addOnMapClickListener { latLng ->
        val point = map.projection.toScreenLocation(latLng)
        val features = map.queryRenderedFeatures(
            RectF(point.x - 16, point.y - 16, point.x + 16, point.y + 16),
            STYLE_TRACKS_LAYER,
        )
        val feature = features.firstOrNull()
        if (feature != null) {
            onTapped(feature, Offset(point.x, point.y))
            true
        } else {
            false
        }
    }
}
