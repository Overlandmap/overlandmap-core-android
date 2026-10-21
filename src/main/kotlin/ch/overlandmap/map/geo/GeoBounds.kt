package ch.overlandmap.map.geo

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * A latitude/longitude box. Port of iOS `GeoBounds`. Anti-meridian spanning is
 * deliberately not handled (no track pack crosses it), as on iOS.
 */
data class GeoBounds(
    val latMin: Double,
    val latMax: Double,
    val lonMin: Double,
    val lonMax: Double,
) {
    val centre: Coordinate
        get() = Coordinate((latMin + latMax) / 2, (lonMin + lonMax) / 2)

    fun contains(c: Coordinate): Boolean =
        c.latitude in latMin..latMax && c.longitude in lonMin..lonMax

    /** Grown by [km] on every side (contributed waypoints must fall within pack bounds + 10 km). */
    fun expanded(byKm: Double): GeoBounds {
        val dLat = byKm / 111.0
        val midLat = (latMin + latMax) / 2
        val dLon = byKm / (111.0 * max(0.01, cos(Math.toRadians(midLat))))
        return GeoBounds(latMin - dLat, latMax + dLat, lonMin - dLon, lonMax + dLon)
    }

    /**
     * Grown by a fraction of its own span on every side, for a padded zoom-to-extent.
     * A degenerate (zero-span) axis gets a small fixed buffer so it still frames.
     */
    fun padded(fraction: Double = 0.1): GeoBounds {
        val latSpan = latMax - latMin
        val lonSpan = lonMax - lonMin
        val dLat = if (latSpan > 0) latSpan * fraction else 0.01
        val dLon = if (lonSpan > 0) lonSpan * fraction else 0.01
        return GeoBounds(latMin - dLat, latMax + dLat, lonMin - dLon, lonMax + dLon)
    }

    companion object {
        /** The box enclosing [coords], widening a degenerate axis by ±0.005°. Null when empty. */
        fun containing(coords: List<Coordinate>): GeoBounds? {
            val first = coords.firstOrNull() ?: return null
            var latMin = first.latitude
            var latMax = first.latitude
            var lonMin = first.longitude
            var lonMax = first.longitude
            for (c in coords.drop(1)) {
                latMin = min(latMin, c.latitude)
                latMax = max(latMax, c.latitude)
                lonMin = min(lonMin, c.longitude)
                lonMax = max(lonMax, c.longitude)
            }
            if (latMin == latMax) {
                latMin -= 0.005
                latMax += 0.005
            }
            if (lonMin == lonMax) {
                lonMin -= 0.005
                lonMax += 0.005
            }
            return GeoBounds(latMin, latMax, lonMin, lonMax)
        }

        /** The smallest box containing all [boxes], or null when there are none. */
        fun union(boxes: List<GeoBounds>): GeoBounds? {
            val first = boxes.firstOrNull() ?: return null
            return boxes.drop(1).fold(first) { r, b ->
                GeoBounds(
                    min(r.latMin, b.latMin), max(r.latMax, b.latMax),
                    min(r.lonMin, b.lonMin), max(r.lonMax, b.lonMax),
                )
            }
        }
    }
}
