package ch.overlandmap.map.ui

import ch.overlandmap.map.model.Itinerary
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.CoordinateBounds
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapboxMap

/**
 * Camera helpers for the itinerary screen's Mapbox map — the Mapbox-SDK
 * counterparts of [zoomToPopupObject] / [zoomToItinerary] (which use MapLibre).
 */
fun zoomToPopupObjectMapbox(map: MapboxMap, kind: MapPopupKind) {
    when (kind) {
        is MapPopupKind.OfItinerary -> zoomToItineraryMapbox(map, kind.itinerary)
        is MapPopupKind.OfStep -> {
            val lat = kind.step.lat ?: return
            val lon = kind.step.lon ?: return
            zoomToPointMapbox(map, lat, lon)
        }
        is MapPopupKind.OfWaypoint -> {
            val lat = kind.waypoint.lat ?: return
            val lon = kind.waypoint.lon ?: return
            zoomToPointMapbox(map, lat, lon)
        }
        is MapPopupKind.Buy -> Unit
    }
}

fun zoomToItineraryMapbox(map: MapboxMap, itinerary: Itinerary) {
    val latMin = itinerary.latMin ?: return
    val latMax = itinerary.latMax ?: return
    val lonMin = itinerary.lonMin ?: return
    val lonMax = itinerary.lonMax ?: return
    val camera = map.cameraForCoordinateBounds(
        CoordinateBounds(Point.fromLngLat(lonMin, latMin), Point.fromLngLat(lonMax, latMax)),
        EdgeInsets(60.0, 60.0, 60.0, 60.0),
        null,
        null,
    )
    map.setCamera(camera)
}

fun zoomToPointMapbox(map: MapboxMap, lat: Double, lon: Double) {
    map.setCamera(CameraOptions.Builder().center(Point.fromLngLat(lon, lat)).zoom(11.0).build())
}
