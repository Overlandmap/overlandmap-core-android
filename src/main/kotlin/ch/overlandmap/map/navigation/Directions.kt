package ch.overlandmap.map.navigation

import ch.overlandmap.map.geo.Coordinate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** A turn-by-turn maneuver of a road route (iOS `RoadManeuver`). */
data class RoadManeuver(
    val instruction: String,
    val type: String? = null,
    val modifier: String? = null,
    val location: Coordinate,
)

data class RoadStep(
    val id: String,
    val instruction: String,
    val maneuver: RoadManeuver,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val name: String? = null,
)

/** The driving route to the start of an itinerary. */
data class RoadRoute(
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Double,
    val coordinates: List<Coordinate>,
    val steps: List<RoadStep>,
)

class DirectionsException(message: String) : Exception(message)

fun interface DirectionsRouting {
    suspend fun fetchRoute(origin: Coordinate, destination: Coordinate, language: String, accessToken: String): RoadRoute
}

/** Mapbox Directions API client (iOS `MapboxDirectionsService`). */
object MapboxDirectionsService : DirectionsRouting {

    fun url(origin: Coordinate, destination: Coordinate, language: String, accessToken: String): String {
        val coords = String.format(
            Locale.US, "%.6f,%.6f;%.6f,%.6f",
            origin.longitude, origin.latitude, destination.longitude, destination.latitude,
        )
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "https://api.mapbox.com/directions/v5/mapbox/driving/$coords" +
            "?steps=true&geometries=geojson&overview=full&language=${enc(language)}&access_token=${enc(accessToken)}"
    }

    override suspend fun fetchRoute(
        origin: Coordinate,
        destination: Coordinate,
        language: String,
        accessToken: String,
    ): RoadRoute = withContext(Dispatchers.IO) {
        val connection = URL(url(origin, destination, language, accessToken)).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            val code = connection.responseCode
            if (code != 200) {
                val body = runCatching { connection.errorStream?.bufferedReader()?.readText() }.getOrNull()
                throw DirectionsException(body ?: "HTTP $code")
            }
            parse(connection.inputStream.bufferedReader().readText(), destination)
        } finally {
            connection.disconnect()
        }
    }

    /** Parses a Directions response; [destination] stands in for a maneuver without a location. */
    fun parse(json: String, destination: Coordinate): RoadRoute {
        val root = JSONObject(json)
        val route = root.optJSONArray("routes")?.optJSONObject(0)
        if (root.optString("code") != "Ok" || route == null) throw DirectionsException("No driving route found to the start point.")

        val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates").let { a ->
            (0 until a.length()).mapNotNull { i ->
                val pair = a.optJSONArray(i)
                if (pair != null && pair.length() >= 2) Coordinate(pair.getDouble(1), pair.getDouble(0)) else null
            }
        }
        val steps = ArrayList<RoadStep>()
        val legs = route.optJSONArray("legs")
        if (legs != null) {
            for (l in 0 until legs.length()) {
                val legSteps = legs.getJSONObject(l).optJSONArray("steps") ?: continue
                for (s in 0 until legSteps.length()) {
                    val step = legSteps.getJSONObject(s)
                    val maneuver = step.getJSONObject("maneuver")
                    val at = maneuver.optJSONArray("location")?.takeIf { it.length() >= 2 }
                        ?.let { Coordinate(it.getDouble(1), it.getDouble(0)) } ?: destination
                    val instruction = maneuver.optString("instruction")
                    steps += RoadStep(
                        id = "step-${steps.size}",
                        instruction = instruction,
                        maneuver = RoadManeuver(
                            instruction, maneuver.optString("type").ifEmpty { null },
                            maneuver.optString("modifier").ifEmpty { null }, at,
                        ),
                        distanceMeters = step.optDouble("distance"),
                        durationSeconds = step.optDouble("duration"),
                        name = step.optString("name").ifEmpty { null },
                    )
                }
            }
        }
        return RoadRoute(route.optDouble("distance"), route.optDouble("duration"), coordinates, steps)
    }
}
