package ch.overlandmap.map.planning

import java.util.PriorityQueue

/**
 * Port of the web `route-planner.ts`, `waypoint-routing.ts` and `routing-restrictions.ts`
 * (iOS `RoutePlanner`). The behavior is intentionally the same, including the cost model
 * (land ×1, ferry ×2, shipping ×3) and the direction dynamic program over the flags.
 */
object RoutePlanner {

    // ── Connectivity ────────────────────────────────────────────────────────

    fun isConnected(trip: PlannedTrip, graph: RouteGraph): Boolean {
        if (trip.segmentIds.isEmpty()) return trip.startNode == null && trip.endNode == null
        var node = trip.startNode ?: return false
        val end = trip.endNode ?: return false
        for (id in trip.segmentIds) {
            val edge = graph.segments[id] ?: return false
            node = when (node) {
                edge.startNode -> edge.endNode
                edge.endNode -> edge.startNode
                else -> return false
            }
        }
        return node == end
    }

    /** Nodes at each segment boundary in the trip's actual travel direction. */
    fun vertices(trip: PlannedTrip, graph: RouteGraph): List<String>? {
        if (!isConnected(trip, graph)) return null
        val start = trip.startNode ?: return emptyList()
        val nodes = arrayListOf(start)
        for (id in trip.segmentIds) {
            val edge = graph.segments[id] ?: return null
            nodes += if (edge.startNode == nodes.last()) edge.endNode else edge.startNode
        }
        return nodes
    }

    fun distanceMeters(trip: PlannedTrip, graph: RouteGraph): Double? {
        var total = 0.0
        for (id in trip.segmentIds) total += (graph.segments[id] ?: return null).lengthMeters
        return total
    }

    /** Whether the segments `start..end` and the nodes between them respect [restrictions]. */
    fun isAllowed(
        trip: PlannedTrip,
        graph: RouteGraph,
        restrictions: RoutingRestrictions,
        start: Int = 0,
        end: Int? = null,
    ): Boolean {
        val last = end ?: (trip.segmentIds.size - 1)
        val nodes = vertices(trip, graph) ?: return false
        if (last < start) return true
        if (start < 0 || last >= trip.segmentIds.size) return false
        val segmentsAllowed = (start..last).all { graph.segmentAllowed(trip.segmentIds[it], restrictions) }
        if (!segmentsAllowed) return false
        if (start + 1 > last) return true
        return ((start + 1)..last).all { graph.nodeAllowsTransition(nodes[it], restrictions) }
    }

    // ── Shortest path ───────────────────────────────────────────────────────

    private class HeapItem(val node: Int, val cost: Double)

    /**
     * Dijkstra from one node to several, never traversing [excluded] (the flag's own segment). One
     * search answers every target: it stops when all of them are settled. Works on integer indices
     * and the precomputed restriction tables. Targets that cannot be reached are absent from the
     * result.
     */
    internal fun shortestPaths(
        graph: RouteGraph,
        from: String,
        targets: Set<String>,
        excluded: String,
        restrictions: RoutingRestrictions,
    ): Map<String, List<String>> {
        val startIndex = graph.nodeIndex[from]
            // A node absent from the graph only connects to itself.
            ?: return if (from in targets) mapOf(from to emptyList()) else emptyMap()
        val allowance = graph.allowance(restrictions)
        if (!allowance.nodes[startIndex]) return emptyMap()
        val excludedIndex = graph.segmentIndex[excluded] ?: -1
        val remaining = targets.mapNotNull { graph.nodeIndex[it] }.filter { allowance.nodes[it] }.toMutableSet()
        if (remaining.isEmpty()) return emptyMap()

        val count = graph.nodeNames.size
        val distances = DoubleArray(count) { Double.POSITIVE_INFINITY }
        val previousNode = IntArray(count) { -1 }
        val previousSegment = IntArray(count) { -1 }
        val heap = PriorityQueue<HeapItem>(compareBy<HeapItem> { it.cost })
        distances[startIndex] = 0.0
        heap.add(HeapItem(startIndex, 0.0))
        val reached = ArrayList<Int>()

        while (true) {
            val current = heap.poll() ?: break
            if (current.cost != distances[current.node]) continue
            if (remaining.remove(current.node)) {
                reached += current.node
                if (remaining.isEmpty()) break
            }
            if (!allowance.nodes[current.node]) continue
            for (step in graph.steps[current.node]) {
                if (step.segment == excludedIndex || !allowance.segments[step.segment]) continue
                val cost = current.cost + step.cost
                if (cost < distances[step.to]) {
                    distances[step.to] = cost
                    previousNode[step.to] = current.node
                    previousSegment[step.to] = step.segment
                    heap.add(HeapItem(step.to, cost))
                }
            }
        }

        val result = HashMap<String, List<String>>()
        for (target in reached) {
            val path = ArrayList<String>()
            var node = target
            while (node != startIndex) {
                path += graph.segmentNames[previousSegment[node]]
                node = previousNode[node]
            }
            result[graph.nodeNames[target]] = path.reversed()
        }
        return result
    }

