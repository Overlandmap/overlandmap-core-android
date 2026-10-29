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
    startChoice: NavigationStartChoice? = null,
    initialLocation: ch.overlandmap.map.tracking.LocationFix? = null,
): Boolean {
    if (itineraryId.startsWith("recording:")) {
        val track = trackRecorder.track(itineraryId.removePrefix("recording:")) ?: return false
        return beginTrackNavigation(track, reverse, initialTargetIndex != null, language, initialTargetIndex, startChoice, initialLocation)
    }
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
        startChoice = startChoice,
        initialLocation = initialLocation,
        language = language,
        mapboxToken = token,
    )
    Log.d(TAG, "beginNavigation: started, navigating=${navigationCoordinator.isNavigating}")
    return true
}

/** Two implicit steps let imported and recorded tracks use the same road-to-start and track HUD. */
suspend fun OverlandApp.beginTrackNavigation(track: ch.overlandmap.map.tracking.RecordedTrack, reverse: Boolean, direct: Boolean, language: String, resumeTarget: Int? = null, startChoice: NavigationStartChoice? = null, initialLocation: ch.overlandmap.map.tracking.LocationFix? = null): Boolean {
    if (track.points.size < 2) return false
    val coords = track.coordinates
    val start = coords.first(); val end = coords.last()
    val id = "recording:${track.id}"
    val steps = listOf(
        ch.overlandmap.map.model.ItineraryStep("$id:start", id, "", 1, getString(ch.overlandmap.map.R.string.track_start), lat = start.latitude, lon = start.longitude),
        ch.overlandmap.map.model.ItineraryStep("$id:end", id, "", 2, getString(ch.overlandmap.map.R.string.track_end), lat = end.latitude, lon = end.longitude, distanceKm = track.lengthKm),
    )
    val token = mapboxTokenManager.cachedTokenNow() ?: withTimeoutOrNull(TOKEN_TIMEOUT_MS) { runCatching { mapboxTokenManager.validToken() }.getOrNull() }.orEmpty()
    navigationCoordinator.startNavigation(id, track.displayName, steps, coords, reverse,
        initialTargetIndex = resumeTarget ?: if (direct) { if (reverse) 0 else 1 } else null, language = language, mapboxToken = if (direct) "" else token,
        preferredStartIndex = if (reverse) 1 else 0, startChoice = startChoice, initialLocation = initialLocation)
    return true
}
