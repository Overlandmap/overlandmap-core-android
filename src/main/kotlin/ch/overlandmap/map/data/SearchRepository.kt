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
)

/** Full-text search over the local library and world data. */
class SearchRepository(
    private val fts: FtsIndex,
    private val library: LibraryRepository,
) {

    /** Minimum query length before a search runs (per the spec: after 3 chars). */
    val minQueryLength = 3

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
                    )
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
        // Rank by how the query matches the name: names starting with it first,
        // then names merely containing it, then the rest (matched on their
        // description). A stable sort keeps the FTS order within each group.
        val needle = trimmed.lowercase()
        return results.sortedBy { result ->
            val name = result.name.lowercase()
            when {
                name.startsWith(needle) -> 0
                name.contains(needle) -> 1
                else -> 2
            }
        }
    }

    /** The waypoint behind a waypoint hit, to show its popup viewer. */
    suspend fun waypoint(documentId: String) = library.waypointByDocumentId(documentId)
}