    // ── Waypoint routing ────────────────────────────────────────────────────

    /** A change to the flags of a trip. */
    class WaypointEdit(
        val kind: Kind,
        val waypointId: String,
        val coordinate: RoutePoint,
        val segmentId: String,
        /** Occurrence of the trip segment where an insertion began. */
        val segmentIndex: Int? = null,
    ) {
        enum class Kind { APPEND, MOVE, INSERT }
    }

    private class Ends(val start: String, val end: String)

    private fun ends(edge: PlannerSegment, direction: TravelDirection) =
        if (direction == TravelDirection.FORWARD) Ends(edge.startNode, edge.endNode) else Ends(edge.endNode, edge.startNode)

    private fun endpointCoordinate(graph: RouteGraph, id: String, node: String): RoutePoint? {
        val edge = graph.segments[id] ?: return null
        return if (node == edge.startNode) edge.startCoordinate else edge.endCoordinate
    }

    /** Older trips without flags keep their exact path; flags mark their terminal segments. */
    fun initializeWaypoints(trip: PlannedTrip, graph: RouteGraph): PlannedTrip {
        if (trip.waypoints != null || trip.segmentIds.isEmpty()) return trip
        val nodes = vertices(trip, graph) ?: return trip
        val last = trip.segmentIds.size - 1
        val start = endpointCoordinate(graph, trip.segmentIds[0], nodes[0]) ?: return trip
        val end = endpointCoordinate(graph, trip.segmentIds[last], nodes[last + 1]) ?: return trip

        fun make(index: Int, coordinate: RoutePoint) = PlanWaypoint(
            id = "${trip.id}-waypoint-$index",
            coordinate = coordinate,
            segmentId = trip.segmentIds[index],
            segmentIndex = index,
            direction = if (graph.segments[trip.segmentIds[index]]?.startNode == nodes[index]) {
                TravelDirection.FORWARD
            } else {
                TravelDirection.REVERSE
            },
        )
        return trip.copy(waypoints = if (last == 0) listOf(make(0, start)) else listOf(make(0, start), make(last, end)))
    }

    private class State(
        val ids: List<String>,
        val points: List<PlanWaypoint>,
        val start: String,
        val end: String,
        val cost: Double,
    )

