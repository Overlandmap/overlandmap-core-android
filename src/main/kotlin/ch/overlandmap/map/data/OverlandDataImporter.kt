package ch.overlandmap.map.data

import ch.overlandmap.map.data.local.FtsDoc
import ch.overlandmap.map.data.local.FtsIndex
import ch.overlandmap.map.data.local.WorldDao
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.Extra
import ch.overlandmap.map.model.Zone
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * Imports the gzipped entity dump published as the `countries` general asset (iOS
 * `OverlandDataImporter`): `{countries: [...], borders: [...], border_posts: [...], zones: [...]}`,
 * each item a Firestore-shaped map with its id under `id` or `documentId`. The dataset replaces
 * the tables and the search entries wholesale: the asset is authoritative.
 */
object OverlandDataImporter {

    class Parsed(
        val countries: List<Country>,
        val borders: List<CountryBorder>,
        val posts: List<BorderPost>,
        val zones: List<Zone>,
    )

    /** Decodes the JSON text of the dump; items without an id are skipped. */
    fun parse(json: String): Parsed {
        val root = JSONObject(json)

        fun <T> items(key: String, build: (String, Map<String, Any?>) -> T): List<T> {
            val array = root.optJSONArray(key) ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val obj = array.optJSONObject(i) ?: return@mapNotNull null
                val data = Extra.objectToMap(obj)
                val id = (data["id"] ?: data["documentId"]) as? String
                if (id.isNullOrEmpty()) null else build(id, data - "id" - "documentId")
            }
        }
        return Parsed(
            countries = items("countries", Country::fromFirestore),
            borders = items("borders", CountryBorder::fromFirestore),
            posts = items("border_posts", BorderPost::fromFirestore),
            zones = items("zones", Zone::fromFirestore),
        )
    }

    fun gunzip(file: File): String =
        GZIPInputStream(file.inputStream().buffered()).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Replaces the Overlanding tables and search entries with the dump in [file]. */
    suspend fun import(file: File, dao: WorldDao, fts: FtsIndex?) {
        val parsed = parse(gunzip(file))
        dao.replaceOverlandData(parsed.countries, parsed.borders, parsed.posts, parsed.zones)
        fts ?: return
        listOf(
            FtsIndex.TYPE_COUNTRY, FtsIndex.TYPE_BORDER, FtsIndex.TYPE_BORDER_POST, FtsIndex.TYPE_ZONE,
        ).forEach { fts.deleteType(it) }
        fts.index(
            buildList {
                parsed.countries.forEach { c ->
                    add(FtsDoc(FtsIndex.TYPE_COUNTRY, c.documentId, c::name) { c.comment(it).orEmpty() })
                }
                parsed.borders.forEach { b ->
                    add(FtsDoc(FtsIndex.TYPE_BORDER, b.documentId, { b.name }) { b.comment(it).orEmpty() })
                }
                parsed.posts.forEach { p ->
                    add(FtsDoc(FtsIndex.TYPE_BORDER_POST, p.documentId, { p.name }) { p.comment(it).orEmpty() })
                }
                parsed.zones.forEach { z ->
                    add(FtsDoc(FtsIndex.TYPE_ZONE, z.documentId, { z.name }) { z.comment(it).orEmpty() })
                }
            }
        )
    }
}
