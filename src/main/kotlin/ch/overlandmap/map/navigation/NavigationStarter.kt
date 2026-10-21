package ch.overlandmap.map.navigation

import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.geo.Coordinate

/**
 * Loads a local itinerary and starts (or, with [initialTargetIndex], resumes) navigating it.
 * Returns false when the itinerary or its steps are not on the device.
 *
 * The Mapbox token is awaited first when it is not cached yet, since the coordinator needs to know
 * whether road directions to the start are possible; an empty one means they are not.
 */
suspend fun OverlandApp.beginNavigation(
    itineraryId: String,
    reverse: Boolean,
    language: String,
    initialTargetIndex: Int? = null,
): Boolean {
    val itinerary = libraryRepository.itinerary(itineraryId) ?: return false
    val steps = libraryRepository.steps(itineraryId).sortedBy { it.stepId }
    if (steps.isEmpty()) return false
    val tracks = libraryRepository.tracks(itineraryId)
    val trackCoordinates = tracks.flatMap { track -> track.coordinates().map { Coordinate(it.lat, it.lon) } }
    val token = mapboxTokenManager.cachedTokenNow() ?: runCatching { mapboxTokenManager.validToken() }.getOrNull().orEmpty()
    navigationCoordinator.startNavigation(
        itineraryId = itineraryId,
        itineraryTitle = itinerary.name(language),
        steps = steps,
        trackCoordinates = trackCoordinates,
        reverse = reverse,
        initialTargetIndex = initialTargetIndex,
        language = language,
        mapboxToken = token,
    )
    return true
}
