package ch.overlandmap.map.units

import ch.overlandmap.map.geo.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import java.util.Locale
import org.junit.Test

/**
 * Vectors from iOS `UnitFormatterTests`, with one deliberate difference: the current iOS
 * formatter groups thousands with the locale (commit "fix the number formatter"), so
 * 1230 m is "1,230 m" in en_US. The iOS test file still holds the pre-change
 * expectations ("1230 m"); the formatter code is the reference. The locale is pinned
 * here so the tests do not depend on the machine running them.
 */
class UnitFormatterTest {
    private val metric = UnitFormatter(UnitFormat.METRIC, Locale.US)
    private val imperial = UnitFormatter(UnitFormat.IMPERIAL, Locale.US)

    // ── Altitude ────────────────────────────────────────────────────────────

    @Test
    fun metersMetricAreWholeNumbers() {
        assertEquals("1,230 m", metric.meters(1230.4))
        assertEquals("", metric.meters(null))
        assertEquals("1,230", metric.meters(1230.4, withUnit = false))
    }

    @Test
    fun shortImperialHeightsKeepOneDecimal() {
        assertEquals("9.8 ft", imperial.meters(3.0))
    }

    @Test
    fun longImperialHeightsAreWholeFeet() {
        assertEquals("4,035 ft", imperial.meters(1230.0))
        assertEquals("404 ft", imperial.meters(123.0)) // 403.54 rounds up
    }

    @Test
    fun veryLargeFootValuesAreGrouped() {
        assertEquals("16,404 ft", imperial.meters(5000.0))
        assertTrue(imperial.meters(3048.0).contains(","))
    }

    @Test
    fun roundingIsHalfAwayFromZeroLikeSwift() {
        // Kotlin's round() is half-even and would give "2 m".
        assertEquals("3 m", metric.meters(2.5))
        assertEquals("-3 m", metric.meters(-2.5))
    }

    @Test
    fun groupingFollowsTheLocale() {
        val french = UnitFormatter(UnitFormat.METRIC, Locale.FRANCE).meters(5500.0)
        assertEquals("5500", french.filter { it.isDigit() })
        assertNotEquals("5500 m", french)
        assertEquals("5.500 m", UnitFormatter(UnitFormat.METRIC, Locale.GERMANY).meters(5500.0))
    }

    // ── Distance ────────────────────────────────────────────────────────────

    @Test
    fun distanceSwitchesPrecisionAroundTenKilometres() {
        assertEquals("123 km", metric.distanceKm(123.0))
        assertEquals("5.6 km", metric.distanceKm(5.55))
        assertEquals("5 km", metric.distanceKm(5.0)) // trailing ".0" dropped
        assertEquals("76 mi", imperial.distanceKm(123.0))
    }

    @Test
    fun wholeNumbersOptionRoundsShortDistances() {
        assertEquals("6 km", metric.distanceKm(5.55, wholeNumbers = true))
        assertEquals("3 mi", imperial.distanceKm(5.0, wholeNumbers = true))
        assertEquals("", metric.distanceKm(null))
    }

    @Test
    fun distanceInMeters() {
        assertEquals("100 m", metric.distanceMeters(100.0))
        assertEquals("328 ft", imperial.distanceMeters(100.0))
    }

    @Test
    fun navigationDistanceThresholds() {
        // Metric
        assertEquals("25 km", metric.navigationDistance(25000.0))
        assertEquals("11 km", metric.navigationDistance(10600.0))
        assertEquals("10 km", metric.navigationDistance(10000.0))
        assertEquals("2.5 km", metric.navigationDistance(2540.0))
        assertEquals("1 km", metric.navigationDistance(1000.0))
        assertEquals("1 km", metric.navigationDistance(960.0)) // rounds up to 1000 m
        assertEquals("900 m", metric.navigationDistance(850.0))
        assertEquals("100 m", metric.navigationDistance(120.0))
        assertEquals("99 m", metric.navigationDistance(99.4))
        assertEquals("7 m", metric.navigationDistance(7.0))
        assertEquals("0 m", metric.navigationDistance(0.0))
        assertEquals("", metric.navigationDistance(null))
        // Imperial
        assertEquals("16 mi", imperial.navigationDistance(25000.0))
        assertEquals("1.6 mi", imperial.navigationDistance(2540.0))
        assertEquals("1 mi", imperial.navigationDistance(1609.344))
        assertEquals("5,200 ft", imperial.navigationDistance(1590.0)) // just under a mile: feet to the nearest 100
        assertEquals("1 mi", imperial.navigationDistance(1605.0)) // 5266 ft rounds to 5300: a mile
        assertEquals("1,000 ft", imperial.navigationDistance(300.0))
        assertEquals("100 ft", imperial.navigationDistance(31.0))
        assertEquals("30 ft", imperial.navigationDistance(9.0))
    }

