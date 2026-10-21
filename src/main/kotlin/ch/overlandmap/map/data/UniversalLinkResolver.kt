package ch.overlandmap.map.data

import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.UniversalLink
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.model.Zone

/** Where a universal link leads, once found in the local data (iOS `UniversalLinkDestination`). */
sealed interface LinkDestination {
    /** [stepId] is the step's own id (the `step` query of the link), already checked to exist. */
    data class OfItinerary(val itinerary: Itinerary, val stepId: Int?) : LinkDestination
    data class OfTrackPack(val pack: TrackPack) : LinkDestination
    data class OfWaypoint(val waypoint: Waypoint) : LinkDestination
    data class OfCountry(val country: Country) : LinkDestination
    data class OfBorder(val border: CountryBorder) : LinkDestination
    data class OfBorderPost(val post: BorderPost) : LinkDestination
    data class OfZone(val zone: Zone) : LinkDestination
}

/** Why a link couldn't be opened. */
class LinkResolutionException(val reason: Reason) : Exception(reason.name) {
    enum class Reason {
        /** Not in the downloaded library (download the track pack and try again). */
        UNAVAILABLE,

        /** An Overlanding object while the Overlanding dataset isn't downloaded yet. */
        NOT_DOWNLOADED,
    }
}

/** The editor an itinerary's link carries: its own `editorId`, else its pack's editor (iOS `linkEditorID`). */
fun linkEditorId(itinerary: Itinerary, pack: TrackPack?): String? =
    (itinerary.extra["editorId"] as? String)?.takeIf { it.isNotEmpty() } ?: pack?.editor

/** The lookups the resolver needs, so it can be tested without a database. */
interface LinkStore {
    suspend fun trackPacks(): List<TrackPack>
    suspend fun itineraryBySlug(trackPackId: String, slug: String): Itinerary?
    suspend fun steps(itineraryId: String): List<ItineraryStep>
    suspend fun waypointByGeohash(geohash: String): Waypoint?
    suspend fun country(alpha3: String): Country?
    suspend fun border(id: String): CountryBorder?
    suspend fun borderPost(id: String): BorderPost?
    suspend fun zone(id: String): Zone?
}

class RepositoryLinkStore(
    private val library: LibraryRepository,
    private val world: WorldRepository,
) : LinkStore {
    override suspend fun trackPacks() = library.allTrackPacks()
    override suspend fun itineraryBySlug(trackPackId: String, slug: String) = library.itineraryBySlug(trackPackId, slug)
    override suspend fun steps(itineraryId: String) = library.steps(itineraryId)
    override suspend fun waypointByGeohash(geohash: String) = library.waypointByGeohash(geohash)
    override suspend fun country(alpha3: String) = world.country(alpha3)
    override suspend fun border(id: String) = world.border(id)
    override suspend fun borderPost(id: String) = world.borderPost(id)
    override suspend fun zone(id: String) = world.zone(id)
}

/**
 * Finds what an `overlandmap.ch` link points at in the local data (iOS `UniversalLinkResolver`).
 * A pack, itinerary or waypoint that isn't downloaded is "unavailable"; an Overlanding object
 * that isn't there means the dataset itself hasn't been downloaded yet.
 */
class UniversalLinkResolver(private val store: LinkStore) {

    @Throws(LinkResolutionException::class)
    suspend fun resolve(link: UniversalLink): LinkDestination = when (link) {
        is UniversalLink.TrackPack -> {
            val packs = store.trackPacks().filter { it.name == link.name && it.editor == link.editorId }
            LinkDestination.OfTrackPack(packs.singleOrNull() ?: unavailable())
        }
        is UniversalLink.Waypoint ->
            LinkDestination.OfWaypoint(store.waypointByGeohash(link.geohash) ?: unavailable())
        is UniversalLink.Itinerary -> resolveItinerary(link)
        is UniversalLink.Country ->
            LinkDestination.OfCountry(store.country(link.alpha3.uppercase()) ?: notDownloaded())
        is UniversalLink.Border -> LinkDestination.OfBorder(store.border(link.id) ?: notDownloaded())
        is UniversalLink.BorderPost -> LinkDestination.OfBorderPost(store.borderPost(link.id) ?: notDownloaded())
        is UniversalLink.Zone -> LinkDestination.OfZone(store.zone(link.id) ?: notDownloaded())
    }

    private suspend fun resolveItinerary(link: UniversalLink.Itinerary): LinkDestination {
        val candidates = store.trackPacks()
            .filter { it.name == link.packName }
            .mapNotNull { pack -> store.itineraryBySlug(pack.documentId, link.shortId)?.let { it to pack } }
            // A teaser has no content of its own: only full itineraries can be opened.
            .filter { (itinerary, pack) -> !itinerary.isBuyable && linkEditorId(itinerary, pack) == link.editorId }
            .map { it.first }
        val itinerary = candidates.singleOrNull() ?: unavailable()
        val stepId = link.stepId
        if (stepId != null && store.steps(itinerary.documentId).none { it.stepId == stepId }) unavailable()
        return LinkDestination.OfItinerary(itinerary, stepId)
    }

    private fun unavailable(): Nothing = throw LinkResolutionException(LinkResolutionException.Reason.UNAVAILABLE)
    private fun notDownloaded(): Nothing = throw LinkResolutionException(LinkResolutionException.Reason.NOT_DOWNLOADED)
}
