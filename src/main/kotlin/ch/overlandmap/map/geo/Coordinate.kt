package ch.overlandmap.map.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A WGS-84 point. Port of iOS `Coordinate` (OverlandMapKit/Models/TrackPack.swift);
 * the formulas and the Earth radius (6371 km) are identical so both apps give the
 * same distances and bearings.
 */
data class Coordinate(val latitude: Double, val longitude: Double) {

    /** Great-circle distance in kilometres. */
    fun distanceKm(other: Coordinate): Double {
        val dLat = Math.toRadians(other.latitude - latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(latitude)) * cos(Math.toRadians(other.latitude)) *
            sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_KM * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Great-circle distance in metres. */
    fun distanceMeters(other: Coordinate): Double = distanceKm(other) * 1000.0

    /** Initial great-circle bearing to [other], in degrees [0, 360). */
    fun bearing(other: Coordinate): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val degrees = Math.toDegrees(atan2(y, x))
        return (degrees + 360.0) % 360.0
    }

    companion object {
        const val EARTH_RADIUS_KM = 6371.0

        private val COMPASS = listOf(
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW",
        )

        /** 16-wind compass-rose name for a bearing in degrees. */
        fun cardinalDirection(bearing: Double): String {
            val normalized = ((bearing % 360.0) + 360.0) % 360.0
            val index = floor((normalized + 11.25) / 22.5).toInt() % 16
            return COMPASS[index]
        }
    }
}
