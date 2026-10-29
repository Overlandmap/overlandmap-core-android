package ch.overlandmap.map.ui.navigate

import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.geo.TrackGeometry
import ch.overlandmap.map.geo.TrackSample
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.navigation.coordinate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A numbered step marker. [number] is the 1-based position in the itinerary. */
data class NavigationMarker(val number: Int, val coordinate: Coordinate)

/** What the navigation map draws besides the live state (iOS `NavigationSession.baseMapContent`). */
data class NavigationMapContent(
    val itinerary: Itinerary,
    /** The itinerary's own tracks, at full resolution. */
    val itineraryTracks: List<List<Coordinate>>,
    /** The pack's other itineraries' tracks, simplified. */
    val otherTracks: List<List<Coordinate>>,
    val markers: List<NavigationMarker>,
    val bounds: GeoBounds?,
)

/** Douglas-Peucker tolerance, in degrees, for the pack's other tracks. */
const val OTHER_TRACKS_TOLERANCE = 0.0005

private fun Track.points(): List<TrackSample> = coordinates().map { TrackSample(it.lat, it.lon, it.ele.takeIf { e -> e != 0.0 }) }

/** The itinerary, its tracks and its steps from the database; null when the itinerary is no longer there. */
suspend fun OverlandApp.loadNavigationContent(itineraryId: String): NavigationMapContent? = withContext(Dispatchers.IO) {
    if (itineraryId.startsWith("recording:")) {
        val track = trackRecorder.track(itineraryId.removePrefix("recording:")) ?: return@withContext null
        val itinerary = Itinerary(documentId = itineraryId, trackPackId = "", name = track.displayName, lengthKM = track.lengthKm)
        return@withContext NavigationMapContent(itinerary, listOf(track.coordinates), emptyList(),
            listOfNotNull(track.coordinates.firstOrNull()?.let { NavigationMarker(1, it) }, track.coordinates.lastOrNull()?.let { NavigationMarker(2, it) }), track.bounds)
    }
    val itinerary = libraryRepository.itinerary(itineraryId) ?: return@withContext null
    val own = libraryRepository.tracks(itineraryId)
        .map { track -> track.points().map { it.coordinate } }
        .filter { it.size > 1 }
    val others = libraryRepository.itinerariesOf(itinerary.trackPackId)
        .filter { it.documentId != itineraryId }
        .flatMap { libraryRepository.tracks(it.documentId) }
        .mapNotNull { track ->
            val points = track.points()
            if (points.size > 1) TrackGeometry.simplify(points, OTHER_TRACKS_TOLERANCE).map { it.coordinate } else null
        }
    val markers = libraryRepository.steps(itineraryId).sortedBy { it.stepId }
        .mapIndexedNotNull { offset, step -> step.coordinate()?.let { NavigationMarker(offset + 1, it) } }

    val latMin = itinerary.latMin
    val latMax = itinerary.latMax
    val lonMin = itinerary.lonMin
    val lonMax = itinerary.lonMax
    val bounds = if (latMin != null && latMax != null && lonMin != null && lonMax != null) {
        GeoBounds(latMin, latMax, lonMin, lonMax)
    } else {
        GeoBounds.containing(own.flatten() + markers.map { it.coordinate })
    }
    NavigationMapContent(itinerary, own, others, markers, bounds)
}
