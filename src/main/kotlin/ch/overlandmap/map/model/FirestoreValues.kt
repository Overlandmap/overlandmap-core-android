package ch.overlandmap.map.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.GeoPoint

/**
 * Tolerant readers for raw Firestore document maps. Documents written over the
 * years store numbers as Long, Double or String, so every reader accepts what
 * it can and returns null otherwise.
 *
 * The same maps also come from a pack zip's `db.json` (a Firestore export),
 * where timestamps are `{_seconds, _nanoseconds}` and geopoints are
 * `{_latitude, _longitude}` maps; [millis], [geoLat] and [geoLon] read both.
 */
object FS {
    fun str(v: Any?): String? = v as? String

    fun double(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }

    fun int(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    fun bool(v: Any?): Boolean = v as? Boolean ?: false

    fun millis(v: Any?): Long? = when (v) {
        is Timestamp -> v.toDate().time
        is Map<*, *> -> double(v["_seconds"])?.let { (it * 1000).toLong() }
        else -> null
    }

    fun geoLat(v: Any?): Double? = when (v) {
        is GeoPoint -> v.latitude
        is Map<*, *> -> double(v["_latitude"])
        else -> null
    }

    fun geoLon(v: Any?): Double? = when (v) {
        is GeoPoint -> v.longitude
        is Map<*, *> -> double(v["_longitude"])
        else -> null
    }

    fun stringList(v: Any?): List<String>? =
        (v as? List<*>)?.filterIsInstance<String>()

    fun stringMap(v: Any?): Map<String, String>? =
        (v as? Map<*, *>)
            ?.mapNotNull { (k, value) ->
                if (k is String && value is String) k to value else null
            }
            ?.toMap()
            ?.ifEmpty { null }
}

/**
 * Resolves a translatable text: the translation for [lang] when present,
 * otherwise the original (usually English) text.
 */
fun localized(original: String?, translations: Map<String, String>?, lang: String): String? =
    translations?.get(lang) ?: original
