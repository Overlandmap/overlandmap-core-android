package ch.overlandmap.map.units

import ch.overlandmap.map.geo.Coordinate
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

/**
 * Renders measurements according to a [UnitFormat]. A faithful port of iOS
 * `UnitFormatter` (itself a port of the Dart helpers in `formatted_text.dart`),
 * including its rounding quirks: descriptions were authored against them, so a
 * "more correct" conversion would make existing content read wrong.
 *
 * Every function returns `""` for a null input.
 */
class UnitFormatter(
    val format: UnitFormat,
    private val locale: Locale = Locale.getDefault(),
) {

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Integer with locale-aware grouping separators (5,500 / 5.500 / 5 500). */
    private fun grouped(value: Int): String =
        NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = true }.format(value)

    /** Swift's `.rounded()`: half away from zero (Kotlin's `round` is half-even). */
    private fun roundAway(x: Double): Double = if (x < 0) -floor(-x + 0.5) else floor(x + 0.5)

    private fun roundInt(x: Double): Int = roundAway(x).toInt()

    /** Drops a trailing `.0` so whole values read as "5 km" rather than "5.0 km". */
    private fun trim(value: Double): String =
        if (value == roundAway(value)) value.toInt().toString()
        else String.format(Locale.ROOT, "%.1f", value)

    // ── Altitude / height ───────────────────────────────────────────────────

    /**
     * Metric: whole metres. Imperial: below 10 m keeps one decimal (so small drops stay
     * meaningful), above that whole feet.
     */
    fun meters(meters: Double?, withUnit: Boolean = true): String {
        if (meters == null) return ""
        if (format.heightMetric) {
            val text = grouped(roundInt(meters))
            return if (withUnit) "$text m" else text
        }
        if (meters < 10) {
            val feet = roundAway(meters * 10 * FEET_PER_METRE) / 10
            return if (withUnit) "${trim(feet)} ft" else trim(feet)
        }
        val text = grouped(roundInt(meters * FEET_PER_METRE))
        return if (withUnit) "$text ft" else text
    }

    // ── Distance ────────────────────────────────────────────────────────────

    /**
     * Metric: one decimal at or below 10 km, whole numbers above. Imperial mirrors it in
     * miles.
     */
    fun distanceKm(kilometers: Double?, wholeNumbers: Boolean = false): String {
        if (kilometers == null) return ""
        if (format.distanceMetric) {
            return if (wholeNumbers || kilometers > 10) "${grouped(roundInt(kilometers))} km"
            else "${trim(roundAway(kilometers * 10) / 10)} km"
        }
        val miles = kilometers * MILES_PER_KM
        return if (wholeNumbers || miles > 10) "${grouped(roundInt(miles))} mi"
        else "${trim(roundAway(miles * 10) / 10)} mi"
    }

    fun distanceMeters(meters: Double?): String {
        if (meters == null) return ""
        if (format.distanceMetric) return "${grouped(roundInt(meters))} m"
        return "${grouped(roundInt(meters * 3.281))} ft"
    }

    /**
     * Navigation distance, coarser as it grows. Metric: above 10 km whole kilometres; from 1 to 10 km
     * one decimal; from 100 m to 1 km metres rounded to 100; under 100 m whole metres. Imperial uses
     * the same rule with miles and feet (the 100 and the 1 being feet and miles).
     */
    fun navigationDistance(meters: Double?): String {
        if (meters == null) return ""
        if (format.distanceMetric) {
            if (meters >= 1000.0) return longDistance(meters / 1000.0, "km")
            val rounded = if (meters >= 100.0) roundInt(meters / 100.0) * 100 else roundInt(meters)
            return if (rounded >= 1000) longDistance(1.0, "km") else "${grouped(rounded)} m"
        }
        val miles = meters / METRES_PER_MILE
        if (miles >= 1.0) return longDistance(miles, "mi")
        val feet = meters * FEET_PER_METRE
        val rounded = if (feet >= 100.0) roundInt(feet / 100.0) * 100 else roundInt(feet)
        return if (rounded >= FEET_PER_MILE) longDistance(1.0, "mi") else "${grouped(rounded)} ft"
    }

    /** [value] km or miles: whole numbers above 10, else one decimal. */
    private fun longDistance(value: Double, unit: String): String =
        if (value > 10) "${grouped(roundInt(value))} $unit" else "${trim(roundAway(value * 10) / 10)} $unit"

    /**
     * The `{->km N}` markup substitution. Distinct from [distanceKm]: metric is the value
     * as written, and imperial switches to one decimal only *below* 10, not at it.
     */
    fun markupKilometers(kilometers: Double): String {
        if (format.distanceMetric) return "${trim(kilometers)} km"
        if (kilometers > 10) return "${grouped(roundInt(kilometers * MILES_PER_KM))} mi"
        return "${trim(roundAway(kilometers * 10 * MILES_PER_KM) / 10)} mi"
    }

    /** The bare `{km}` unit word. */
    val distanceUnitWord: String get() = if (format.distanceMetric) "km" else "miles"

    fun speed(kilometersPerHour: Double?, withUnit: Boolean = true): String {
        if (kilometersPerHour == null) return ""
        if (format.distanceMetric) {
            val text = grouped(roundInt(kilometersPerHour))
            return if (withUnit) "$text km/h" else text
        }
        val text = grouped(roundInt(kilometersPerHour * MILES_PER_KM))
        return if (withUnit) "$text mph" else text
    }

    fun millimeters(millimeters: Double?, withUnit: Boolean = true): String {
        if (millimeters == null) return ""
        if (format.distanceMetric) {
            val text = grouped(roundInt(millimeters))
            return if (withUnit) "$text mm" else text
        }
        if (millimeters < 1000) {
            val inches = roundAway(millimeters / 2.54) / 10
            return if (withUnit) "${trim(inches)} in" else trim(inches)
        }
        val text = grouped(roundInt(millimeters / 1000 * FEET_PER_METRE))
        return if (withUnit) "$text ft" else text
    }

    // ── Temperature ─────────────────────────────────────────────────────────

    fun temperature(celsius: Double?, withUnit: Boolean = true): String {
        if (celsius == null) return ""
        if (format.temperatureCelsius) {
            val value = roundInt(celsius)
            return if (withUnit) "$value °C" else "$value°"
        }
        val value = roundInt(celsius * 9 / 5 + 32)
        return if (withUnit) "$value °F" else "$value°"
    }

    /**
     * The `{°C …}` markup substitution, which accepts a range as well as a single value
     * (climate copy uses `{°C 20-30}`).
     */
    fun markupTemperature(raw: String): String {
        if (format.temperatureCelsius) return "$raw °C"
        val match = TEMPERATURE_RANGE.find(raw) ?: return raw
        val low = match.groupValues[1].toDoubleOrNull() ?: return raw
        val lowF = roundInt(low * 9 / 5 + 32)
        val high = match.groupValues[2].toDoubleOrNull()
        if (high != null) return "$lowF - ${roundInt(high * 9 / 5 + 32)}°F"
        return "$lowF°F"
    }

    // ── Coordinates ─────────────────────────────────────────────────────────

    fun coordinate(latitude: Double, longitude: Double): String = when (format.gpsFormat) {
        GpsFormat.DD -> String.format(Locale.ROOT, "%.5f, %.5f", latitude, longitude)
        GpsFormat.DDMM ->
            "${degreesMinutes(latitude, isLatitude = true)} ${degreesMinutes(longitude, isLatitude = false)}"
        GpsFormat.DDMMSS ->
            "${degreesMinutesSeconds(latitude, true)}, ${degreesMinutesSeconds(longitude, false)}"
    }

    fun coordinate(coordinate: Coordinate): String =
        coordinate(coordinate.latitude, coordinate.longitude)

    private fun degreesMinutes(value: Double, isLatitude: Boolean): String {
        val absolute = abs(value)
        val degrees = absolute.toInt()
        val minutes = (absolute - degrees) * 60
        return String.format(Locale.ROOT, "%d° %06.3f' %s", degrees, minutes, hemisphere(value, isLatitude))
    }

    private fun degreesMinutesSeconds(value: Double, isLatitude: Boolean): String {
        val absolute = abs(value)
        val degrees = absolute.toInt()
        val minutesTotal = (absolute - degrees) * 60
        val minutes = minutesTotal.toInt()
        val seconds = (minutesTotal - minutes) * 60
        return String.format(
            Locale.ROOT, "%d° %d' %.0f'' %s", degrees, minutes, seconds, hemisphere(value, isLatitude),
        )
    }

    private fun hemisphere(value: Double, isLatitude: Boolean): String =
        if (isLatitude) (if (value >= 0) "N" else "S") else (if (value >= 0) "E" else "W")

    private companion object {
        const val FEET_PER_METRE = 3.28084
        const val METRES_PER_MILE = 1609.344
        const val FEET_PER_MILE = 5280
        const val MILES_PER_KM = 0.621371
        val TEMPERATURE_RANGE = Regex("""(\d+)\s?-?\s?(\d*)""")
    }
}
