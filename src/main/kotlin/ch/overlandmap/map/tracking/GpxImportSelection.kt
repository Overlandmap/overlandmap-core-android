package ch.overlandmap.map.tracking

internal data class GpxImportSelection(val folderName: String, val tracks: List<RecordedTrack>)

/** Preserve selection order so the first checked file supplies the proposed folder name. */
internal fun selectGpxDocuments(documents: List<GpxDownloadParser.Document>, selected: List<Int>): GpxImportSelection? {
    val files = selected.distinct().mapNotNull { documents.getOrNull(it) }
    val first = files.firstOrNull() ?: return null
    return GpxImportSelection(first.fileName.substringBeforeLast('.'), files.flatMap { it.tracks })
}
