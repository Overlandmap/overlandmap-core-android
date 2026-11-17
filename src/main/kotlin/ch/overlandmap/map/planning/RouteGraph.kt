package ch.overlandmap.map.planning

class RouteGraphException(message: String) : Exception(message)

/** An edge as seen from one of its nodes, with everything the router needs as plain numbers. */
internal class IndexedStep(val segment: Int, val to: Int, val cost: Double)

/** What the current restrictions allow, per segment and per node index. */
internal class RouteAllowance(
    val restrictions: RoutingRestrictions,
    val segments: BooleanArray,
    val nodes: BooleanArray,
)

/**
 * The indexed, undirected routing graph (iOS `RouteGraph`). Built once per graph file, then
 * read-only apart from the closed-border and country-status sets, which are filled in before
 * planning.
 *
 * Routing runs on integer indices (`segmentIndex`, `nodeIndex`) and on per-restriction tables
 * computed once, so a shortest-path search touches no strings.
 */
class RouteGraph(file: PlannerGraphFile) {

    val segments: Map<String, PlannerSegment>

    /** Every node name of the graph. */
    val nodes: List<String>

    /** Maps a graph node to the border post (Firestore `border_post` document) it stands for. */
    val borderPostNodes: Map<String, String> = file.borderPostNodes
    val archiveSha256: String? = file.archiveSha256
    val geometrySha256: String? = file.geometrySha256

    /** Nodes of closed or bilateral border posts. */
    @Volatile
    var closedBorderNodes: Set<String> = emptySet()
        set(value) {
            field = value
            invalidateAllowance()
        }

    /**
     * Overlanding status per country (0 forbidden, 1 unsafe, 2 restricted, 3 open), keyed by
     * upper-cased ADM0_A3 code.
     */
    @Volatile
    var countryStatuses: Map<String, Int> = emptyMap()
        set(value) {
            field = value
            invalidateAllowance()
        }

    /** Supplies the polyline of a segment. */
    @Volatile
    var geometryProvider: ((PlannerSegment) -> SegmentGeometry?)? = null

    internal val nodeNames: List<String>
    internal val nodeIndex: Map<String, Int>
    internal val segmentNames: List<String>
    internal val segmentIndex: Map<String, Int>
    internal val segmentList: List<PlannerSegment>
    internal val steps: List<List<IndexedStep>>

    private val allowanceLock = Any()
    private var cachedAllowance: RouteAllowance? = null

    init {
        val byId = LinkedHashMap<String, PlannerSegment>()
        val nodeIndex = HashMap<String, Int>()
        val nodeNames = ArrayList<String>()
        val segmentIndex = HashMap<String, Int>()
        val segmentList = ArrayList<PlannerSegment>()

        fun node(name: String): Int = nodeIndex.getOrPut(name) {
            nodeNames += name
            nodeNames.size - 1
        }

        for (edge in file.segments) {
            if (edge.id.isEmpty() || byId.containsKey(edge.id) || edge.startNode.isEmpty() ||
                edge.endNode.isEmpty() || !edge.lengthMeters.isFinite() || edge.lengthMeters < 0
            ) throw RouteGraphException("Invalid route graph")
            byId[edge.id] = edge
            segmentIndex[edge.id] = segmentList.size
            segmentList += edge
            node(edge.startNode)
            node(edge.endNode)
        }

        val steps = List(nodeNames.size) { ArrayList<IndexedStep>() }
        for ((index, edge) in segmentList.withIndex()) {
            val from = nodeIndex.getValue(edge.startNode)
            val to = nodeIndex.getValue(edge.endNode)
            steps[from] += IndexedStep(index, to, edge.routingCost)
            steps[to] += IndexedStep(index, from, edge.routingCost)
        }

        segments = byId
        nodes = nodeNames
        this.nodeNames = nodeNames
        this.nodeIndex = nodeIndex
        segmentNames = segmentList.map { it.id }
        this.segmentIndex = segmentIndex
        this.segmentList = segmentList
        this.steps = steps
    }

    fun geometry(segmentId: String): SegmentGeometry? {
        val segment = segments[segmentId] ?: return null
        return geometryProvider?.invoke(segment)
    }

    /** Border post document ID for a node, falling back to the node itself. */
    fun borderPostId(node: String): String = borderPostNodes[node] ?: node

    // ── Restrictions ────────────────────────────────────────────────────────

    /** Checks every associated country: a minimum status alone can hide a second category. */
    fun blockedReason(segment: PlannerSegment, restrictions: RoutingRestrictions): BlockedCountryReason? {
        val statuses = (segment.countries ?: emptyList())
            .map { countryStatuses[it.trim().uppercase()] }
            .toMutableList()
        if (statuses.isEmpty() || statuses.any { it == null }) statuses += segment.overlanding
        if (restrictions.avoidUnsafe && statuses.contains(0)) return BlockedCountryReason.FORBIDDEN
        if (restrictions.avoidUnsafe && statuses.contains(1)) return BlockedCountryReason.UNSAFE
        if (restrictions.avoidRestricted && statuses.contains(2)) return BlockedCountryReason.RESTRICTED
        return null
    }

    private fun invalidateAllowance() {
        synchronized(allowanceLock) { cachedAllowance = null }
    }

    /** The allowed segments and nodes for [restrictions], computed once per combination. */
    internal fun allowance(restrictions: RoutingRestrictions): RouteAllowance {
        synchronized(allowanceLock) {
            val cached = cachedAllowance
            if (cached != null && cached.restrictions == restrictions) return cached
            val closed = closedBorderNodes
            val result = RouteAllowance(
                restrictions = restrictions,
                segments = BooleanArray(segmentList.size) { blockedReason(segmentList[it], restrictions) == null },
                nodes = BooleanArray(nodeNames.size) { !restrictions.excludeClosedBorders || nodeNames[it] !in closed },
            )
            cachedAllowance = result
            return result
        }
    }

    fun segmentAllowed(id: String, restrictions: RoutingRestrictions): Boolean {
        val index = segmentIndex[id] ?: return false
        return allowance(restrictions).segments[index]
    }

    /** A closed or bilateral post disconnects incident segments without removing them. */
    fun nodeAllowsTransition(node: String, restrictions: RoutingRestrictions): Boolean {
        val index = nodeIndex[node] ?: return true
        return allowance(restrictions).nodes[index]
    }

    companion object {
        /** Border-post statuses 0 and 1 mean closed and bilateral respectively. */
        fun borderPostBlocksRouting(isOpen: Int?): Boolean = isOpen == 0 || isOpen == 1
    }
}
