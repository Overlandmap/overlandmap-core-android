package ch.overlandmap.map.data

import ch.overlandmap.map.data.local.FtsIndex

/**
 * One full-text search result, ready for the list. [type] is an
 * [FtsIndex] `TYPE_*`; step hits carry the [itineraryDocumentId] and [stepId]
 * needed to open the step in its itinerary.
 */
data class SearchResult(
    val type: String,
    val documentId: String,
    val name: String,
    val snippet: String,
    val itineraryDocumentId: String? = null,
    val stepId: Int? = null,
    /** The itinerary's human slug (e.g. "L9"), shown in red for itinerary/step hits. */
    val itinerarySlug: String? = null,
    val distanceKm: Double? = null,
    val contributedWaypoint: ch.overlandmap.map.model.ContributedWaypoint? = null,
    val importedTrack: ch.overlandmap.map.tracking.RecordedTrack? = null,
    val importedWaypoint: ch.overlandmap.map.tracking.RecordedWaypoint? = null,
    val markerType: ch.overlandmap.map.model.WaypointType? = null,
)

/** Full-text search over the local library and world data. */
class SearchRepository(
    private val fts: FtsIndex,
    private val library: LibraryRepository,
    private val importedSearch: suspend (String) -> List<SearchResult> = { emptyList() },
) {

    /** Minimum query length before a search runs (per the spec: after 3 chars). */
    val minQueryLength = MIN_QUERY_LENGTH

    suspend fun search(query: String, lang: String): List<SearchResult> {
        val trimmed = query.trim()
        if (trimmed.length < minQueryLength) return emptyList()
        val results = fts.search(lang, query).map { hit ->
            when (hit.type) {
                FtsIndex.TYPE_STEP -> {
                    val step = library.stepByDocumentId(hit.documentId)
                    val slug = step?.itineraryId?.let { library.itinerary(it)?.itineraryId }
                    SearchResult(
                        type = hit.type,
                        documentId = hit.documentId,
                        name = hit.name,
                        snippet = hit.snippet,
                        itineraryDocumentId = step?.itineraryId,
                        stepId = step?.stepId,
                        itinerarySlug = slug,
                        markerType = step,
                    )
                }
                FtsIndex.TYPE_WAYPOINT -> {
                    val waypoint = library.waypointByDocumentId(hit.documentId)
                    SearchResult(hit.type, hit.documentId, hit.name, hit.snippet,
                        itineraryDocumentId = waypoint?.itineraryId, markerType = waypoint)
                }
                FtsIndex.TYPE_ITINERARY -> SearchResult(
                    type = hit.type,
                    documentId = hit.documentId,
                    name = hit.name,
                    snippet = hit.snippet,
                    itinerarySlug = library.itinerary(hit.documentId)?.itineraryId,
                )
                else -> SearchResult(hit.type, hit.documentId, hit.name, hit.snippet)
            }
        }
        val allResults = results + kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { importedSearch(trimmed) }
        val unique = uniqueResults(allResults)
        if (unique.size != allResults.size) ch.overlandmap.map.diagnostics.CrashReports.note(
            "Search duplicate results removed: ${allResults.size - unique.size}",
        )
        return rank(unique, trimmed)
    }

    /** The waypoint behind a waypoint hit, to show its popup viewer. */
    suspend fun waypoint(documentId: String) = library.waypointByDocumentId(documentId)

    companion object {
        /** A document can occur only once in a list, whose row keys must be unique. */
        fun uniqueResults(results: List<SearchResult>): List<SearchResult> =
            results.distinctBy { it.type to it.documentId }

        /** Minimum query length before a search runs. */
        const val MIN_QUERY_LENGTH = 3

        private fun fold(text: String): String =
            java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
                .filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }
                .lowercase()

        /**
         * Buckets the hits by how [query] matches the name, as iOS does: names starting with it
         * first, then names containing it, then the rest (matched on their body). A strong body
         * match must still lose to a weak name match, so this is not relevance scoring. The sort
         * is stable: within a bucket the index order is kept. Comparison ignores case and accents.
         */
        fun <T> rankBy(items: List<T>, query: String, name: (T) -> String): List<T> {
            val needle = fold(query.trim())
            return items.sortedBy { item ->
                val n = fold(name(item))
                when {
                    n.startsWith(needle) -> 0
                    n.contains(needle) -> 1
                    else -> 2
                }
            }
        }

        fun rank(results: List<SearchResult>, query: String): List<SearchResult> =
            rankBy(results, query) { it.name }
    }
}
