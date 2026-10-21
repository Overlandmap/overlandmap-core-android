package ch.overlandmap.map.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ch.overlandmap.map.model.BorderOpenState
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.CountryCarnet
import ch.overlandmap.map.model.CountryInsurance
import ch.overlandmap.map.model.CountryOverlanding
import ch.overlandmap.map.model.CountryVisa
import ch.overlandmap.map.model.Extra
import ch.overlandmap.map.model.GlobalSetting
import ch.overlandmap.map.model.InsuranceScheme
import ch.overlandmap.map.model.Zone
import ch.overlandmap.map.model.boundsFromJsonArray
import ch.overlandmap.map.model.buildJsonColumn
import ch.overlandmap.map.model.doubleOrNull
import ch.overlandmap.map.model.intOrNull
import ch.overlandmap.map.model.parseJsonColumn
import ch.overlandmap.map.model.putIfNotEmpty
import ch.overlandmap.map.model.putIfNotNull
import ch.overlandmap.map.model.stringListOrNull
import ch.overlandmap.map.model.stringMapOrNull
import ch.overlandmap.map.model.stringOrNull
import ch.overlandmap.map.model.without
import org.json.JSONArray
import org.json.JSONObject

/*
 * Room rows for the offline world data, column for column as in the iOS schema. The
 * overlanding/carnet/visa statuses, open states and `updatedAt` are promoted to columns
 * (the map styles on them); the rest of each object is packed into `json`.
 */

@Entity(tableName = "country")
data class CountryRow(
    @PrimaryKey val documentId: String,
    val name: String?,
    @ColumnInfo(name = "iso_a2", collate = ColumnInfo.NOCASE) val isoA2: String?,
    val overlanding: Int?,
    val carnet: Int?,
    val visa: Int?,
    val updatedAt: Long?,
    val json: String?,
)

fun Country.toRow() = CountryRow(
    documentId = documentId,
    name = name,
    isoA2 = isoA2,
    overlanding = overlanding,
    carnet = carnet,
    visa = visa,
    updatedAt = updatedAt,
    json = buildJsonColumn {
        putIfNotEmpty("translations", translations)
        putIfNotNull("continent", continent)
        putIfNotNull("capital", capital)
        putIfNotEmpty("capital_translations", capitalTranslations)
        putIfNotNull("area", area)
        putIfNotNull("population", population)
        putIfNotNull("currency", currency)
        putIfNotNull("currency_symbol", currencySymbol)
        putIfNotNull("driving", driving)
        putIfNotNull("timezone", timezone)
        putIfNotNull("adm0_a3", adm0A3)
        putIfNotEmpty("languages", languages)
        putIfNotNull("insurance", insurance)
        putIfNotNull("insurance_scheme", insuranceScheme?.raw)
        putIfNotNull("visa_url", visaUrl)
        putIfNotNull("insurance_url", insuranceUrl)
        putIfNotNull("comment", comment)
        putIfNotEmpty("comment_translations", commentTranslations)
        putIfNotNull("visa_comment", visaComment)
        putIfNotEmpty("visa_comment_translations", visaCommentTranslations)
        putIfNotNull("carnet_comment", carnetComment)
        putIfNotEmpty("carnet_comment_translations", carnetCommentTranslations)
        putIfNotNull("insurance_comment", insuranceComment)
        putIfNotEmpty("insurance_comment_translations", insuranceCommentTranslations)
        putIfNotNull("stay_duration", stayDuration)
        putIfNotEmpty("stay_duration_translations", stayDurationTranslations)
        putIfNotNull("money", money)
        putIfNotEmpty("money_translations", moneyTranslations)
        putIfNotNull("tip", tip)
        putIfNotEmpty("tip_translations", tipTranslations)
        putIfNotEmpty("borders", bordersMap)
        boundingBox?.let {
            put("bounding_box", JSONArray(listOf(it.lonMin, it.latMin, it.lonMax, it.latMax)))
        }
    },
)

fun CountryRow.toModel(): Country = parseJsonColumn(json).let { j ->
    Country(
        documentId = documentId,
        name = name ?: "",
        isoA2 = isoA2,
        translations = j.stringMapOrNull("translations"),
        continent = j.stringOrNull("continent"),
        capital = j.stringOrNull("capital"),
        capitalTranslations = j.stringMapOrNull("capital_translations"),
        area = j.doubleOrNull("area"),
        population = j.intOrNull("population"),
        currency = j.stringOrNull("currency"),
        currencySymbol = j.stringOrNull("currency_symbol"),
        driving = j.stringOrNull("driving"),
        timezone = j.stringOrNull("timezone"),
        adm0A3 = j.stringOrNull("adm0_a3"),
        languages = j.stringListOrNull("languages"),
        // An absent status column reads as iOS reads it: open / not required.
        overlanding = overlanding ?: CountryOverlanding.OPEN.raw,
        carnet = carnet ?: CountryCarnet.NOT_REQUIRED.raw,
        visa = visa ?: CountryVisa.UNKNOWN.raw,
        insurance = j.intOrNull("insurance") ?: CountryInsurance.UNKNOWN.raw,
        insuranceScheme = InsuranceScheme.fromRaw(j.stringOrNull("insurance_scheme")),
        visaUrl = j.stringOrNull("visa_url"),
        insuranceUrl = j.stringOrNull("insurance_url"),
        comment = j.stringOrNull("comment"),
        commentTranslations = j.stringMapOrNull("comment_translations"),
        visaComment = j.stringOrNull("visa_comment"),
        visaCommentTranslations = j.stringMapOrNull("visa_comment_translations"),
        carnetComment = j.stringOrNull("carnet_comment"),
        carnetCommentTranslations = j.stringMapOrNull("carnet_comment_translations"),
        insuranceComment = j.stringOrNull("insurance_comment"),
        insuranceCommentTranslations = j.stringMapOrNull("insurance_comment_translations"),
        stayDuration = j.stringOrNull("stay_duration"),
        stayDurationTranslations = j.stringMapOrNull("stay_duration_translations"),
        money = j.stringOrNull("money"),
        moneyTranslations = j.stringMapOrNull("money_translations"),
        tip = j.stringOrNull("tip"),
        tipTranslations = j.stringMapOrNull("tip_translations"),
        bordersMap = j.stringMapOrNull("borders"),
        boundingBox = boundsFromJsonArray(j.optJSONArray("bounding_box")?.let { a ->
            List(a.length()) { a.opt(it) }
        }),
        updatedAt = updatedAt,
    )
}

