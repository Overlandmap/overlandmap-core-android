package ch.overlandmap.map.ui.world

import android.graphics.PointF
import android.graphics.RectF
import ch.overlandmap.map.data.routes.RouteDataSource
import ch.overlandmap.map.data.routes.TripDisplay
import ch.overlandmap.map.data.routes.TripPlannerState
import ch.overlandmap.map.map.LocalTileServer
import ch.overlandmap.map.planning.RouteMode
import ch.overlandmap.map.planning.RoutePoint
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineDasharray
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.PropertyFactory.visibility
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * The route network and the trips on the Overlanding map (iOS `OverlandingRoutes.swift`).
 * Both are ports of the web `route-map.ts`.
 */

/** A route segment tapped on the Overlanding map (from the `segments` layer of the route tiles). */
data class RouteSegmentSelection(
    val id: String,
    val name: String?,
    val mode: RouteMode,
    /** ADM0_A3 codes. */
    val countries: List<String>,
    val overlanding: Int?,
    val lengthKilometers: Double?,
)

/**
 * A tap in planning mode: the nearest route segment and the place that was tapped. [onTrip] is
 * set when the point is on the line of the trip being edited, where a flag is inserted rather than
 * appended.
 */
data class PlanTap(val segmentId: String, val coordinate: RoutePoint, val onTrip: Boolean = false)

/** What the Overlanding map shows of the route network and the trips. */
data class OverlandingRoutes(
    val showRoutes: Boolean = true,
    val showTrips: Boolean = true,
    val planning: Boolean = false,
    val selectedSegmentId: String? = null,
    /** Segments emphasised above the trips, e.g. the leg tapped in the planner list. */
    val highlightedSegmentIds: List<String> = emptyList(),
    /**
     * The trip emphasised on the map and whose flags are shown: the trip being edited in planning
     * mode, the tapped trip otherwise.
     */
    val highlightedTripId: String? = null,
)

/** What the map reports back about the routes and trips. */
class OverlandingRouteActions(
    val onSelectSegment: (RouteSegmentSelection) -> Unit = {},
    /** A trip line was tapped (normal mode). */
    val onSelectTrip: (String) -> Unit = {},
    /** The id of the visible trip drawn on a middle segment, if any. */
    val tripForSegment: (String) -> String? = { null },
    /** A tap (or long press) in planning mode: inserts a flag on the trip's line, else appends one. */
    val onPlanTap: (PlanTap) -> Unit = {},
    /**
     * A drag started on the trip's line: inserts a flag there and returns its id, so the map can
     * go on dragging it as it would an existing flag. Null when nothing was inserted.
     */
    val onInsertFlag: (PlanTap) -> String? = { null },
    /**
     * Any tap on the map, first. True when it was only used to dismiss the keyboard, and must do
     * nothing else.
     */
    val onMapTap: () -> Boolean = { false },
    /** Normal mode: a long press on a route starts a new trip with its first flag there. */
    val onStartTrip: (PlanTap) -> Unit = {},
    val onMoveFlag: (flagId: String, PlanTap) -> Unit = { _, _ -> },
    /** A flag was pressed and held without moving: ask whether to delete it. */
    val onLongPressFlag: (String) -> Unit = {},
    /** A flag is being dragged and is now over the [PlanTap]; called continuously. */
    val onPreviewFlag: (flagId: String, PlanTap) -> Unit = { _, _ -> },
    /** A drag ended where no segment can take the flag. */
    val onCancelPreview: () -> Unit = {},
)

/** Ids shared by the layer installer and the tap handler. */
internal object RouteLayerIds {
    const val SOURCE = "om-routes"
    const val SELECTED = "om-route-selected"
    const val LAND_CASING = "om-route-land-casing"
    const val LAND = "om-route-land"
    const val FERRY = "om-route-ferry"
    const val SHIPPING = "om-route-shipping"
    const val POINTS = "om-route-points"
    const val TRIP_SOURCE = "om-trips"

