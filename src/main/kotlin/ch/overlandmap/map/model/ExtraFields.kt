package ch.overlandmap.map.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * The "everything else" blob of a stored object: the document's fields that aren't promoted
 * to SQL columns, kept as plain JSON-shaped values (String, Number, Boolean, List, Map) and
 * read through tolerant typed accessors. Mirrors iOS `ExtraFields`, so a field added
 * upstream survives a round-trip through the `json` column without a schema change.
 *
 * Android's `org.json` has no `toMap`, hence the small converters here.
 */
object Extra {

    /** Serialises [map] for a `json` column, dropping nulls and empty maps/lists. */
    fun toJson(map: Map<String, Any?>): String = JSONObject(wrapMap(map)).toString()

    /** Parses a `json` column into a plain map; corrupt or empty content gives an empty map. */
    fun fromJson(json: String?): Map<String, Any?> {
        if (json.isNullOrEmpty()) return emptyMap()
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        return objectToMap(obj)
    }

    fun objectToMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            unwrap(obj.get(k))?.let { out[k] = it }
        }
        return out
    }

    private fun unwrap(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is JSONObject -> objectToMap(v)
        is JSONArray -> List(v.length()) { unwrap(v.get(it)) }
        else -> v
    }

    private fun wrapMap(map: Map<String, Any?>): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for ((k, v) in map) wrap(v)?.let { out[k] = it }
        return out
    }

    private fun wrap(v: Any?): Any? = when (v) {
        null -> null
        is Map<*, *> -> {
            val m = JSONObject()
            for ((k, value) in v) if (k is String) wrap(value)?.let { m.put(k, it) }
            m.takeIf { it.length() > 0 }
        }
        is Iterable<*> -> {
            val a = JSONArray()
            v.forEach { item -> wrap(item)?.let { a.put(it) } }
            a.takeIf { it.length() > 0 }
        }
        is String, is Number, is Boolean -> v
        else -> v.toString()
    }
}

/**
 * The unmodelled fields of an exported document that are cheap to keep: scalars and maps of strings
 * (translations), without the nested arrays (`steps`, photo lists…) that would bloat the row. Sidebar
 * table definitions and `sidebarDocId` markers live here, as in iOS `ExtraFields`.
 */
fun scalarExtras(data: Map<String, Any?>, vararg known: String): Map<String, Any?> =
    FS.jsonSafe(data).filter { (key, value) ->
        key !in known && when (value) {
            is String, is Number, is Boolean -> true
            is Map<*, *> -> value.values.all { it is String }
            else -> false
        }
    }

/** The subset of [this] not in [keys], for peeling promoted columns off a document. */
fun Map<String, Any?>.without(vararg keys: String): Map<String, Any?> =
    filterKeys { it !in keys }