    /**
     * Keeps unaffected legs verbatim and recalculates only the connections touching edited flags.
     * Returns null when no allowed connection exists.
     */
    fun build(
        trip: PlannedTrip,
        waypoints: List<PlanWaypoint>,
        graph: RouteGraph,
        restrictions: RoutingRestrictions = RoutingRestrictions.DEFAULT,
    ): PlannedTrip? {
        if (waypoints.isEmpty()) {
            return trip.copy(waypoints = emptyList(), segmentIds = emptyList(), startNode = null, endNode = null, distanceMeters = 0.0)
        }
        for (point in waypoints) if (!graph.segmentAllowed(point.segmentId, restrictions)) return null
        for (point in waypoints) if (graph.segments[point.segmentId] == null) return null

        val fractions = HashMap<String, Double>()
        for (point in waypoints) {
            val geometry = graph.geometry(point.segmentId) ?: continue
            fractions[point.id] = if (geometry.total > 0) geometry.project(point.coordinate).along / geometry.total else 0.0
        }
        val firstFraction = fractions[waypoints[0].id]
        val lastFraction = fractions[waypoints[waypoints.size - 1].id]
        val hasEndpoints = firstFraction != null && lastFraction != null

        fun options(point: PlanWaypoint): List<TravelDirection> =
            point.direction?.let { listOf(it) } ?: listOf(TravelDirection.FORWARD, TravelDirection.REVERSE)

        val first = waypoints[0]
        val firstEdge = graph.segments.getValue(first.segmentId)
        var states: List<State> = options(first).map { direction ->
            val nodes = ends(firstEdge, direction)
            val fraction = if (hasEndpoints && firstFraction != null) {
                when {
                    waypoints.size == 1 -> 0.0
                    direction == TravelDirection.FORWARD -> 1 - firstFraction
                    else -> firstFraction
                }
            } else {
                1.0
            }
            State(
                ids = listOf(first.segmentId),
                points = listOf(first.copy(direction = direction, segmentIndex = 0)),
                start = nodes.start,
                end = nodes.end,
                cost = firstEdge.routingCost * fraction,
            )
        }

        val original = trip.waypoints ?: emptyList()
        val oldById = HashMap<String, Int>()
        for ((index, point) in original.withIndex()) oldById[point.id] = index

        // Whether a stretch of the current trip is still allowed, answered in O(1) from prefix
        // counts instead of re-walking the trip for every candidate leg.
        val tripNodes = if (original.isEmpty()) null else vertices(trip, graph)
        val allowance = graph.allowance(restrictions)
        val blockedSegments = arrayListOf(0)
        val blockedNodes = arrayListOf(0)
        if (tripNodes != null) {
            for (id in trip.segmentIds) {
                val allowed = graph.segmentIndex[id]?.let { allowance.segments[it] } ?: false
                blockedSegments += blockedSegments.last() + if (allowed) 0 else 1
            }
            for (node in tripNodes) {
                val allowed = graph.nodeIndex[node]?.let { allowance.nodes[it] } ?: true
                blockedNodes += blockedNodes.last() + if (allowed) 0 else 1
            }
        }
        fun stretchAllowed(start: Int, end: Int): Boolean {
            if (tripNodes == null || start < 0 || end >= trip.segmentIds.size) return false
            if (end < start) return true
            if (blockedSegments[end + 1] - blockedSegments[start] != 0) return false
            // The nodes strictly inside the stretch: start+1 … end.
            return start + 1 > end || blockedNodes[end + 1] - blockedNodes[start + 1] == 0
        }

        for (i in 1 until waypoints.size) {
            val point = waypoints[i]
            val edge = graph.segments.getValue(point.segmentId)
            val next = ArrayList<State>()
            // One search per state answers both directions of this flag.
            val targetStarts = options(point).map { ends(edge, it).start }.toSet()
            val connections = HashMap<Int, Map<String, List<String>>>()
            for (direction in options(point)) {
                var best: State? = null
                val nodes = ends(edge, direction)
                for ((stateIndex, state) in states.withIndex()) {
                    val previous = state.points[state.points.size - 1]
                    val oldIndex = oldById[previous.id]
                    val oldA = oldIndex?.let { original[it] }
                    var oldB: PlanWaypoint? = null
                    if (oldIndex != null && oldIndex + 1 < original.size) oldB = original[oldIndex + 1]
                    var unchanged = false
                    if (oldA != null && oldB != null) {
                        unchanged = oldB.id == point.id && oldA.segmentId == previous.segmentId &&
                            oldB.segmentId == point.segmentId && oldA.direction == previous.direction &&
                            oldB.direction == direction && stretchAllowed(oldA.segmentIndex, oldB.segmentIndex)
                    }
                    var plannedIds: List<String>? = null
                    if (unchanged && oldA != null && oldB != null) {
                        val lower = oldA.segmentIndex + 1
                        val upper = oldB.segmentIndex + 1
                        if (lower <= upper && upper <= trip.segmentIds.size) {
                            plannedIds = trip.segmentIds.subList(lower, upper)
                        }
                    } else if (previous.segmentId == point.segmentId && previous.direction == direction) {
                        plannedIds = emptyList() // Two flags can refer to the same traversal of one segment.
                    } else {
                        val paths = connections.getOrPut(stateIndex) {
                            shortestPaths(graph, state.end, targetStarts, point.segmentId, restrictions)
                        }
                        plannedIds = paths[nodes.start]?.let { it + point.segmentId }
                    }
                    val ids = plannedIds ?: continue

                    val isLast = i == waypoints.size - 1
                    var cost = state.cost
                    if (ids.isEmpty() && previous.segmentId == point.segmentId && previous.direction == direction && hasEndpoints) {
                        val before = fractions[previous.id]
                        val after = fractions[point.id]
                        if (before != null && after != null &&
                            (if (direction == TravelDirection.FORWARD) after < before else after > before)
                        ) continue
                        // The traversal previously ran to the end of this edge; stop at the last flag.
                        if (isLast && lastFraction != null) {
                            cost -= edge.routingCost * (if (direction == TravelDirection.FORWARD) 1 - lastFraction else lastFraction)
                        }
                    }
                    for ((index, id) in ids.withIndex()) {
                        val weight = graph.segments[id]?.routingCost ?: 0.0
                        cost += if (hasEndpoints && isLast && index == ids.size - 1 && lastFraction != null) {
                            weight * (if (direction == TravelDirection.FORWARD) lastFraction else 1 - lastFraction)
                        } else {
                            weight
                        }
                    }
                    val flag = point.copy(direction = direction, segmentIndex = state.ids.size + ids.size - 1)
                    val candidate = State(
                        ids = state.ids + ids,
                        points = state.points + flag,
                        start = state.start,
                        end = nodes.end,
                        cost = cost,
                    )
                    if (best == null || candidate.cost < best.cost) best = candidate
                }
                best?.let { next += it }
            }
            states = next
            if (states.isEmpty()) return null
        }

        var best = states.firstOrNull() ?: return null
        for (state in states.drop(1)) if (state.cost < best.cost) best = state
        var result = trip.copy(
            segmentIds = best.ids,
            waypoints = best.points,
            startNode = best.start,
            endNode = best.end,
            distanceMeters = best.ids.sumOf { graph.segments[it]?.lengthMeters ?: 0.0 },
        )
        if (hasEndpoints) {
            RouteGeometryBuilder.trim(result, graph)?.let { result = result.copy(distanceMeters = it.distanceMeters) }
        }
        return if (isConnected(result, graph)) result else null
    }