    // Trips are drawn twice: segments in the middle are highlighted in the route tiles themselves
    // (by ID), the cut first and last segments come from a small GeoJSON.
    const val TILE_HALO = "om-trip-tiles-halo"
    const val TILE_CASING = "om-trip-tiles-casing"
    const val TILE_LINE = "om-trip-tiles-line"
    const val TILE_POINTS = "om-trip-tiles-points"
    const val TRIP_HALO = "om-trips-halo"
    const val TRIP_CASING = "om-trips-casing"
    const val TRIP_LINE = "om-trips-line"
    const val TRIP_POINTS = "om-trips-points"
    const val LEG_HIGHLIGHT_CASING = "om-leg-highlight-casing"
    const val LEG_HIGHLIGHT = "om-leg-highlight"

    val routeLayers = listOf(LAND_CASING, LAND, FERRY, SHIPPING, POINTS)
    val tripLayers = listOf(TILE_HALO, TILE_CASING, TILE_LINE, TILE_POINTS, TRIP_HALO, TRIP_CASING, TRIP_LINE, TRIP_POINTS)

    /** Every layer and source this file adds, top first, for removal. */
    val allLayers = listOf(LEG_HIGHLIGHT, LEG_HIGHLIGHT_CASING) + tripLayers.reversed() + listOf(SELECTED) + routeLayers.reversed()
}

/** Installs, updates and queries the route and trip layers of the Overlanding map. */
internal object OverlandingRouteLayers {

    private const val EMPTY_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

    private fun expression(vararg parts: Any?): Expression = Expression.raw(JSONArray(parts.toList()).toString())

    /** `base` pixels at zoom 5–6, scaled down for the world view and up for close-ups. */
    private fun width(base: Double): Expression =
        expression("interpolate", listOf("linear"), listOf("zoom"), 1, base / 3, 4, base / 2, 5, base, 6, base, 10, base * 2)

    private fun visible(on: Boolean) = visibility(if (on) Property.VISIBLE else Property.NONE)

    private fun idEquals(id: String?) = expression("==", listOf("get", "id"), id ?: "")

    private fun idFilterParts(ids: Collection<String>): List<Any?> =
        if (ids.isEmpty()) listOf("==", listOf("get", "id"), "")
        else listOf("match", listOf("get", "id"), ids.toList(), true, false)

    /** A filter selecting segments by ID; matches nothing for an empty list. */
    fun idFilter(ids: Collection<String>): Expression = expression(*idFilterParts(ids).toTypedArray())

    /** Line colour by segment ID, grouped by colour: `["match", id, [ids], colour, …, fallback]`. */
    fun colorExpression(colorByID: Map<String, String>): Expression {
        if (colorByID.isEmpty()) return expression("literal", "#FF0000")
        val idsByColor = LinkedHashMap<String, MutableList<String>>()
        for ((id, color) in colorByID) idsByColor.getOrPut(color) { ArrayList() } += id
        val parts = arrayListOf<Any?>("match", listOf("get", "id"))
        for ((color, ids) in idsByColor) {
            parts.add(ids)
            parts.add(color)
        }
        parts.add("#FF0000")
        return expression("to-color", parts)
    }

    private fun lineLayer(
        id: String,
        source: String,
        sourceLayer: String?,
        filter: Expression?,
        cap: String,
        on: Boolean,
        color: Expression,
        width: Expression,
        dash: Array<Float>? = null,
    ) = LineLayer(id, source).also { layer ->
        sourceLayer?.let { layer.sourceLayer = it }
        filter?.let { layer.setFilter(it) }
        layer.setProperties(lineCap(cap), lineJoin(Property.LINE_JOIN_ROUND), visible(on), lineColor(color), lineWidth(width))
        dash?.let { layer.setProperties(lineDasharray(it)) }
    }

    private fun color(hex: String): Expression = expression("to-color", hex)

