package ch.overlandmap.map.map

import ch.overlandmap.map.geo.Coordinate
import com.mapbox.geojson.Feature
import com.mapbox.geojson.FeatureCollection
import com.mapbox.geojson.Point
import com.mapbox.maps.Style
import com.mapbox.maps.extension.style.layers.addLayer
import com.mapbox.maps.extension.style.layers.generated.circleLayer
import com.mapbox.maps.extension.style.sources.addSource
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource
import com.mapbox.maps.extension.style.sources.generated.geoJsonSource
import com.mapbox.maps.extension.style.sources.getSourceAs

private const val USER_SOURCE = "user-location"
private const val USER_LAYER = "user-location-dot"

/** Adds the (initially empty) current-location dot, on top of everything added so far, to a Mapbox style. */
fun addMapboxUserLocationLayer(style: Style) {
    if (style.styleSourceExists(USER_SOURCE)) return
    style.addSource(geoJsonSource(USER_SOURCE) { featureCollection(FeatureCollection.fromFeatures(emptyList<Feature>())) })
    style.addLayer(
        circleLayer(USER_LAYER, USER_SOURCE) {
            circleColor("#007AFF"); circleRadius(8.0); circleStrokeColor("#FFFFFF"); circleStrokeWidth(3.0)
        }
    )
}

/** Moves the dot to [location]; nothing is drawn while it is null. */
fun updateMapboxUserLocation(style: Style, location: Coordinate?) {
    val features = if (location == null) emptyList() else listOf(Feature.fromGeometry(Point.fromLngLat(location.longitude, location.latitude)))
    style.getSourceAs<GeoJsonSource>(USER_SOURCE)?.featureCollection(FeatureCollection.fromFeatures(features))
}