    fun edit(
        original: PlannedTrip,
        edit: WaypointEdit,
        graph: RouteGraph,
        restrictions: RoutingRestrictions = RoutingRestrictions.DEFAULT,
    ): PlannedTrip? {
        val trip = initializeWaypoints(original, graph)
        if (trip.segmentIds.isNotEmpty() && trip.waypoints == null) return null
        val points = (trip.waypoints ?: emptyList()).toMutableList()
        val flag = PlanWaypoint(id = edit.waypointId, coordinate = edit.coordinate, segmentId = edit.segmentId, segmentIndex = 0)
        when (edit.kind) {
            WaypointEdit.Kind.MOVE -> {
                val index = points.indexOfFirst { it.id == edit.waypointId }
                if (index < 0) return null
                points[index] = flag
            }
            WaypointEdit.Kind.INSERT -> {
                val segmentIndex = edit.segmentIndex ?: return null
                if (segmentIndex < 0 || segmentIndex >= trip.segmentIds.size) return null
                val index = points.indexOfFirst { it.segmentIndex > segmentIndex }.takeIf { it >= 0 }
                if (points.size == 1) points[0] = points[0].copy(direction = null)
                points.add(index?.let { maxOf(1, it) } ?: points.size, flag)
            }
            WaypointEdit.Kind.APPEND -> {
                if (points.size == 1) points[0] = points[0].copy(direction = null) // The second flag sets the direction.
                points += flag
            }
        }
        if (trip.segmentIds.size == 1 && points.all { it.segmentId == trip.segmentIds[0] }) {
            for (index in points.indices) points[index] = points[index].copy(direction = null)
        }
        return build(trip, points, graph, restrictions)
    }

