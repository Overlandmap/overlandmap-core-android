package ch.overlandmap.map.ui

import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.CoordinateBounds
import com.mapbox.maps.EdgeInsets
import com.mapbox.maps.MapView
import com.mapbox.maps.MapboxMap
import com.mapbox.maps.plugin.animation.MapAnimationOptions
import com.mapbox.maps.plugin.animation.camera
import com.mapbox.maps.plugin.animation.easeTo

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

fun zoomToPointMapbox(map: MapboxMap, lat: Double, lon: Double, zoom: Double = 11.0) {
    map.setCamera(CameraOptions.Builder().center(Point.fromLngLat(lon, lat)).zoom(zoom).build())
}

/**
 * Eases the camera to show both [a] and its neighbour [b] as tightly as possible
 * (the itinerary auto-zoom). With no neighbour (an end step) it centres on [a]
 * at a fixed close zoom. The move is animated so the view shifts progressively.
 */
fun fitStepsMapbox(mapView: MapView, a: ItineraryStep, b: ItineraryStep?) {
    val aLat = a.lat ?: return
    val aLon = a.lon ?: return
    val bLat = b?.lat
    val bLon = b?.lon
    val camera = if (bLat != null && bLon != null) {
        mapView.mapboxMap.cameraForCoordinateBounds(
            CoordinateBounds(
                Point.fromLngLat(minOf(aLon, bLon), minOf(aLat, bLat)),
                Point.fromLngLat(maxOf(aLon, bLon), maxOf(aLat, bLat)),
            ),
            EdgeInsets(80.0, 80.0, 80.0, 80.0),
            null,
            null,
        )
    } else {
        CameraOptions.Builder().center(Point.fromLngLat(aLon, aLat)).zoom(12.0).build()
    }
    mapView.camera.easeTo(camera, MapAnimationOptions.Builder().duration(600L).build())
}