    /**
     * Adds the network source and its layers. Call between the border and border-post layers so the
     * routes sit above countries and borders and below the posts.
     */
    fun installRoutes(style: Style, routes: OverlandingRoutes) {
        if (style.getSource(RouteLayerIds.SOURCE) == null) {
            // The installed routes.pmtiles, served by the local tile server; the online tiles
            // until the `routes-pmtiles` asset has been downloaded.
            val offline = LocalTileServer.hasRoutes()
            val tiles = TileSet(
                "2.1.0",
                if (offline) "${LocalTileServer.baseUrl}/tiles/routes/{z}/{x}/{y}.mvt" else RouteDataSource.TILE_URL_TEMPLATE,
            ).apply {
                minZoom = 0f
                maxZoom = ((if (offline) LocalTileServer.routesMaxZoom() else null) ?: RouteDataSource.TILE_MAX_ZOOM).toFloat()
            }
            style.addSource(VectorSource(RouteLayerIds.SOURCE, tiles))
        }
        val on = routes.showRoutes || routes.planning
        val isLand = expression("==", listOf("get", "mode"), "land")
        val segments = "segments"
        // Yellow halo of the selected segment, under everything else.
        style.addLayer(
            lineLayer(
                RouteLayerIds.SELECTED, RouteLayerIds.SOURCE, segments, idEquals(routes.selectedSegmentId),
                Property.LINE_CAP_ROUND, on, color("#FACC15"), width(13.0),
            )
        )
        style.addLayer(
            lineLayer(
                RouteLayerIds.LAND_CASING, RouteLayerIds.SOURCE, segments, isLand,
                Property.LINE_CAP_ROUND, on, color("#000000"), width(7.0),
            )
        )
        style.addLayer(
            lineLayer(
                RouteLayerIds.LAND, RouteLayerIds.SOURCE, segments, isLand,
                Property.LINE_CAP_ROUND, on, color("#FFFFFF"), width(3.0),
            )
        )
        style.addLayer(
            lineLayer(
                RouteLayerIds.FERRY, RouteLayerIds.SOURCE, segments, expression("==", listOf("get", "mode"), "ferry"),
                Property.LINE_CAP_BUTT, on, color("#2563EB"), width(3.0), dash = arrayOf(3f, 2f),
            )
        )
        style.addLayer(
            lineLayer(
                RouteLayerIds.SHIPPING, RouteLayerIds.SOURCE, segments, expression("==", listOf("get", "mode"), "shipping"),
                Property.LINE_CAP_ROUND, on, color("#2563EB"), width(3.0), dash = arrayOf(0.2f, 2f),
            )
        )
        style.addLayer(
            CircleLayer(RouteLayerIds.POINTS, RouteLayerIds.SOURCE).apply {
                sourceLayer = segments
                setFilter(expression("==", listOf("geometry-type"), "Point"))
                setProperties(
                    visible(on),
                    circleColor(
                        expression(
                            "to-color",
                            listOf("match", listOf("get", "mode"), listOf("ferry", "shipping"), "#2563EB", "#FFFFFF"),
                        )
                    ),
                    circleRadius(width(3.0)),
                    circleStrokeColor("#000000"),
                    circleStrokeWidth(1.5f),
                )
            }
        )
    }

