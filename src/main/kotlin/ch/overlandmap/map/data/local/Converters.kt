package ch.overlandmap.map.data.local

import androidx.room.TypeConverter
import org.json.JSONArray
import org.json.JSONObject

/** Stores translation maps and ID lists as JSON text columns. */
class Converters {

    @TypeConverter
    fun stringMapToJson(map: Map<String, String>?): String? =
        map?.let { JSONObject(it).toString() }

    @TypeConverter
    fun jsonToStringMap(json: String?): Map<String, String>? {
        if (json.isNullOrEmpty()) return null
        val obj = JSONObject(json)
        return obj.keys().asSequence().associateWith { obj.getString(it) }.ifEmpty { null }
    }

    @TypeConverter
    fun stringListToJson(list: List<String>?): String? =
        list?.let { JSONArray(it).toString() }

    @TypeConverter
    fun jsonToStringList(json: String?): List<String>? {
        if (json.isNullOrEmpty()) return null
        val array = JSONArray(json)
        return List(array.length()) { array.getString(it) }
    }
}
