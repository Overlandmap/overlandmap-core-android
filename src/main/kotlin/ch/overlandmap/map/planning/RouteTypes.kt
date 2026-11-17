package ch.overlandmap.map.planning

import ch.overlandmap.map.geo.Coordinate
import org.json.JSONArray
import org.json.JSONObject

/*
 * Types of the planning mode (iOS `OverlandMapKit/Planning/RouteTypes.swift`), a port of the web
 * app's `route-planner.ts`: the routing graph is undirected, built from the segments of
 * `routes.pmtiles.graph.json`. Trips use the web `PlannedRoute` JSON, so they can be moved
 * between the applications.
 */

/** A `[longitude, latitude]` pair; it is a two-element JSON array in graph files and stored trips. */
data class RoutePoint(val longitude: Double, val latitude: Double) {
    fun toCoordinate() = Coordinate(latitude, longitude)

    fun toJson(): JSONArray = JSONArray().put(longitude).put(latitude)

    companion object {
        fun fromJson(array: JSONArray?): RoutePoint? {
            if (array == null || array.length() < 2) return null
            val lon = array.optDouble(0, Double.NaN)
            val lat = array.optDouble(1, Double.NaN)
            return if (lon.isNaN() || lat.isNaN()) null else RoutePoint(lon, lat)
        }
    }
}

enum class RouteMode(val raw: String) {
    LAND("land"), FERRY("ferry"), SHIPPING("shipping");

    companion object {
        fun fromRaw(raw: String?): RouteMode? = entries.firstOrNull { it.raw == raw }
    }
}

enum class TravelDirection(val raw: String) {
    FORWARD("forward"), REVERSE("reverse");

    companion object {
        fun fromRaw(raw: String?): TravelDirection? = entries.firstOrNull { it.raw == raw }
    }
}

/** One edge of the routing graph. */
data class PlannerSegment(
    val id: String,
    val startNode: String,
    val endNode: String,
    val lengthMeters: Double,
    val name: String? = null,
    val startCoordinate: RoutePoint? = null,
    val endCoordinate: RoutePoint? = null,
    val mode: RouteMode? = null,
    val countries: List<String>? = null,
    val overlanding: Int? = null,
) {
    val effectiveMode: RouteMode get() = mode ?: RouteMode.LAND

    /** Routing preference only; physical distances remain [lengthMeters]. */
    val routingCost: Double
        get() = lengthMeters * when (mode) {
            RouteMode.SHIPPING -> 3.0
            RouteMode.FERRY -> 2.0
            else -> 1.0
        }
}

/** The `routes.pmtiles.graph.json` file. */
class PlannerGraphFile(
    val archiveSha256: String?,
    val geometrySha256: String?,
    val borderPostNodes: Map<String, String>,
    val segments: List<PlannerSegment>,
) {
    companion object {
        fun parse(json: String): PlannerGraphFile {
            val root = JSONObject(json)
            val array = root.getJSONArray("segments")
            val segments = ArrayList<PlannerSegment>(array.length())
            for (i in 0 until array.length()) {
                val s = array.getJSONObject(i)
                val countries = s.optJSONArray("countries")?.let { list ->
                    List(list.length()) { list.optString(it) }
                }
                segments += PlannerSegment(
                    id = s.optString("id"),
                    startNode = s.optString("start_node"),
                    endNode = s.optString("end_node"),
                    lengthMeters = s.optDouble("length_m", Double.NaN),
                    name = if (s.isNull("name")) null else s.optString("name"),
                    startCoordinate = RoutePoint.fromJson(s.optJSONArray("start_coordinate")),
                    endCoordinate = RoutePoint.fromJson(s.optJSONArray("end_coordinate")),
                    mode = RouteMode.fromRaw(s.optString("mode")),
                    countries = countries,
                    overlanding = if (s.has("overlanding") && !s.isNull("overlanding")) s.optInt("overlanding") else null,
                )
            }
            val posts = LinkedHashMap<String, String>()
            root.optJSONObject("border_post_nodes")?.let { obj ->
                obj.keys().forEach { node -> posts[node] = obj.optString(node) }
            }
            return PlannerGraphFile(
                archiveSha256 = root.optString("archive_sha256").ifEmpty { null },
                geometrySha256 = root.optString("geometry_sha256").ifEmpty { null },
                borderPostNodes = posts,
                segments = segments,
            )
        }
    }
}

/**
 * A flag placed by the user on a segment. Consecutive flags define the trip; the planner computes
 * the shortest connection between them.
 */