    fun removeWaypoint(
        trip: PlannedTrip,
        id: String,
        graph: RouteGraph,
        restrictions: RoutingRestrictions = RoutingRestrictions.DEFAULT,
    ): PlannedTrip? {
        val points = trip.waypoints ?: return null
        if (points.none { it.id == id }) return null
        val remaining = points.filter { it.id != id }
        build(trip, remaining, graph, restrictions)?.let { return it }
        // The directions chosen for the other flags can make them unreachable once this one is
        // gone (a round trip, two flags on one segment…): let the planner choose them again.
        return build(trip.copy(waypoints = emptyList()), remaining.map { it.copy(direction = null) }, graph, restrictions)
    }

    /** A filter change may require a new path and different directions through the flags. */
    fun replan(trip: PlannedTrip, graph: RouteGraph, restrictions: RoutingRestrictions): PlannedTrip? {
        val initialized = initializeWaypoints(trip, graph)
        if (initialized.segmentIds.isEmpty()) return initialized
        val points = initialized.waypoints?.takeIf { it.isNotEmpty() } ?: return null
        return build(
            initialized.copy(waypoints = emptyList()),
            points.map { it.copy(direction = null) },
            graph,
            restrictions,
        )
    }

    enum class TripEnd { START, END }

    /** Trims exactly one traversal and moves the terminal flag to the new end. */
    fun trimSegment(original: PlannedTrip, end: TripEnd, graph: RouteGraph): PlannedTrip? {
        val trip = initializeWaypoints(original, graph)
        val nodes = vertices(trip, graph) ?: return null
        if (trip.segmentIds.isEmpty()) return null
        if (trip.segmentIds.size == 1) {
            return trip.copy(waypoints = emptyList(), segmentIds = emptyList(), startNode = null, endNode = null, distanceMeters = 0.0)
        }
        val atStart = end == TripEnd.START
        val ids = if (atStart) trip.segmentIds.drop(1) else trip.segmentIds.dropLast(1)
        val from = if (atStart) nodes[1] else nodes[0]
        val to = if (atStart) nodes[nodes.size - 1] else nodes[nodes.size - 2]
        val points = (trip.waypoints ?: emptyList())
            .filter { if (atStart) it.segmentIndex > 0 else it.segmentIndex < ids.size }
            .map { it.copy(segmentIndex = it.segmentIndex - if (atStart) 1 else 0) }
            .toMutableList()
        val index = if (atStart) 0 else ids.size - 1
        val coordinate = endpointCoordinate(graph, ids[index], if (atStart) from else to) ?: return null
        val edge = graph.segments[ids[index]] ?: return null
        val direction = if (edge.startNode == (if (atStart) from else nodes[index])) TravelDirection.FORWARD else TravelDirection.REVERSE
        val terminal = if (atStart) trip.waypoints?.firstOrNull() else trip.waypoints?.lastOrNull()
        val flag = PlanWaypoint(
            id = terminal?.id ?: "${trip.id}-trim-${if (atStart) "start" else "end"}-${ids.size}",
            coordinate = coordinate,
            segmentId = ids[index],
            segmentIndex = index,
            direction = direction,
        )
        if (atStart) points.add(0, flag) else points += flag
        val result = trip.copy(segmentIds = ids, startNode = from, endNode = to, waypoints = points)
        return result.copy(distanceMeters = distanceMeters(result, graph))
    }
}
