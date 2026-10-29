package ch.overlandmap.map.data

import ch.overlandmap.map.data.local.*
import ch.overlandmap.map.geo.*
import ch.overlandmap.map.model.Itinerary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Offline proximity search over downloaded library, world data and synced community waypoints. */
class NearbySearchRepository(private val dao: NearbyDao,
    private val imported: ((List<String>) -> List<SearchResult>)? = null,
    private val loadItinerary: suspend (String) -> Itinerary?) {
    enum class Type { ALL, ITINERARY, WAYPOINT, BORDER_POST }

    suspend fun search(origin: Coordinate, radiusKm: Double, type: Type, lang: String): List<SearchResult> = withContext(Dispatchers.IO) {
        val prefixes = NearbyGeohashes.cover(origin, radiusKm)
        val precision = prefixes.first().length
        val found = mutableMapOf<String, SearchResult>()
        val itineraries = mutableMapOf<String, Itinerary?>()
        suspend fun itinerary(id: String): Itinerary? {
            if (id !in itineraries) itineraries[id] = loadItinerary(id)
            return itineraries[id]
        }
        fun add(result: SearchResult, lat: Double?, lon: Double?, hash: String? = null) {
            val point = if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) Coordinate(lat, lon)
                else Geohash.decode(hash) ?: return
            val distance = origin.distanceKm(point)
            if (distance > radiusKm) return
            val key = result.type + ":" + result.documentId
            if (distance < (found[key]?.distanceKm ?: Double.POSITIVE_INFINITY)) found[key] = result.copy(distanceKm = distance)
        }
        fun addItinerary(
            it: Itinerary,
            lat: Double? = it.centerLat ?: it.latMin?.let { south -> it.latMax?.let { north -> (south + north) / 2 } },
            lon: Double? = it.centerLon ?: it.lonMin?.let { west -> it.lonMax?.let { east -> (west + east) / 2 } },
            hash: String? = null,
        ) {
            add(SearchResult(FtsIndex.TYPE_ITINERARY, it.documentId, it.name(lang), it.description(lang).orEmpty(),
                itinerarySlug = it.itineraryId), lat, lon, hash)
        }
        if (type == Type.ALL || type == Type.ITINERARY) {
            val steps = prefixes.flatMap { dao.steps(it, it + "~") } + dao.unindexedSteps(precision)
            for (row in steps.distinctBy { it.documentId }) {
                val step = row.toModel()
                val parent = itinerary(step.itineraryId)
                add(SearchResult(FtsIndex.TYPE_STEP, step.documentId, step.name(lang), step.description(lang).orEmpty(),
                    step.itineraryId, step.stepId, parent?.itineraryId, markerType = step), step.lat, step.lon, step.geohash)
                if (parent != null) addItinerary(parent, step.lat, step.lon, step.geohash)
            }
            for (prefix in prefixes) {
                val box = Geohash.boundingBox(prefix)!!
                for (row in dao.itineraryCenters(box.latMin, box.latMax, box.lonMin, box.lonMax)) addItinerary(row.toModel())
            }
        }
        if (type == Type.ALL || type == Type.WAYPOINT) {
            val waypoints = prefixes.flatMap { dao.waypoints(it, it + "~") } + dao.unindexedWaypoints(precision)
            for (row in waypoints.distinctBy { it.documentId }) {
                val w = row.toModel()
                add(SearchResult(FtsIndex.TYPE_WAYPOINT, w.documentId, w.name(lang), w.description(lang).orEmpty(), w.itineraryId, markerType = w), w.lat, w.lon, w.geohash)
            }
            val contributed = prefixes.flatMap { dao.contributed(it, it + "~") } + dao.unindexedContributed(precision)
            for (row in contributed.distinctBy { it.documentId }) {
                val w = row.toModel()
                add(SearchResult(FtsIndex.TYPE_CONTRIBUTED_WAYPOINT, w.documentId, w.name(lang), w.description(lang) ?: w.content.orEmpty(),
                    w.itinDocumentId, contributedWaypoint = w), w.lat, w.lon, w.geohash)
            }
        }
        if (type == Type.ALL || type == Type.BORDER_POST) {
            val posts = prefixes.flatMap { dao.posts(it, it + "~") } + dao.unindexedPosts(precision)
            for (row in posts.distinctBy { it.documentId }) {
                val p = row.toModel()
                add(SearchResult(FtsIndex.TYPE_BORDER_POST, p.documentId, p.name, p.comment(lang).orEmpty()), p.lat, p.lon, p.geohash)
            }
        }
        if (type != Type.BORDER_POST) imported?.invoke(prefixes)?.forEach { result ->
            val w = result.importedWaypoint
            val t = result.importedTrack
            if (w != null && (type == Type.ALL || type == Type.WAYPOINT)) add(result, w.latitude, w.longitude, w.geohash)
            else if (w == null && t != null && (type == Type.ALL || type == Type.ITINERARY)) t.trackBounds?.centre?.let { add(result, it.latitude, it.longitude, t.geohash) }
        }
        // Geohash cell order is spatially useful but is NOT distance order. Filter circle false
        // positives and sort by the exact great-circle distance only once, after indexed lookup.
        found.values.sortedWith(compareBy<SearchResult> { it.distanceKm }.thenBy { it.name }.thenBy { it.documentId })
    }
}
