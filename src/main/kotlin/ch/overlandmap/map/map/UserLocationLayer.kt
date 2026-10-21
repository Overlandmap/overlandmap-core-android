package ch.overlandmap.map.map

import ch.overlandmap.map.geo.Coordinate
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val USER_SOURCE = "user-location"
private const val USER_LAYER = "user-location-dot"

/** Adds the (initially empty) current-location dot, on top of everything added so far, to a MapLibre style. */
fun addUserLocationLayer(style: Style) {
    if (style.getSource(USER_SOURCE) != null) return
    style.addSource(GeoJsonSource(USER_SOURCE, FeatureCollection.fromFeatures(emptyList<Feature>())))
    style.addLayer(
        CircleLayer(USER_LAYER, USER_SOURCE).withProperties(
            circleColor("#007AFF"), circleRadius(8f), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f),
        )
    )
}

/** Moves the dot to [location]; nothing is drawn while it is null. */
fun updateUserLocation(style: Style, location: Coordinate?) {
    val features = if (location == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)))
    style.getSourceAs<GeoJsonSource>(USER_SOURCE)?.setGeoJson(FeatureCollection.fromFeatures(features))
}
