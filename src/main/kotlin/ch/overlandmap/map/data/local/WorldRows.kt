package ch.overlandmap.map.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import ch.overlandmap.map.model.BorderOpenState
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.CountryCarnet
import ch.overlandmap.map.model.CountryInsurance
import ch.overlandmap.map.model.CountryOverlanding
import ch.overlandmap.map.model.CountryVisa
import ch.overlandmap.map.model.buildJsonColumn
import ch.overlandmap.map.model.doubleOrNull
import ch.overlandmap.map.model.intOrNull
import ch.overlandmap.map.model.parseJsonColumn
import ch.overlandmap.map.model.putIfNotEmpty
import ch.overlandmap.map.model.putIfNotNull
import ch.overlandmap.map.model.stringMapOrNull
import ch.overlandmap.map.model.stringOrNull

/* Room rows for the offline world data. See LibraryRows for the pattern. */

@Entity(tableName = "country")
data class CountryRow(
    @PrimaryKey val documentId: String,
    val name: String,
    val isoA2: String?,
    val json: String?,
)

fun Country.toRow() = CountryRow(
    documentId = documentId,
    name = name,
    isoA2 = isoA2,
    json = buildJsonColumn {
        putIfNotEmpty("translations", translations)
        putIfNotNull("continent", continent)
        putIfNotNull("capital", capital)
        putIfNotEmpty("capitalTranslations", capitalTranslations)
        putIfNotNull("area", area)
        putIfNotNull("population", population)
        putIfNotNull("currency", currency)
        putIfNotNull("currencySymbol", currencySymbol)
        putIfNotNull("driving", driving)
        putIfNotNull("timezone", timezone)
        putIfNotNull("adm0A3", adm0A3)
        putIfNotNull("overlanding", overlanding)
        putIfNotNull("carnet", carnet)
        putIfNotNull("visa", visa)
        putIfNotNull("insurance", insurance)
        putIfNotNull("visaUrl", visaUrl)
        putIfNotNull("insuranceUrl", insuranceUrl)
        putIfNotNull("comment", comment)
        putIfNotEmpty("commentTranslations", commentTranslations)
        putIfNotNull("visaComment", visaComment)
        putIfNotEmpty("visaCommentTranslations", visaCommentTranslations)
        putIfNotNull("carnetComment", carnetComment)
        putIfNotEmpty("carnetCommentTranslations", carnetCommentTranslations)
        putIfNotNull("insuranceComment", insuranceComment)
        putIfNotEmpty("insuranceCommentTranslations", insuranceCommentTranslations)
        putIfNotNull("stayDuration", stayDuration)
        putIfNotEmpty("stayDurationTranslations", stayDurationTranslations)
        putIfNotEmpty("bordersMap", bordersMap)
    },
)

fun CountryRow.toModel(): Country = parseJsonColumn(json).let { j ->
    Country(
        documentId = documentId,
        name = name,
        isoA2 = isoA2,
        translations = j.stringMapOrNull("translations"),
        continent = j.stringOrNull("continent"),
        capital = j.stringOrNull("capital"),
        capitalTranslations = j.stringMapOrNull("capitalTranslations"),
        area = j.doubleOrNull("area"),
        population = j.intOrNull("population"),
        currency = j.stringOrNull("currency"),
        currencySymbol = j.stringOrNull("currencySymbol"),
        driving = j.stringOrNull("driving"),
        timezone = j.stringOrNull("timezone"),
        adm0A3 = j.stringOrNull("adm0A3"),
        overlanding = j.intOrNull("overlanding") ?: CountryOverlanding.UNKNOWN.raw,
        carnet = j.intOrNull("carnet") ?: CountryCarnet.UNKNOWN.raw,
        visa = j.intOrNull("visa") ?: CountryVisa.UNKNOWN.raw,
        insurance = j.intOrNull("insurance") ?: CountryInsurance.UNKNOWN.raw,
        visaUrl = j.stringOrNull("visaUrl"),
        insuranceUrl = j.stringOrNull("insuranceUrl"),
        comment = j.stringOrNull("comment"),
        commentTranslations = j.stringMapOrNull("commentTranslations"),
        visaComment = j.stringOrNull("visaComment"),
        visaCommentTranslations = j.stringMapOrNull("visaCommentTranslations"),
        carnetComment = j.stringOrNull("carnetComment"),
        carnetCommentTranslations = j.stringMapOrNull("carnetCommentTranslations"),
        insuranceComment = j.stringOrNull("insuranceComment"),
        insuranceCommentTranslations = j.stringMapOrNull("insuranceCommentTranslations"),
        stayDuration = j.stringOrNull("stayDuration"),
        stayDurationTranslations = j.stringMapOrNull("stayDurationTranslations"),
        bordersMap = j.stringMapOrNull("bordersMap"),
    )
}

@Entity(tableName = "border")
data class CountryBorderRow(
    @PrimaryKey val documentId: String,
    val json: String?,
)

fun CountryBorder.toRow() = CountryBorderRow(
    documentId = documentId,
    json = buildJsonColumn {
        putIfNotNull("name", name)
        putIfNotNull("country1", country1)
        putIfNotNull("country2", country2)
        putIfNotNull("isOpen", isOpen)
        putIfNotNull("geomType", geomType)
        putIfNotNull("geomString", geomString)
        putIfNotNull("comment", comment)
        putIfNotEmpty("commentTranslations", commentTranslations)
        putIfNotEmpty("borderPostsIds", borderPostsIds)
    },
)

fun CountryBorderRow.toModel(): CountryBorder = parseJsonColumn(json).let { j ->
    CountryBorder(
        documentId = documentId,
        name = j.stringOrNull("name") ?: "",
        country1 = j.stringOrNull("country1"),
        country2 = j.stringOrNull("country2"),
        isOpen = j.intOrNull("isOpen") ?: BorderOpenState.UNKNOWN.raw,
        geomType = j.stringOrNull("geomType"),
        geomString = j.stringOrNull("geomString"),
        comment = j.stringOrNull("comment"),
        commentTranslations = j.stringMapOrNull("commentTranslations"),
        borderPostsIds = j.stringMapOrNull("borderPostsIds"),
    )
}

@Entity(tableName = "border_post")
data class BorderPostRow(
    @PrimaryKey val documentId: String,
    val json: String?,
)

fun BorderPost.toRow() = BorderPostRow(
    documentId = documentId,
    json = buildJsonColumn {
        putIfNotNull("name", name)
        putIfNotNull("countries", countries)
        putIfNotNull("isOpen", isOpen)
        putIfNotNull("lat", lat)
        putIfNotNull("lon", lon)
        putIfNotNull("geohash", geohash)
        putIfNotNull("comment", comment)
        putIfNotEmpty("commentTranslations", commentTranslations)
    },
)

fun BorderPostRow.toModel(): BorderPost = parseJsonColumn(json).let { j ->
    BorderPost(
        documentId = documentId,
        name = j.stringOrNull("name") ?: "",
        countries = j.stringOrNull("countries"),
        isOpen = j.intOrNull("isOpen") ?: BorderOpenState.UNKNOWN.raw,
        lat = j.doubleOrNull("lat"),
        lon = j.doubleOrNull("lon"),
        geohash = j.stringOrNull("geohash"),
        comment = j.stringOrNull("comment"),
        commentTranslations = j.stringMapOrNull("commentTranslations"),
    )
}
