package ch.overlandmap.map.data

import ch.overlandmap.map.model.FS
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.Waypoint
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Everything a pack zip's `json/db.json` defines, ready for Room. */
data class ParsedPack(
    val pack: TrackPack,
    val itineraries: List<Itinerary>,
    val steps: List<ItineraryStep>,
    val tracks: List<Track>,
    val waypoints: List<Waypoint>,
    val sidebars: List<Sidebar>,
)

/**
 * Parses the `json/db.json` of a downloaded pack zip. The file is a Firestore
 * export: `{packId: {..., itineraries: {id: {..., steps: [...]}}},
 * tracks: {id: ...}, waypoints: {id: ...}, sidebars: {id: ...}}` — the same
 * field names as the live documents, so the models' `fromFirestore` parsers
 * are reused ([FS] understands the export's timestamp and geopoint maps).
 *
 * Photos in the json (`titlePhotoUrl`) are paths relative to the zip's
 * `images/` directory; they become absolute [photoDir] paths so the UI can
 * display them offline.
 */
object PackJsonImporter {

    fun parse(dbJson: File, photoDir: File): ParsedPack {
        val root = JSONObject(dbJson.readText()).toMap()

        val sidebarsJson = root.mapValue("sidebars")
        val tracksJson = root.mapValue("tracks")
        val waypointsJson = root.mapValue("waypoints")
        val packId = root.keys.firstOrNull { it !in setOf("sidebars", "tracks", "waypoints") }
            ?: throw IllegalArgumentException("db.json contains no track pack")
        val packJson = root.mapValue(packId)

        val itineraries = ArrayList<Itinerary>()
        val steps = ArrayList<ItineraryStep>()
        packJson.mapValue("itineraries").forEach { (itineraryId, data) ->
            val itineraryJson = data.asMap()
            itineraries += Itinerary.fromFirestore(itineraryId, itineraryJson)
                .copy(
                    localPhotoPath = localPhoto(itineraryJson, photoDir),
                    localOtherPhotoPaths = (itineraryJson["otherPhotoUrls"] as? List<*>)
                        ?.mapNotNull { FS.str(it)?.takeIf(String::isNotEmpty) }
                        ?.map { File(photoDir, it).path }
                        ?.ifEmpty { null },
                )
            (itineraryJson["steps"] as? List<*>).orEmpty().forEach { stepData ->
                val stepJson = stepData.asMap()
                // The export stores the step's document ID as a field, and
                // omits trackPackId (deletion cascades filter o      n it).
                steps += ItineraryStep
                    .fromFirestore(FS.str(stepJson["documentId"]) ?: "", itineraryId, stepJson)
                    .copy(trackPackId = packId, localPhotoPath = localPhoto(stepJson, photoDir))
            }
        }

        // A free sample also lists the full pack's other itineraries as
        // description-and-photo-only teasers (no steps, no tracks).
        val editor = FS.str(packJson["editor"]) ?: ""
        packJson.mapValue("buyable_itineraries").forEach { (itineraryId, data) ->
            val itineraryJson = data.asMap()
            itineraries += Itinerary.fromFirestore(itineraryId, itineraryJson).copy(
                isBuyable = true,
                localPhotoPath = buyablePhoto(itineraryJson, photoDir, editor, packId),
            )
        }

        // The export's track dicts carry no itineraryId; the itineraries' own
        // trackIds lists provide the linkage the track queries rely on.
        val trackOwner = HashMap<String, String>()
        itineraries.forEach { itinerary ->
            itinerary.trackIds.forEach { trackOwner[it] = itinerary.documentId }
        }

        return ParsedPack(
            pack = TrackPack.fromFirestore(packId, packJson)
                .copy(localPhotoPath = localPhoto(packJson, photoDir)),
            itineraries = itineraries,
            steps = steps.filter { it.documentId.isNotEmpty() },
            tracks = tracksJson.map { (id, data) ->
                val track = Track.fromFirestore(id, data.asMap())
                if (track.itineraryId.isEmpty()) {
                    track.copy(itineraryId = trackOwner[id] ?: "")
                } else {
                    track
                }
            },
            waypoints = waypointsJson.map { (id, data) -> Waypoint.fromFirestore(id, data.asMap()) },
            sidebars = sidebarsJson.map { (id, data) ->
                val sidebarJson = data.asMap()
                Sidebar.fromFirestore(id, sidebarJson)
                    .copy(localPhotoPath = localPhoto(sidebarJson, photoDir))
            },
        )
    }

    /** The json's relative `titlePhotoUrl` as an absolute path under [photoDir]. */
    private fun localPhoto(json: Map<String, Any?>, photoDir: File): String? =
        FS.str(json["titlePhotoUrl"])?.takeIf { it.isNotEmpty() }?.let { File(photoDir, it).path }

    /**
     * A buyable itinerary's `titlePhotoUrl` is an absolute web URL, but the
     * photo itself is bundled in the zip under the editor's directory. Null
     * (photo missing from the zip) falls back to the network URL derived from
     * `titlePhotoId`.
     */
    private fun buyablePhoto(
        json: Map<String, Any?>,
        photoDir: File,
        editor: String,
        packId: String,
    ): String? {
        val photoId = FS.str(json["titlePhotoId"])?.takeIf { it.isNotEmpty() } ?: return null
        return File(photoDir, "$editor/$packId/$photoId.jpg").takeIf { it.isFile }?.path
    }

    private fun Map<String, Any?>.mapValue(key: String): Map<String, Any?> =
        this[key].asMap()

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asMap(): Map<String, Any?> =
        this as? Map<String, Any?> ?: emptyMap()

    private fun JSONObject.toMap(): Map<String, Any?> =
        keys().asSequence().associateWith { unwrap(get(it)) }

    private fun JSONArray.toList(): List<Any?> = (0 until length()).map { unwrap(get(it)) }

    private fun unwrap(value: Any?): Any? = when (value) {
        JSONObject.NULL -> null
        is JSONObject -> value.toMap()
        is JSONArray -> value.toList()
        else -> value
    }
}