    @Test
    fun markupKilometersDiffersFromDistanceKm() {
        // Metric shows the value as written (no rounding), unlike distanceKm.
        assertEquals("12 km", metric.markupKilometers(12.0))
        assertEquals("3.1 mi", imperial.markupKilometers(5.0))
        assertEquals("76 mi", imperial.markupKilometers(123.0))
        assertEquals("km", metric.distanceUnitWord)
        assertEquals("miles", imperial.distanceUnitWord)
    }

    @Test
    fun speedConverts() {
        assertEquals("80 km/h", metric.speed(80.0))
        assertEquals("50 mph", imperial.speed(80.0))
    }

    @Test
    fun millimetresConvertToInchesThenFeet() {
        assertEquals("250 mm", metric.millimeters(250.0))
        assertEquals("9.8 in", imperial.millimeters(250.0))
        assertEquals("7 ft", imperial.millimeters(2000.0))
    }

    // ── Temperature ─────────────────────────────────────────────────────────

    @Test
    fun temperatureConverts() {
        assertEquals("34 °C", metric.temperature(34.0))
        assertEquals("93 °F", imperial.temperature(34.0))
        assertEquals("32 °F", imperial.temperature(0.0))
        assertEquals("-40 °F", imperial.temperature(-40.0))
        assertEquals("34°", metric.temperature(34.0, withUnit = false))
    }

    @Test
    fun markupTemperatureHandlesRanges() {
        assertEquals("34 °C", metric.markupTemperature("34"))
        assertEquals("93°F", imperial.markupTemperature("34"))
        assertEquals("68 - 86°F", imperial.markupTemperature("20-30"))
    }

    // ── Coordinates ─────────────────────────────────────────────────────────

    @Test
    fun formatsDecimalDegrees() {
        val f = UnitFormatter(UnitFormat(gpsFormat = GpsFormat.DD), Locale.US)
        assertEquals("34.05972, 77.51383", f.coordinate(Coordinate(34.05972, 77.51383)))
    }

    @Test
    fun formatsDegreesMinutes() {
        val f = UnitFormatter(UnitFormat(gpsFormat = GpsFormat.DDMM), Locale.US)
        assertEquals("34° 03.583' N 77° 30.830' E", f.coordinate(34.05972, 77.51383))
    }

    @Test
    fun formatsDegreesMinutesSeconds() {
        val f = UnitFormatter(UnitFormat(gpsFormat = GpsFormat.DDMMSS), Locale.US)
        assertEquals("34° 3' 35'' N, 77° 30' 50'' E", f.coordinate(34.05972, 77.51383))
    }

    @Test
    fun southernAndWesternHemispheresGetTheRightLetters() {
        val f = UnitFormatter(UnitFormat(gpsFormat = GpsFormat.DDMM), Locale.US)
        val text = f.coordinate(-33.9, -70.6)
        assertTrue(text, text.contains("S"))
        assertTrue(text, text.contains("W"))
    }

    @Test
    fun coordinateFormattingIgnoresTheDeviceLocale() {
        // A French device must not turn "34.05972" into "34,05972".
        val f = UnitFormatter(UnitFormat(gpsFormat = GpsFormat.DD), Locale.FRANCE)
        assertEquals("34.05972, 77.51383", f.coordinate(34.05972, 77.51383))
    }

    @Test
    fun unitsSampleMatchesTheIosScreen() {
        // The sample on the Units screen (Everest), as rendered by iOS.
        assertEquals("100 m", metric.distanceMeters(100.0))
        assertEquals("30 km", metric.distanceKm(30.0))
        assertEquals("8,848 m", metric.meters(8848.0))
        assertEquals("27.98710, 86.92590", metric.coordinate(27.9871, 86.9259))
        assertEquals("20 °C", metric.temperature(20.0))
    }

    // ── Preferences model ───────────────────────────────────────────────────

    @Test
    fun deviceDefaultFollowsTheRegion() {
        assertEquals(false, UnitFormat.deviceDefault("US").distanceMetric)
        assertEquals(false, UnitFormat.deviceDefault("us").heightMetric)
        assertEquals(true, UnitFormat.deviceDefault("FR").distanceMetric)
        assertEquals(true, UnitFormat.deviceDefault(null).distanceMetric)
    }

    @Test
    fun unitAxesAreIndependent() {
        val mixed = UnitFormatter(UnitFormat(heightMetric = false, distanceMetric = true), Locale.US)
        assertEquals("4,035 ft", mixed.meters(1230.0))
        assertEquals("123 km", mixed.distanceKm(123.0))
        assertEquals("34 °C", mixed.temperature(34.0))
    }
}
