package ch.overlandmap.map.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Helpers for the `json` text column that every entity carries. Only the
 * fields used to query a table are real SQL columns; everything else is packed
 * into `json`, so the model can gain fields without a database migration.
 *
 * Packing writes only meaningful values — nulls, empty maps/lists and `false`
 * flags are omitted — and reading treats a missing key as absent (null, or
 * `false` for a flag). See each model's `packed()` / `hydrated()`.
 */

/** Builds a JSON string from [build]; keys left unset are simply absent. */
inline fun buildJsonColumn(build: JSONObject.() -> Unit): String =
    JSONObject().apply(build).toString()

/** Parses a `json` column, tolerating null/empty/corrupt content. */
fun parseJsonColumn(json: String?): JSONObject =
    if (json.isNullOrEmpty()) JSONObject()
    else runCatching { JSONObject(json) }.getOrDefault(JSONObject())

fun JSONObject.putIfNotNull(key: String, value: String?) {
    if (value != null) put(key, value)
}

fun JSONObject.putIfNotNull(key: String, value: Number?) {
    if (value != null) put(key, value)
}

/** Writes a flag only when true; a missing key reads back as false. */
fun JSONObject.putIfTrue(key: String, value: Boolean) {
    if (value) put(key, true)
}

fun JSONObject.putIfNotEmpty(key: String, value: Map<String, String>?) {
    if (!value.isNullOrEmpty()) put(key, JSONObject(value as Map<*, *>))
}

fun JSONObject.putIfNotEmpty(key: String, value: List<String>?) {
    if (!value.isNullOrEmpty()) put(key, JSONArray(value))
}

fun JSONObject.stringOrNull(key: String): String? =
    if (has(key) && !isNull(key)) optString(key) else null

fun JSONObject.intOrNull(key: String): Int? =
    if (has(key) && !isNull(key)) optInt(key) else null

fun JSONObject.longOrNull(key: String): Long? =
    if (has(key) && !isNull(key)) optLong(key) else null

fun JSONObject.doubleOrNull(key: String): Double? =
    if (has(key) && !isNull(key)) optDouble(key) else null

fun JSONObject.flag(key: String): Boolean = optBoolean(key, false)

fun JSONObject.stringMapOrNull(key: String): Map<String, String>? {
    val obj = optJSONObject(key) ?: return null
    val out = LinkedHashMap<String, String>(obj.length())
    obj.keys().forEach { out[it] = obj.optString(it) }
    return out.ifEmpty { null }
}

fun JSONObject.stringListOrNull(key: String): List<String>? {
    val arr = optJSONArray(key) ?: return null
    return List(arr.length()) { arr.optString(it) }.ifEmpty { null }
}
