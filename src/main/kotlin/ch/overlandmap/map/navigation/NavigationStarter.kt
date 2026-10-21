package ch.overlandmap.map.navigation

import android.util.Log
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.geo.Coordinate
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "NavDebug"

/** The longest a navigation waits for a Mapbox token that isn't cached yet, before going without road directions. */
private const val TOKEN_TIMEOUT_MS = 4000L

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
    Log.d(TAG, "beginNavigation: itinerary=$itineraryId reverse=$reverse initialTarget=$initialTargetIndex")
    val itinerary = libraryRepository.itinerary(itineraryId) ?: run {
        Log.d(TAG, "beginNavigation: itinerary not found")
        return false
    }
    val steps = libraryRepository.steps(itineraryId).sortedBy { it.stepId }
    Log.d(TAG, "beginNavigation: ${steps.size} steps")
    if (steps.isEmpty()) {
        Log.d(TAG, "beginNavigation: the itinerary has no steps")
        return false
    }
    val tracks = libraryRepository.tracks(itineraryId)
    val trackCoordinates = tracks.flatMap { track -> track.coordinates().map { Coordinate(it.lat, it.lon) } }
    Log.d(TAG, "beginNavigation: ${tracks.size} tracks, ${trackCoordinates.size} points")
    val cached = mapboxTokenManager.cachedTokenNow()
    val token = cached ?: withTimeoutOrNull(TOKEN_TIMEOUT_MS) {
        Log.d(TAG, "beginNavigation: no cached Mapbox token, fetching")
        runCatching { mapboxTokenManager.validToken() }.getOrNull()
    }.orEmpty()
    Log.d(TAG, "beginNavigation: token ${if (cached != null) "cached" else if (token.isEmpty()) "unavailable" else "fetched"}, starting")
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
    Log.d(TAG, "beginNavigation: started, navigating=${navigationCoordinator.isNavigating}")
    return true
}
