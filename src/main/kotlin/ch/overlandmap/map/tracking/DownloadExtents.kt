package ch.overlandmap.map.tracking

import ch.overlandmap.map.geo.GeoBounds

internal data class DownloadExtents(
    val folders: Map<String, GeoBounds?>,
    val websites: Map<String, GeoBounds?>,
)

/** Folder membership determines the website extent, even for older tracks lacking a website ID. */
internal fun calculateDownloadExtents(folders: List<TrackFolder>, tracks: List<RecordedTrack>): DownloadExtents {
    val tracksByFolder = tracks.groupBy { it.trackFolderId }
    val folderBounds = folders.associate { folder ->
        folder.id to GeoBounds.containing(tracksByFolder[folder.id].orEmpty().flatMap { track ->
            track.coordinates + track.waypoints.map { it.coordinate }
        })
    }
    val websiteBounds = folders.filter { it.websiteId != null }.groupBy { it.websiteId!! }.mapValues { (_, children) ->
        GeoBounds.union(children.mapNotNull { folderBounds[it.id] })
    }
    return DownloadExtents(folderBounds, websiteBounds)
}
