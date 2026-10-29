package ch.overlandmap.map.tracking

import ch.overlandmap.map.geo.GeoBounds
import org.junit.Assert.*
import org.junit.Test

class DownloadExtentsTest {
    private val first = TrackFolder("first", "website", "First", 0)
    private val second = TrackFolder("second", "website", "Second", 0)
    private val other = TrackFolder("other", "other-website", "Other", 0)

    private fun track(folder: String, south: Double, west: Double, north: Double, east: Double) = RecordedTrack(
        fileName = "$folder.json", startDateMs = 0, endDateMs = 0, isImported = true, trackFolderId = folder,
        // A legacy or stale website ID must not override the folder's parent.
        downloadWebsiteId = "outdated",
        points = listOf(RecordedPoint(south, west, timestampMs = 0), RecordedPoint(north, east, timestampMs = 0)),
    )

    @Test fun websiteMergesAllChildFolderExtentsIncludingWaypoints() {
        val a = track(first.id, 10.0, 20.0, 11.0, 21.0).copy(waypoints = listOf(
            RecordedWaypoint(latitude = 9.0, longitude = 19.0, timestampMs = 0)))
        val b = track(second.id, 30.0, 40.0, 31.0, 41.0)
        val unrelated = track(other.id, -50.0, -60.0, -49.0, -59.0)
        val result = calculateDownloadExtents(listOf(first, second, other), listOf(a, b, unrelated))
        assertEquals(GeoBounds(9.0, 11.0, 19.0, 21.0), result.folders[first.id])
        assertEquals(GeoBounds(9.0, 31.0, 19.0, 41.0), result.websites["website"])
        assertEquals(unrelated.bounds, result.websites["other-website"])
    }

    @Test fun parentShrinksWhenContentOrFoldersAreRemoved() {
        val a = track(first.id, 10.0, 20.0, 11.0, 21.0)
        val b = track(second.id, 30.0, 40.0, 31.0, 41.0)
        val cleared = calculateDownloadExtents(listOf(first, second), listOf(a))
        assertNull(cleared.folders[second.id])
        assertEquals(a.bounds, cleared.websites["website"])
        val deleted = calculateDownloadExtents(listOf(first), listOf(a, b))
        assertEquals(a.bounds, deleted.websites["website"])
        assertNull(calculateDownloadExtents(listOf(first, second), emptyList()).websites["website"])
        assertNull(calculateDownloadExtents(emptyList(), listOf(a, b)).websites["website"])
    }
}
