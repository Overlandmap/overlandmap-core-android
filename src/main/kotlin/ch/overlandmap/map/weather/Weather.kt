package ch.overlandmap.map.weather

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale

/*
 * Weather models and the parsing of WeatherKit's REST responses (iOS WeatherService.swift: the
 * same fields, with the REST payloads instead of the Swift SDK's types). Temperatures are Celsius,
 * precipitation millimetres, wind km/h.
 */

/** One day of a forecast; [dateMs] is the start of that day at the site, as an instant. */
data class DayForecast(
    val dateMs: Long,
    val condition: String,
    val highCelsius: Double,
    val lowCelsius: Double,
    val precipitationMm: Double,
    val windKmh: Double,
)

data class HourForecast(
    val dateMs: Long,
    val condition: String,
    val temperatureCelsius: Double,
    val precipitationMm: Double,
    val windKmh: Double,
)

data class LocationForecast(
    val stepName: String,
    val daily: List<DayForecast>,
    val hourly: List<HourForecast>,
    /** The fixed UTC offset the days were rolled up in, for labelling them. */
    val offset: ZoneOffset,
)

/** Mean monthly climate of a place (month 1..12). */
data class MonthlyClimate(
    val month: Int,
    val minTempCelsius: Double,
    val maxTempCelsius: Double,
    val precipitationMm: Double,
)

/** The Apple Weather mark and the legal page that must accompany WeatherKit data. */
data class WeatherAttribution(
    val lightLogoUrl: String?,
    val darkLogoUrl: String?,
    val legalPageUrl: String,
)

enum class WeatherFailure { NO_COORDINATE, NETWORK, SERVICE, INVALID_DATA }

class WeatherException(val failure: WeatherFailure, message: String? = null, cause: Throwable? = null) :
    Exception(message ?: failure.name, cause)

object WeatherParsing {

    const val MAX_DAYS = 10
    const val MAX_HOURS = 48

    /**
     * The fixed zone WeatherKit rolls days up in for a longitude: whole hours from solar time. The
     * REST API needs a time zone for daily forecasts and the step's own isn't known, so this
     * `Etc/GMT±N` approximation stands in (days may start up to a few hours off near zone borders).
     */
    fun offsetFor(longitude: Double): ZoneOffset =
        ZoneOffset.ofHours(Math.round(longitude / 15.0).toInt().coerceIn(-12, 14))

    /** The IANA name of [offset] (`Etc/GMT-5` is UTC+5: the sign is inverted). */
    fun timezoneName(offset: ZoneOffset): String {
        val hours = offset.totalSeconds / 3600
        return when {
            hours == 0 -> "Etc/GMT"
            hours > 0 -> "Etc/GMT-$hours"
            else -> "Etc/GMT+${-hours}"
        }
    }

    private fun instant(text: String?): Long? = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()

    private fun JSONObject.dbl(key: String): Double? = if (has(key) && !isNull(key)) optDouble(key).takeIf { !it.isNaN() } else null

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

    /** Parses a `/weather` response holding `forecastDaily` and/or `forecastHourly`. */
    fun forecast(json: String, stepName: String, offset: ZoneOffset): LocationForecast {
        val root = try { JSONObject(json) } catch (e: Exception) { throw WeatherException(WeatherFailure.INVALID_DATA, cause = e) }
        val days = root.optJSONObject("forecastDaily")?.optJSONArray("days")?.objects().orEmpty().mapNotNull { d ->
            val start = instant(d.optString("forecastStart")) ?: return@mapNotNull null
            DayForecast(
                dateMs = start,
                condition = d.optString("conditionCode", "Clear"),
                highCelsius = d.dbl("temperatureMax") ?: return@mapNotNull null,
                lowCelsius = d.dbl("temperatureMin") ?: return@mapNotNull null,
                precipitationMm = d.dbl("precipitationAmount") ?: 0.0,
                windKmh = d.optJSONObject("daytimeForecast")?.dbl("windSpeed") ?: d.dbl("windSpeed") ?: 0.0,
            )
        }.take(MAX_DAYS)
        val hours = root.optJSONObject("forecastHourly")?.optJSONArray("hours")?.objects().orEmpty().mapNotNull { h ->
            HourForecast(
                dateMs = instant(h.optString("forecastStart")) ?: return@mapNotNull null,
                condition = h.optString("conditionCode", "Clear"),
                temperatureCelsius = h.dbl("temperature") ?: return@mapNotNull null,
                precipitationMm = h.dbl("precipitationAmount") ?: 0.0,
                windKmh = h.dbl("windSpeed") ?: 0.0,
            )
        }.take(MAX_HOURS)
        return LocationForecast(stepName, days, hours, offset)
    }

