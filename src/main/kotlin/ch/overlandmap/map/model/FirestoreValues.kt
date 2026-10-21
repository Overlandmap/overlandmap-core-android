package ch.overlandmap.map.model

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.GeoPoint
import java.time.Instant

/**
 * Tolerant readers for raw Firestore document maps. Documents written over the
 * years store numbers as Long, Double or String, so every reader accepts what
 * it can and returns null otherwise. The rules mirror the iOS `JSONValue` /
 * `FirestoreTimestamp` decoders, so both apps read the same document the same way.
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

    /**
     * A boolean, or null when the field is absent or not boolean-like. Firestore exports
     * sometimes carry booleans as 0/1 (the spec stores `upVote` and `active` that way), so
     * a number counts as true when non-zero — exactly as iOS `JSONValue.boolValue`.
     */
    fun boolOrNull(v: Any?): Boolean? = when (v) {
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        else -> null
    }

    /** [boolOrNull] with absent = false (the convention for point-of-interest flags). */
    fun bool(v: Any?): Boolean = boolOrNull(v) ?: false

    /**
     * The open-state fields (`is_open`): an integer, or a legacy boolean where true means
     * open (2) and false closed (0) — iOS `raw.intValue ?? raw.boolValue.map { $0 ? 2 : 0 }`.
     */
    fun openState(v: Any?): Int? = when (v) {
        is Boolean -> if (v) 2 else 0
        else -> int(v)
    }

    /**
     * Epoch milliseconds from any of the shapes seen in real data: a Firestore
     * [Timestamp]; `{_seconds,_nanoseconds}` or `{seconds,nanoseconds}` maps; a bare number
     * (above 1e11 it is milliseconds, below it seconds); or an ISO-8601 string.
     */
    fun millis(v: Any?): Long? = when (v) {
        is Timestamp -> v.toDate().time
        is Map<*, *> -> {
            val seconds = double(v["_seconds"]) ?: double(v["seconds"])
            if (seconds == null) null
            else {
                val nanos = double(v["_nanoseconds"]) ?: double(v["nanoseconds"]) ?: 0.0
                (seconds * 1000 + nanos / 1_000_000).toLong()
            }
        }
        is Number -> {
            val n = v.toDouble()
            if (n > 100_000_000_000.0) n.toLong() else (n * 1000).toLong()
        }
        is String -> runCatching { Instant.parse(v).toEpochMilli() }.getOrNull()
        else -> null
    }

    fun geoLat(v: Any?): Double? = when (v) {
        is GeoPoint -> v.latitude
        is Map<*, *> -> double(v["_latitude"]) ?: double(v["latitude"])
        else -> null
    }

    fun geoLon(v: Any?): Double? = when (v) {
        is GeoPoint -> v.longitude
        is Map<*, *> -> double(v["_longitude"]) ?: double(v["longitude"])
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

    /**
     * A copy of [data] safe to store in a `json` column: Firestore types become the plain
     * shapes the exports use (timestamps as `{_seconds,_nanoseconds}`, geopoints as
     * `{latitude,longitude}`), and nulls are dropped. Mirrors what the iOS Firestore
     * client does before it hands a document to the models.
     */
    fun jsonSafe(data: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for ((key, value) in data) jsonSafeValue(value)?.let { out[key] = it }
        return out
    }

    private fun jsonSafeValue(v: Any?): Any? = when (v) {
        null -> null
        is Timestamp -> mapOf("_seconds" to v.seconds, "_nanoseconds" to v.nanoseconds.toLong())
        is GeoPoint -> mapOf("latitude" to v.latitude, "longitude" to v.longitude)
        is DocumentReference -> v.path
        is Map<*, *> -> {
            val m = LinkedHashMap<String, Any?>()
            for ((k, value) in v) if (k is String) jsonSafeValue(value)?.let { m[k] = it }
            m
        }
        is Iterable<*> -> v.mapNotNull { jsonSafeValue(it) }
        is String, is Number, is Boolean -> v
        else -> v.toString()
    }
}

/**
 * Resolves a translatable text: the translation for [lang] when present (a bare "pt"
 * also finds a "pt-PT" key, see [LocalizedLookup]), otherwise the original.
 */
fun localized(original: String?, translations: Map<String, String>?, lang: String): String? =
    LocalizedLookup.value(translations, lang) ?: original
