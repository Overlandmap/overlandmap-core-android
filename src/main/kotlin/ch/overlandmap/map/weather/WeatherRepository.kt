package ch.overlandmap.map.weather

import android.util.Log
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** What the repository needs from the network: a GET with the bearer token. Replaceable in tests. */
fun interface WeatherHttp {
    /** Returns the body of a 200 response; throws [WeatherException] otherwise. */
    suspend fun get(url: String, token: String): String
}

/** WeatherKit REST over [HttpURLConnection]. */
object WeatherKitHttp : WeatherHttp {
    override suspend fun get(url: String, token: String): String = withContext(Dispatchers.IO) {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OverlandMap/1.0")
            }
        } catch (e: Exception) {
            throw WeatherException(WeatherFailure.NETWORK, cause = e)
        }
        try {
            when (val code = connection.responseCode) {
                200 -> connection.inputStream.bufferedReader().use { it.readText() }
                // 401/403: the token or the WeatherKit configuration; 429: the API quota is spent.
                else -> {
                    Log.w("WeatherKit", "HTTP $code for $url")
                    throw WeatherException(WeatherFailure.SERVICE, "HTTP $code")
                }
            }
        } catch (e: WeatherException) {
            throw e
        } catch (e: Exception) {
            throw WeatherException(WeatherFailure.NETWORK, cause = e)
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * Forecasts and climate normals from Apple WeatherKit's REST API (iOS `WeatherService`, with the
 * REST endpoints the Flutter app uses in place of the Swift SDK). The JWT comes from the
 * `weatherKitToken` Firebase callable and is reused until shortly before it expires; historical
 * climate is cached on disk per 0.01° cell, as on iOS.
 */
class WeatherRepository(
    private val cacheDir: File,
    private val http: WeatherHttp = WeatherKitHttp,
    private val fetchToken: suspend () -> String = { callTokenFunction() },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var token: String? = null
    private var tokenExpiryMs = 0L

    private suspend fun bearer(): String {
        token?.let { if (now() < tokenExpiryMs) return it }
        val fresh = try {
            fetchToken()
        } catch (e: WeatherException) {
            throw e
        } catch (e: Exception) {
            throw WeatherException(WeatherFailure.NETWORK, cause = e)
        }
        token = fresh
        // Refresh a minute early; a token without a readable expiry is kept for ten minutes.
        tokenExpiryMs = WeatherParsing.jwtExpiry(fresh)?.let { it * 1000 - 60_000 } ?: (now() + 10 * 60_000)
        return fresh
    }

    private fun check(lat: Double, lon: Double) {
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) throw WeatherException(WeatherFailure.NO_COORDINATE)
    }

    private fun coords(lat: Double, lon: Double) = String.format(Locale.US, "%.4f/%.4f", lat, lon)

    /** Ten days of daily and the next hours of hourly forecast at a coordinate. */
    suspend fun forecast(lat: Double, lon: Double, stepName: String): LocationForecast {
        check(lat, lon)
        val offset = WeatherParsing.offsetFor(lon)
        val tz = java.net.URLEncoder.encode(WeatherParsing.timezoneName(offset), "UTF-8")
        val url = "$BASE_V1/weather/en/${coords(lat, lon)}?dataSets=forecastDaily,forecastHourly&timezone=$tz"
        return WeatherParsing.forecast(http.get(url, bearer()), stepName, offset)
    }

    /** The twelve monthly climate normals at a coordinate, from the disk cache when present. */
    suspend fun climate(lat: Double, lon: Double): List<MonthlyClimate> {
        check(lat, lon)
        val file = File(cacheDir, "climate_cache/${WeatherParsing.cacheKey(lat, lon)}")
        if (file.isFile) {
            WeatherParsing.decodeClimate(file.readText())?.let { return it }
        }
        val url = "$BASE_V2/statistics/monthly/${coords(lat, lon)}?dataSets=precipitation,temperature&start=1&end=12"
        val months = WeatherParsing.monthlyClimate(http.get(url, bearer()))
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(WeatherParsing.encodeClimate(lat, lon, months, now()))
        }
        return months
    }

    /** The Apple Weather mark and legal page; null when unreachable (the tab then shows the text mark). */
    suspend fun attribution(): WeatherAttribution? = try {
        WeatherParsing.attribution(http.get("$BASE_V1/attribution/en", bearer()))
    } catch (e: WeatherException) {
        null
    }

    companion object {
        private const val BASE_V1 = "https://weatherkit.apple.com/api/v1"
        private const val BASE_V2 = "https://weatherkit.apple.com/api/v2"

        /** Apple's own page, used when the attribution endpoint can't be reached. */
        const val LEGAL_PAGE = "https://weatherkit.apple.com/legal-attribution.html"

        private suspend fun callTokenFunction(): String {
            val data = FirebaseFunctions.getInstance().getHttpsCallable("weatherKitToken")
                .call(mapOf("debug" to "none")).await().getData() as? Map<*, *>
            return data?.get("token") as? String
                ?: throw WeatherException(WeatherFailure.SERVICE, (data?.get("error") as? String) ?: "No token")
        }
    }
}