data class PlanWaypoint(
    val id: String,
    val coordinate: RoutePoint,
    val segmentId: String,
    /** Occurrence of [segmentId] in the trip's segment list. */
    val segmentIndex: Int = 0,
    val direction: TravelDirection? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("coordinate", coordinate.toJson())
        put("segmentId", segmentId)
        put("segmentIndex", segmentIndex)
        direction?.let { put("direction", it.raw) }
    }

    companion object {
        fun fromJson(o: JSONObject): PlanWaypoint? {
            val id = o.optString("id").ifEmpty { return null }
            val coordinate = RoutePoint.fromJson(o.optJSONArray("coordinate")) ?: return null
            val segment = o.optString("segmentId").ifEmpty { return null }
            return PlanWaypoint(
                id = id,
                coordinate = coordinate,
                segmentId = segment,
                segmentIndex = o.optInt("segmentIndex", 0),
                direction = TravelDirection.fromRaw(o.optString("direction")),
            )
        }
    }
}

/**
 * A trip: an ordered list of segment traversals between two nodes, plus the flags that produced it.
 * Same shape as the web `PlannedRoute`. [waypoints] is null for a trip that was never edited with
 * flags (older web trips); the planner then derives terminal flags from its path.
 */
data class PlannedTrip(
    val id: String,
    val name: String,
    val segmentIds: List<String> = emptyList(),
    val startNode: String? = null,
    val endNode: String? = null,
    val distanceMeters: Double? = null,
    val waypoints: List<PlanWaypoint>? = emptyList(),
    /** `#rrggbb`. */
    val color: String? = null,
    val visible: Boolean? = null,
    /** Distance covered in a day, to count the days of the trip; the planner's default when unset. */
    val kmPerDay: Double? = null,
    /**
     * The day the trip starts, if the user set one. It belongs to the trip, not to a flag: it stays
     * when the start flag is moved, and the next flag is the start when the first one is removed.
     */
    val startDate: java.time.LocalDate? = null,
) {
    val isVisible: Boolean get() = visible ?: true
    val displayColor: String get() = color ?: DEFAULT_COLOR

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("segmentIds", JSONArray(segmentIds))
        startNode?.let { put("startNode", it) }
        endNode?.let { put("endNode", it) }
        distanceMeters?.let { put("distance_m", it) }
        waypoints?.let { list -> put("waypoints", JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }) }
        color?.let { put("color", it) }
        visible?.let { put("visible", it) }
        kmPerDay?.let { put("kmPerDay", it) }
        startDate?.let { put("startDate", it.toString()) }
    }.toString()

    companion object {
        const val DEFAULT_COLOR = "#ff0000"
        const val DEFAULT_KM_PER_DAY = 500.0

        /** Colors are stored as `#rrggbb` only. */
        fun isValidColor(value: String): Boolean =
            value.length == 7 && value[0] == '#' && value.drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        fun fromJson(json: String): PlannedTrip? = runCatching {
            val o = JSONObject(json)
            val ids = o.optJSONArray("segmentIds")?.let { a -> List(a.length()) { a.optString(it) } } ?: emptyList()
            val waypoints = o.optJSONArray("waypoints")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(PlanWaypoint::fromJson) }
            }
            PlannedTrip(
                id = o.getString("id"),
                name = o.optString("name"),
                segmentIds = ids,
                startNode = if (o.isNull("startNode")) null else o.optString("startNode"),
                endNode = if (o.isNull("endNode")) null else o.optString("endNode"),
                distanceMeters = if (o.has("distance_m") && !o.isNull("distance_m")) o.optDouble("distance_m") else null,
                waypoints = waypoints,
                color = if (o.isNull("color")) null else o.optString("color"),
                visible = if (o.has("visible") && !o.isNull("visible")) o.optBoolean("visible") else null,
                kmPerDay = if (o.has("kmPerDay") && !o.isNull("kmPerDay")) o.optDouble("kmPerDay") else null,
                startDate = o.optString("startDate").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() },
            )
        }.getOrNull()
    }
}

/** Which parts of the network the planner may use. All on by default. */
data class RoutingRestrictions(
    val avoidUnsafe: Boolean = true,
    val avoidRestricted: Boolean = true,
    val excludeClosedBorders: Boolean = true,
) {
    companion object {
        val DEFAULT = RoutingRestrictions()
    }
}

enum class BlockedCountryReason { UNSAFE, FORBIDDEN, RESTRICTED }
