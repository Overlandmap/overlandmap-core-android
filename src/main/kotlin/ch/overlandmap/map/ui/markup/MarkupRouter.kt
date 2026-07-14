package ch.overlandmap.map.ui.markup

import ch.overlandmap.map.data.LibraryRepository
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Waypoint

/** What tapping a markup link should open. */
sealed interface MarkupDestination {
    /**
     * Select a step of the itinerary already on screen. [step] carries the
     * step's data for the confirmation popup; when it cannot be found the
     * link jumps directly.
     */
    data class JumpToStep(val stepId: Int, val step: ItineraryStep?) : MarkupDestination

    /** Open another local itinerary, on [stepId] when the link named one. */
    data class OpenItinerary(
        val itinerary: Itinerary,
        val stepId: Int?,
        val step: ItineraryStep?,
    ) : MarkupDestination

    /** A buyable teaser has no content: buy popup, not the screen. */
    data class ShowBuyable(val itinerary: Itinerary) : MarkupDestination
    data class ShowSidebar(val sidebar: Sidebar) : MarkupDestination
    data class ShowWaypoint(val waypoint: Waypoint) : MarkupDestination
    data class OpenUrl(val url: String) : MarkupDestination
    /** Target not in the local library (e.g. only in the full pack). */
    object Unavailable : MarkupDestination
}

/**
 * Resolves a [MarkupLink] against the local library, from the point of view
 * of a text belonging to [trackPackId] and, when shown on an itinerary
 * screen, to the itinerary with slug [sourceItineraryId] (like "K4") — a
 * step link to that same itinerary jumps in place instead of navigating.
 */
class MarkupRouter(
    private val library: LibraryRepository,
    private val trackPackId: String,
    private val sourceItineraryId: String?,
) {

    suspend fun resolve(link: MarkupLink): MarkupDestination = when (link) {
        is MarkupLink.Url -> MarkupDestination.OpenUrl(link.url)
        is MarkupLink.ItineraryRef -> resolveItinerary(link)
        is MarkupLink.SidebarRef ->
            library.sidebarByName(trackPackId, link.name)
                ?.let { MarkupDestination.ShowSidebar(it) }
                ?: MarkupDestination.Unavailable
        is MarkupLink.WaypointRef ->
            library.waypointByName(trackPackId, link.name)
                ?.let { MarkupDestination.ShowWaypoint(it) }
                ?: MarkupDestination.Unavailable
        is MarkupLink.GeohashRef ->
            // TODO: border posts also carry a geohash.
            library.waypointByGeohash(link.geohash)
                ?.let { MarkupDestination.ShowWaypoint(it) }
                ?: MarkupDestination.Unavailable
        is MarkupLink.TrackRef -> MarkupDestination.Unavailable // TODO, like the Flutter app
        is MarkupLink.EntityRef -> MarkupDestination.Unavailable // TODO: world entities
    }

    private suspend fun resolveItinerary(link: MarkupLink.ItineraryRef): MarkupDestination {
        val packId = when (link.packName) {
            null -> trackPackId
            else -> library.trackPackByName(link.packName)?.documentId
                ?: return MarkupDestination.Unavailable
        }
        val sameItinerary = packId == trackPackId &&
            (link.itineraryId == null || link.itineraryId.equals(sourceItineraryId, ignoreCase = true))
        if (sameItinerary && sourceItineraryId != null && link.stepId != null) {
            return MarkupDestination.JumpToStep(
                link.stepId,
                stepOf(packId, sourceItineraryId, link.stepId),
            )
        }
        val slug = link.itineraryId ?: return MarkupDestination.Unavailable
        val itinerary = library.itineraryBySlug(packId, slug)
            ?: return MarkupDestination.Unavailable
        if (itinerary.isBuyable) return MarkupDestination.ShowBuyable(itinerary)
        val step = link.stepId?.let { stepId ->
            library.steps(itinerary.documentId).firstOrNull { it.stepId == stepId }
        }
        return MarkupDestination.OpenItinerary(itinerary, link.stepId, step)
    }

    private suspend fun stepOf(packId: String, slug: String, stepId: Int): ItineraryStep? {
        val itinerary = library.itineraryBySlug(packId, slug) ?: return null
        return library.steps(itinerary.documentId).firstOrNull { it.stepId == stepId }
    }
}
