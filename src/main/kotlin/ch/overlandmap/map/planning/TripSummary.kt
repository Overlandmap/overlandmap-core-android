package ch.overlandmap.map.planning

/** A run of consecutive traversals shown as one row of the trip breakdown (web `plan-route-tiles.ts`). */
data class TripLeg(
    val id: String,
    val mode: RouteMode,
    val name: String?,
    val countries: List<String>,
    val segmentIds: List<String>,
    val startIndex: Int,
    val endIndex: Int,
    val lengthMeters: Double?,
    /** Border-post node crossed just before this leg, if any. */
    val borderPostNode: String?,
    /** The `border_post` document of that node. */
    val borderPostId: String?,
    /** The name of the leg's first segment as the graph has it (for a ferry, the full name). */
    val segmentName: String? = null,
)

data class TripSummary(
    val distanceMeters: Double?,
    val countries: List<String>,
    val ferryCrossings: Int,
    val legs: List<TripLeg>,
)

object TripSummaryBuilder {

    /**
     * @param lengths per-traversal lengths (trimmed ends); the graph lengths when null.
     * @param borderPostNodes nodes that are known border posts, which split land legs.
     */
    fun summary(
        trip: PlannedTrip,
        graph: RouteGraph,
        lengths: List<Double>? = null,
        borderPostNodes: Set<String> = emptySet(),
    ): TripSummary {
        val nodes = RoutePlanner.vertices(trip, graph)
        val legs = ArrayList<TripLeg>()
        val orderedCountries = ArrayList<String>()
        var ferryCrossings = 0

        for ((index, id) in trip.segmentIds.withIndex()) {
            val segment = graph.segments[id]
            val mode = segment?.effectiveMode ?: RouteMode.LAND
            val countries = ArrayList<String>()
            for (code in segment?.countries ?: emptyList()) if (code !in countries) countries += code
            for (code in countries) if (code !in orderedCountries) orderedCountries += code
            val length = if (lengths != null) lengths[index] else segment?.lengthMeters

            if (mode == RouteMode.FERRY) {
                val before = if (index > 0) graph.segments[trip.segmentIds[index - 1]] else null
                if (before == null || before.mode != RouteMode.FERRY || before.name != segment?.name) ferryCrossings += 1
            }

            val crossesPost = nodes?.let { it[index] in borderPostNodes } ?: false
            val previous = legs.lastOrNull()
            if (mode == RouteMode.LAND && countries.size == 1 && previous != null && previous.mode == RouteMode.LAND &&
                previous.countries.size == 1 && previous.countries[0] == countries[0] && !crossesPost
            ) {
                val total = previous.lengthMeters
                legs[legs.size - 1] = previous.copy(
                    segmentIds = previous.segmentIds + id,
                    endIndex = index,
                    lengthMeters = if (total != null && length != null) total + length else null,
                )
            } else {
                var name: String? = null
                val raw = segment?.name
                if (mode != RouteMode.LAND && raw != null) {
                    val prefix = if (mode == RouteMode.FERRY) "ferry" else "shipping"
                    name = if (raw.lowercase().startsWith("$prefix ")) raw.drop(prefix.length + 1).trim() else raw
                }
                legs += TripLeg(
                    id = "${trip.id}:$index:$id",
                    mode = mode,
                    name = name,
                    countries = countries,
                    segmentIds = listOf(id),
                    startIndex = index,
                    endIndex = index,
                    lengthMeters = length,
                    borderPostNode = if (crossesPost && index > 0) nodes?.get(index) else null,
                    borderPostId = if (crossesPost && index > 0) nodes?.let { graph.borderPostId(it[index]) } else null,
                    segmentName = segment?.name?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
        }
        return TripSummary(
            distanceMeters = if (lengths != null) lengths.sum() else trip.distanceMeters,
            countries = orderedCountries,
            ferryCrossings = ferryCrossings,
            legs = legs,
        )
    }
}

/**
 * The days [meters] take at [kmPerDay], as an exact fraction (callers round it for display, so that
 * the fractions of the legs add up to the trip's); null without a distance.
 */
fun tripDays(meters: Double?, kmPerDay: Double): Double? =
    meters?.takeIf { kmPerDay > 0 }?.let { it / 1000.0 / kmPerDay }
