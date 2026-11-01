package ch.overlandmap.map.ui.social

import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.ContributedWaypoint
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.geo.checkInGeohash

/**
 * What a check-in or correction is about: the document, and where it lives in Firestore
 * (`collection`), as iOS passes them to `CheckInButton` / `CorrectionButton`.
 */
data class SocialTarget(
    val objectId: String,
    val trackPackId: String,
    val itineraryId: String,
    /** `itinerary/<id>/steps` for a step, `waypoint` for a pack waypoint. */
    val collection: String,
    val geohash: String? = null,
)

fun ItineraryStep.socialTarget() = SocialTarget(
    objectId = documentId,
    trackPackId = trackPackId,
    itineraryId = itineraryId,
    collection = "itinerary/$itineraryId/steps",
    geohash = checkInGeohash(),
)

fun Waypoint.socialTarget() = SocialTarget(
    objectId = documentId,
    trackPackId = trackPackId,
    itineraryId = itineraryId.orEmpty(),
    collection = "waypoint",
    geohash = checkInGeohash(),
)

/** The geohash identifies the location even when a waypoint is shared across packs. */
fun ContributedWaypoint.socialTarget() = SocialTarget(
    objectId = documentId,
    trackPackId = trackPackId,
    itineraryId = itinDocumentId.orEmpty(),
    collection = "contributed_waypoint",
    geohash = checkInGeohash(),
)