    /**
     * Parses `/statistics/monthly` (v2): each month has `temperature.{min,max}` and
     * `precipitation.averageAmount`, as the Flutter app reads it. Twelve months are required.
     */
    fun monthlyClimate(json: String): List<MonthlyClimate> {
        val root = try { JSONObject(json) } catch (e: Exception) { throw WeatherException(WeatherFailure.INVALID_DATA, cause = e) }
        val months = root.optJSONArray("months")?.objects().orEmpty().mapNotNull { m ->
            val month = m.optInt("month", -1).takeIf { it in 1..12 } ?: return@mapNotNull null
            val temperature = m.optJSONObject("temperature")
            val precipitation = m.optJSONObject("precipitation")
            MonthlyClimate(
                month = month,
                minTempCelsius = temperature?.dbl("min") ?: temperature?.dbl("averageLow") ?: return@mapNotNull null,
                maxTempCelsius = temperature?.dbl("max") ?: temperature?.dbl("averageHigh") ?: return@mapNotNull null,
                precipitationMm = precipitation?.dbl("averageAmount") ?: 0.0,
            )
        }.sortedBy { it.month }
        if (months.map { it.month } != (1..12).toList()) throw WeatherException(WeatherFailure.INVALID_DATA)
        return months
    }

    /** Parses `/attribution/{language}`; the logo keys carry a scale suffix (`logoLight@2x`). */
    fun attribution(json: String): WeatherAttribution? {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val legal = o.optString("legalPageURL").takeIf { it.isNotEmpty() } ?: return null
        fun logo(prefix: String) = listOf("@2x", "@3x", "@1x").firstNotNullOfOrNull { s ->
            o.optString(prefix + s).takeIf { it.isNotEmpty() }
        }
        return WeatherAttribution(logo("logoLight"), logo("logoDark"), legal)
    }

    // ── Climate cache (JSON file per ~1 km cell, like iOS) ──────────────────

    fun cacheKey(lat: Double, lon: Double): String =
        "climate_" + String.format(Locale.US, "%.2f_%.2f", lat, lon) + ".json"

    fun encodeClimate(lat: Double, lon: Double, months: List<MonthlyClimate>, fetchedAtMs: Long): String =
        JSONObject()
            .put("latitude", lat).put("longitude", lon).put("fetchedAt", fetchedAtMs)
            .put(
                "months",
                JSONArray(months.map {
                    JSONObject().put("month", it.month).put("minTempCelsius", it.minTempCelsius)
                        .put("maxTempCelsius", it.maxTempCelsius).put("precipitationMM", it.precipitationMm)
                }),
            ).toString()

    fun decodeClimate(json: String): List<MonthlyClimate>? = runCatching {
        JSONObject(json).getJSONArray("months").objects().map {
            MonthlyClimate(
                it.getInt("month"), it.getDouble("minTempCelsius"),
                it.getDouble("maxTempCelsius"), it.getDouble("precipitationMM"),
            )
        }.takeIf { it.size == 12 }
    }.getOrNull()

    // ── JWT ─────────────────────────────────────────────────────────────────

    /** The `exp` claim (epoch seconds) of a JWT, or null when unreadable. */
    fun jwtExpiry(token: String): Long? = runCatching {
        val payload = token.split('.')[1]
        val bytes = java.util.Base64.getUrlDecoder().decode(payload)
        JSONObject(String(bytes, Charsets.UTF_8)).getLong("exp")
    }.getOrNull()
}

/** Presentation helpers shared by the tab and its tests. */
object WeatherFormat {
    fun displayTemp(celsius: Double, useCelsius: Boolean) = if (useCelsius) celsius else celsius * 9 / 5 + 32

    fun temp(celsius: Double, useCelsius: Boolean): String =
        "${Math.round(displayTemp(celsius, useCelsius))}°"

    fun tempRange(low: Double, high: Double, useCelsius: Boolean) = "${temp(low, useCelsius)}/${temp(high, useCelsius)}"

    fun displayPrecip(mm: Double, metric: Boolean) = if (metric) mm else mm / 25.4

    fun precip(mm: Double, metric: Boolean): String =
        if (metric) "${Math.round(mm)} mm" else String.format(Locale.US, "%.1f in", mm / 25.4)

    /** Whether a precipitation figure is worth showing (iOS hides anything up to 0.1 mm). */
    fun hasPrecip(mm: Double) = mm > 0.1

    /** The chart's temperature axis: the data's span plus 5 degrees each side. */
    fun chartTempRange(months: List<MonthlyClimate>, useCelsius: Boolean): ClosedFloatingPointRange<Double> {
        val temps = months.flatMap { listOf(displayTemp(it.minTempCelsius, useCelsius), displayTemp(it.maxTempCelsius, useCelsius)) }
        return ((temps.minOrNull() ?: 0.0) - 5)..((temps.maxOrNull() ?: 30.0) + 5)
    }

    /** Rain-type conditions read blue, clear-sky ones red (iOS `conditionColor`). */
    enum class Tone { RAIN, SUN, NEUTRAL }

    fun tone(conditionCode: String): Tone = when (conditionCode) {
        "Drizzle", "Rain", "HeavyRain", "SunShowers", "IsolatedThunderstorms", "ScatteredThunderstorms",
        "StrongStorms", "Thunderstorms", "Hail", "Sleet", "Snow", "Flurries", "SunFlurries", "WintryMix",
        "Blizzard", "BlowingSnow", "FreezingDrizzle", "FreezingRain", "HeavySnow" -> Tone.RAIN
        "Clear", "MostlyClear", "Hot" -> Tone.SUN
        else -> Tone.NEUTRAL
    }
}