    /** Adds the trip layers on top of everything, as the web planner does. */
    fun installTrips(style: Style, routes: OverlandingRoutes) {
        if (style.getSource(RouteLayerIds.TRIP_SOURCE) == null) {
            style.addSource(GeoJsonSource(RouteLayerIds.TRIP_SOURCE, EMPTY_COLLECTION))
        }
        val on = routes.showTrips
        val none = idFilter(emptyList())
        val isPoint = expression("==", listOf("geometry-type"), "Point")
        val highlighted = expression("==", listOf("get", "active"), 1)
        val tripColor = expression("to-color", listOf("get", "color"))
        val tripSource = RouteLayerIds.TRIP_SOURCE
        val tiles = RouteLayerIds.SOURCE
        val segments = "segments"

        fun tilePoints(id: String, fill: Expression, stroke: String) = CircleLayer(id, tiles).apply {
            sourceLayer = segments
            setFilter(none)
            setProperties(
                visible(on), circleColor(fill), circleRadius(width(12.0)),
                circleStrokeColor(stroke), circleStrokeWidth(2f),
            )
        }

        // Bottom to top: halos, white casings, coloured lines, then single-point segments.
        style.addLayer(lineLayer(RouteLayerIds.TILE_HALO, tiles, segments, none, Property.LINE_CAP_ROUND, on, color("#FACC15"), width(24.0)))
        style.addLayer(lineLayer(RouteLayerIds.TRIP_HALO, tripSource, null, highlighted, Property.LINE_CAP_ROUND, on, color("#FACC15"), width(24.0)))
        style.addLayer(lineLayer(RouteLayerIds.TILE_CASING, tiles, segments, none, Property.LINE_CAP_ROUND, on, color("#FFFFFF"), width(18.0)))
        style.addLayer(lineLayer(RouteLayerIds.TRIP_CASING, tripSource, null, null, Property.LINE_CAP_ROUND, on, color("#FFFFFF"), width(18.0)))
        style.addLayer(lineLayer(RouteLayerIds.TILE_LINE, tiles, segments, none, Property.LINE_CAP_ROUND, on, color("#FF0000"), width(12.0)))
        style.addLayer(lineLayer(RouteLayerIds.TRIP_LINE, tripSource, null, null, Property.LINE_CAP_ROUND, on, tripColor, width(12.0)))
        style.addLayer(tilePoints(RouteLayerIds.TILE_POINTS, color("#FF0000"), "#FFFFFF"))
        style.addLayer(
            CircleLayer(RouteLayerIds.TRIP_POINTS, tripSource).apply {
                setFilter(isPoint)
                setProperties(
                    visible(on), circleColor(tripColor), circleRadius(width(12.0)),
                    circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                )
            }
        )
    }

    /** Cyan stripe over the given segments, above the trips. */
    fun installLegHighlight(style: Style, routes: OverlandingRoutes) {
        val filter = idFilter(routes.highlightedSegmentIds)
        style.addLayer(
            lineLayer(
                RouteLayerIds.LEG_HIGHLIGHT_CASING, RouteLayerIds.SOURCE, "segments", filter,
                Property.LINE_CAP_ROUND, true, color("#000000"), width(18.0),
            )
        )
        style.addLayer(
            lineLayer(
                RouteLayerIds.LEG_HIGHLIGHT, RouteLayerIds.SOURCE, "segments", filter,
                Property.LINE_CAP_ROUND, true, color("#00E5FF"), width(11.0),
            )
        )
    }

    fun updateLegHighlight(style: Style, ids: List<String>) {
        val filter = idFilter(ids)
        for (id in listOf(RouteLayerIds.LEG_HIGHLIGHT_CASING, RouteLayerIds.LEG_HIGHLIGHT)) {
            style.getLayerAs<LineLayer>(id)?.setFilter(filter)
        }
    }

    /**
     * Pushes the planner's trips to the map: the small GeoJSON of the cut end segments, and the ID
     * filters and colours of the tile layers that draw the rest.
     */
    fun updateTrips(style: Style, routes: OverlandingRoutes, planner: TripPlannerState) {
        val source = style.getSourceAs<GeoJsonSource>(RouteLayerIds.TRIP_SOURCE) ?: return
        source.setGeoJson(fragmentsJson(planner, routes.highlightedTripId))

        val colorByID = LinkedHashMap<String, String>()
        var highlightedIds: List<String> = emptyList()
        for (trip in planner.trips) {
            if (!trip.isVisible) continue
            val display = displayOf(trip.id, planner) ?: continue
            for (id in display.fullSegmentIds) colorByID[id] = trip.displayColor
            if (trip.id == routes.highlightedTripId) highlightedIds = display.fullSegmentIds
        }
        val filter = idFilter(colorByID.keys)
        val colors = colorExpression(colorByID)
        style.getLayerAs<LineLayer>(RouteLayerIds.TILE_CASING)?.setFilter(filter)
        style.getLayerAs<LineLayer>(RouteLayerIds.TILE_LINE)?.let {
            it.setFilter(filter)
            it.setProperties(lineColor(colors))
        }
        style.getLayerAs<CircleLayer>(RouteLayerIds.TILE_POINTS)?.let {
            it.setFilter(expression("all", idFilterParts(colorByID.keys), listOf("==", listOf("geometry-type"), "Point")))
            it.setProperties(circleColor(colors))
        }
        style.getLayerAs<LineLayer>(RouteLayerIds.TILE_HALO)?.setFilter(idFilter(highlightedIds))
    }