@Entity(
    tableName = "border",
    indices = [
        Index("country1", "country2", name = "idx_border_countries"),
        Index("country2", name = "idx_border_country2"),
        Index("is_open", name = "idx_border_isOpen"),
    ],
)
data class CountryBorderRow(
    @PrimaryKey val documentId: String,
    @ColumnInfo(name = "is_open") val isOpen: Int?,
    val country1: String?,
    val country2: String?,
    val updatedAt: Long?,
    val json: String?,
)

fun CountryBorder.toRow() = CountryBorderRow(
    documentId = documentId,
    isOpen = isOpen,
    country1 = country1,
    country2 = country2,
    updatedAt = updatedAt,
    json = buildJsonColumn {
        putIfNotNull("name", name)
        putIfNotNull("geomType", geomType)
        putIfNotNull("geomString", geomString)
        putIfNotNull("comment", comment)
        putIfNotEmpty("comment_translations", commentTranslations)
        putIfNotEmpty("border_posts", borderPostIds)
        boundingBox?.let {
            put("bounding_box", JSONArray(listOf(it.lonMin, it.latMin, it.lonMax, it.latMax)))
        }
    },
)

fun CountryBorderRow.toModel(): CountryBorder = parseJsonColumn(json).let { j ->
    CountryBorder(
        documentId = documentId,
        name = j.stringOrNull("name") ?: "",
        country1 = country1,
        country2 = country2,
        isOpen = isOpen ?: BorderOpenState.OPEN.raw,
        geomType = j.stringOrNull("geomType"),
        geomString = j.stringOrNull("geomString"),
        comment = j.stringOrNull("comment"),
        commentTranslations = j.stringMapOrNull("comment_translations"),
        borderPostIds = j.stringListOrNull("border_posts") ?: emptyList(),
        boundingBox = boundsFromJsonArray(j.optJSONArray("bounding_box")?.let { a ->
            List(a.length()) { a.opt(it) }
        }),
        updatedAt = updatedAt,
    )
}

@Entity(
    tableName = "border_post",
    indices = [
        Index("borderDocId", name = "idx_borderPost_border"),
        Index("geohash", name = "idx_borderPost_geohash"),
    ],
)
data class BorderPostRow(
    @PrimaryKey val documentId: String,
    val name: String?,
    @ColumnInfo(name = "is_open") val isOpen: Int?,
    val borderDocId: String?,
    val geohash: String?,
    val updatedAt: Long?,
    val json: String?,
)

fun BorderPost.toRow() = BorderPostRow(
    documentId = documentId,
    name = name,
    isOpen = isOpen,
    borderDocId = borderId,
    geohash = geohash,
    updatedAt = updatedAt,
    json = buildJsonColumn {
        putIfNotNull("countries", countries)
        putIfNotNull("lat", lat)
        putIfNotNull("lon", lon)
        putIfNotNull("comment", comment)
        putIfNotEmpty("comment_translations", commentTranslations)
    },
)

fun BorderPostRow.toModel(): BorderPost = parseJsonColumn(json).let { j ->
    BorderPost(
        documentId = documentId,
        name = name ?: "",
        countries = j.stringOrNull("countries"),
        isOpen = isOpen ?: BorderOpenState.OPEN.raw,
        lat = j.doubleOrNull("lat"),
        lon = j.doubleOrNull("lon"),
        geohash = geohash,
        comment = j.stringOrNull("comment"),
        commentTranslations = j.stringMapOrNull("comment_translations"),
        borderId = borderDocId,
        updatedAt = updatedAt,
    )
}

@Entity(
    tableName = "zone",
    indices = [Index("country", name = "idx_zone_country")],
)
data class ZoneRow(
    @PrimaryKey val documentId: String,
    val name: String?,
    val country: String?,
    val type: Int?,
    val updatedAt: Long?,
    val json: String?,
)

private val ZONE_COLUMNS = arrayOf("name", "country", "type", "updatedAt")

fun Zone.toRow() = ZoneRow(
    documentId = documentId,
    name = name,
    country = country,
    type = type,
    updatedAt = updatedAt,
    json = Extra.toJson(stored().without(*ZONE_COLUMNS)),
)

fun ZoneRow.toModel(): Zone = Zone.fromFirestore(
    documentId,
    Extra.fromJson(json) + columns(
        "name" to name, "country" to country, "type" to type, "updatedAt" to updatedAt,
    ),
)

/** One row per Firestore `global` document, its payload in the shared `json` shape. */
@Entity(tableName = "setting")
data class SettingRow(
    @PrimaryKey val documentId: String,
    val updatedAt: Long?,
    val json: String?,
)

fun GlobalSetting.toRow() = SettingRow(
    documentId = documentId,
    updatedAt = updatedAt,
    json = Extra.toJson(extra),
)

fun SettingRow.toModel(): GlobalSetting = GlobalSetting(
    documentId = documentId,
    updatedAt = updatedAt,
    extra = Extra.fromJson(json),
)
