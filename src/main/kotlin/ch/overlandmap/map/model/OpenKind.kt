package ch.overlandmap.map.model

/**
 * Access / opening status of a step or waypoint (port of the Flutter app's
 * `OpenKind`). [raw] is the value stored in Firestore and the pack json.
 */
enum class OpenKind(val raw: String) {
    OPEN("open"),
    CLOSED("closed"),
    TEMP_CLOSED("tempClosed"),
    PERMIT("permit"),
    RESTRICTED("restricted"),
    TOLL("toll"),
    OTHER("other");

    companion object {
        /** The kind for [raw]: null when absent, [OTHER] when unrecognized (as on iOS and in Dart). */
        fun fromRaw(raw: String?): OpenKind? =
            raw?.let { r -> entries.firstOrNull { it.raw == r } ?: OTHER }
    }
}