    /** The display to draw for a trip: the drag preview replaces the active trip's. */
    private fun displayOf(tripId: String, planner: TripPlannerState): TripDisplay? {
        if (tripId == planner.activeId && planner.previewDisplay != null) return planner.previewDisplay
        return planner.displays[tripId]
    }

    /** GeoJSON of the cut first and last segments of every visible trip. */
    fun fragmentsJson(planner: TripPlannerState, highlighted: String?): String {
        val features = JSONArray()
        for (trip in planner.trips) {
            if (!trip.isVisible) continue
            val display = displayOf(trip.id, planner) ?: continue
            for (fragment in display.fragments) {
                if (fragment.coordinates.isEmpty()) continue
                val geometry = JSONObject()
                if (fragment.coordinates.size == 1) {
                    geometry.put("type", "Point").put("coordinates", fragment.coordinates[0].toJson())
                } else {
                    geometry.put("type", "LineString")
                        .put("coordinates", JSONArray().also { a -> fragment.coordinates.forEach { a.put(it.toJson()) } })
                }
                features.put(
                    JSONObject()
                        .put("type", "Feature")
                        .put(
                            "properties",
                            JSONObject()
                                .put("id", trip.id)
                                .put("color", trip.displayColor)
                                .put("active", if (trip.id == highlighted) 1 else 0),
                        )
                        .put("geometry", geometry)
                )
            }
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    fun updateVisibility(style: Style, routes: OverlandingRoutes) {
        val routeVisibility = visible(routes.showRoutes || routes.planning)
        for (id in listOf(RouteLayerIds.SELECTED) + RouteLayerIds.routeLayers) style.getLayer(id)?.setProperties(routeVisibility)
        val tripVisibility = visible(routes.showTrips)
        for (id in RouteLayerIds.tripLayers) style.getLayer(id)?.setProperties(tripVisibility)
    }

    fun updateSelection(style: Style, segmentId: String?) {
        style.getLayerAs<LineLayer>(RouteLayerIds.SELECTED)?.setFilter(idEquals(segmentId))
    }

    /** Removes what [installRoutes], [installTrips] and [installLegHighlight] added. */
    fun remove(style: Style) {
        RouteLayerIds.allLayers.forEach { style.removeLayer(it) }
        style.removeSource(RouteLayerIds.TRIP_SOURCE)
        style.removeSource(RouteLayerIds.SOURCE)
    }

    // ── Diagnostics ─────────────────────────────────────────────────────────

    private val loggedDots = HashSet<String>()

    /**
     * Logs (tag `OverlandingRoutes`, warning) each feature that the trips' dot layers draw in the
     * current view, once: its layer, id and GeoJSON. These dots are single-point features, which
     * should not exist; the log says which segment or trip piece they are.
     */
    fun logRenderedDots(map: MapLibreMap) {
        val style = map.style ?: return
        val layers = listOf(RouteLayerIds.TILE_POINTS, RouteLayerIds.TRIP_POINTS).filter { style.getLayer(it) != null }
        if (layers.isEmpty()) return
        val view = RectF(0f, 0f, map.width, map.height)
        for (layer in layers) {
            for (feature in map.queryRenderedFeatures(view, layer)) {
                val json = feature.toJson()
                val key = layer + ":" + (feature.getStringProperty("id") ?: json.hashCode().toString())
                if (loggedDots.add(key)) android.util.Log.w("OverlandingRoutes", "Trip dot drawn by $layer: ${json.take(500)}")
            }
        }
    }

    // ── Queries ─────────────────────────────────────────────────────────────

    /** The segment selection for a rendered `segments` feature. */
    fun selection(feature: Feature): RouteSegmentSelection? {
        val id = feature.getStringProperty("id")?.takeIf { it.isNotEmpty() } ?: return null
        val codes = (feature.getStringProperty("countries") ?: feature.getStringProperty("country_code") ?: "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val kilometres = feature.numberProperty("segment_length_km")
            ?: feature.numberProperty("length_m")?.let { Math.round(it / 1000.0).toDouble() }
        return RouteSegmentSelection(
            id = id,
            name = feature.getStringProperty("name"),
            mode = RouteMode.fromRaw(feature.getStringProperty("mode")) ?: RouteMode.LAND,
            countries = codes,
            overlanding = feature.numberProperty("overlanding")?.toInt(),
            lengthKilometers = kilometres,
        )
    }

    private fun Feature.numberProperty(key: String): Double? {
        val element = properties()?.get(key) ?: return null
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isNumber) element.asDouble else null
    }

    /** The features of the route layers around [point] (screen pixels), within [halfSize] pixels. */
    fun queryRoutes(map: MapLibreMap, point: PointF, halfSize: Float): List<Feature> {
        val style = map.style ?: return emptyList()
        val layers = RouteLayerIds.routeLayers.filter { style.getLayer(it) != null }.toTypedArray()
        if (layers.isEmpty()) return emptyList()
        val box = RectF(point.x - halfSize, point.y - halfSize, point.x + halfSize, point.y + halfSize)
        return map.queryRenderedFeatures(box, *layers)
    }

    /** The route segment nearest to [point] among the rendered features, measured on screen. */
    fun nearestSegment(map: MapLibreMap, point: PointF, density: Float): String? {
        var best: String? = null
        var bestDistance = Float.POSITIVE_INFINITY
        for (feature in queryRoutes(map, point, 24f * density)) {
            val id = feature.getStringProperty("id")?.takeIf { it.isNotEmpty() } ?: continue
            val lines: List<List<Point>> = when (val geometry = feature.geometry()) {
                is LineString -> listOf(geometry.coordinates())
                is MultiLineString -> geometry.coordinates()
                is Point -> listOf(listOf(geometry))
                else -> emptyList()
            }
            for (line in lines) {
                var previous: PointF? = null
                for (coordinate in line) {
                    val projected = map.projection.toScreenLocation(LatLng(coordinate.latitude(), coordinate.longitude()))
                    val distance = previous?.let { distanceToSegment(point, it, projected) }
                        ?: hypot(point.x - projected.x, point.y - projected.y)
                    if (distance <= bestDistance) {
                        best = id
                        bestDistance = distance
                    }
                    previous = projected
                }
            }
        }
        return best
    }

    /**
     * The segment of [trip] whose drawn line is under [point], or null when the point is not on the
     * line of that trip (a trip's line is drawn by its own layers, so a nearby route that is not
     * part of the trip does not count).
     */
    fun tripSegmentAt(map: MapLibreMap, point: PointF, density: Float, trip: ch.overlandmap.map.planning.PlannedTrip): String? {
        val style = map.style ?: return null
        val half = 18f * density
        val box = RectF(point.x - half, point.y - half, point.x + half, point.y + half)
        fun hits(vararg ids: String): List<Feature> {
            val layers = ids.filter { style.getLayer(it) != null }.toTypedArray()
            return if (layers.isEmpty()) emptyList() else map.queryRenderedFeatures(box, *layers)
        }
        val segments = trip.segmentIds.toHashSet()
        val own = hits(RouteLayerIds.TRIP_LINE, RouteLayerIds.TRIP_POINTS).any { it.getStringProperty("id") == trip.id }
        val tile = hits(RouteLayerIds.TILE_LINE, RouteLayerIds.TILE_POINTS)
            .mapNotNull { it.getStringProperty("id") }.firstOrNull { it in segments }
        if (!own && tile == null) return null
        return nearestSegment(map, point, density)?.takeIf { it in segments } ?: tile
    }

    private fun distanceToSegment(p: PointF, a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val squared = dx * dx + dy * dy
        val ratio = if (squared == 0f) 0f else max(0f, min(1f, ((p.x - a.x) * dx + (p.y - a.y) * dy) / squared))
        return hypot(p.x - a.x - ratio * dx, p.y - a.y - ratio * dy)
    }
}
