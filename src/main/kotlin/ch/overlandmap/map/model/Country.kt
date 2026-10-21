package ch.overlandmap.map.model

import ch.overlandmap.map.geo.GeoBounds

/**
 * Enum ports of iOS `Overland.swift`; raw values match the Firestore ints.
 *
 * The *absent* semantics differ per enum and matter: an absent `overlanding` means OPEN
 * (no restriction recorded) and an absent `carnet` means NOT_REQUIRED, exactly as
 * `fromInt` in Dart and `init(raw:)` in Swift. Anything unrecognised is UNKNOWN.
 */
enum class CountryOverlanding(val raw: Int, val color: Long) {
    FORBIDDEN(0, 0xFF1A1A1A), DANGEROUS(1, 0xFFDC2626), RESTRICTED(2, 0xFFEAB308),
    OPEN(3, 0xFF16A34A), UNKNOWN(4, 0xFF9CA3AF);

    companion object {
        fun fromRaw(raw: Int?): CountryOverlanding =
            if (raw == null) OPEN else entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

enum class CountryCarnet(val raw: Int) {
    /** The vehicle itself may not enter. */
    ACCESS_FORBIDDEN(-1), NOT_REQUIRED(0), REQUIRED_IN_SOME_SITUATIONS(1), MANDATORY(2), UNKNOWN(3);

    companion object {
        fun fromRaw(raw: Int?): CountryCarnet =
            if (raw == null) NOT_REQUIRED else entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

enum class CountryVisa(val raw: Int) {
    NOT_REQUIRED(0), BORDER(1), E_VISA(2), EMBASSY(3), RESTRICTED(4), UNKNOWN(5);

    companion object {
        fun fromRaw(raw: Int?) = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

enum class CountryInsurance(val raw: Int) {
    NOT_REQUIRED(0), AT_BORDER(1), ONLINE(2), OTHER(3), UNKNOWN(4);

    companion object {
        fun fromRaw(raw: Int?) = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/** A regional insurance convention (`insurance_scheme`). */
enum class InsuranceScheme(val raw: String) {
    GREEN_CARD("green_card"), BROWN_CARD("brown_card"), YELLOW_CARD("yellow_card"),
    SOAT("soat"), MERCOSUR("mercosur");

    companion object {
        fun fromRaw(raw: String?) = entries.firstOrNull { it.raw == raw }
    }
}

/** Severity of a travel-advisory [Zone]. */
enum class ZoneType(val raw: Int) {
    CLOSED(0), GUIDE_ESCORT_NEEDED(1), PERMIT_NEEDED(2), SPECIAL(3), UNKNOWN(-1);

    companion object {
        fun fromRaw(raw: Int?) = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

/**
 * `bounding_box`: `[southWestLng, southWestLat, northEastLng, northEastLat]`, the order
 * Dart's `Country.fromJson` and iOS `GeoBounds(jsonArray:)` read it in.
 */
fun boundsFromJsonArray(v: Any?): GeoBounds? {
    val a = (v as? List<*>)?.map { FS.double(it) ?: return null } ?: return null
    if (a.size < 4) return null
    return GeoBounds(latMin = a[1], latMax = a[3], lonMin = a[0], lonMax = a[2])
}


/**
 * A country with its overlanding facts (Firestore collection `country`). Port of iOS
 * `Country` / Dart `models/country.dart`. Cached in Room so the world map works offline.
 *
 * Firestore keys (snake_case, as iOS and Dart read them): `comment`, `visa_comment`,
 * `carnet_comment`, `insurance_comment`, `stay_duration`, `money`, `tip` — each with a
 * `<key>_translations` map — plus `capital_translations`, `currency_symbol`, `visa_url`,
 * `insurance_url`, `insurance_scheme`, `languages`, `borders`, `bounding_box`.
 */
data class Country(
    val documentId: String,
    val name: String,
    val translations: Map<String, String>? = null,
    val continent: String? = null,
    val capital: String? = null,
    val capitalTranslations: Map<String, String>? = null,
    val area: Double? = null,
    val population: Int? = null,
    val currency: String? = null,
    val currencySymbol: String? = null,
    val driving: String? = null,
    val timezone: String? = null,
    val isoA2: String? = null,
    val adm0A3: String? = null,
    val languages: List<String>? = null,
    /** Absent = OPEN (no restriction recorded). */
    val overlanding: Int = CountryOverlanding.OPEN.raw,
    /** Absent = NOT_REQUIRED. */
    val carnet: Int = CountryCarnet.NOT_REQUIRED.raw,
    val visa: Int = CountryVisa.UNKNOWN.raw,
    val insurance: Int = CountryInsurance.UNKNOWN.raw,
    val insuranceScheme: InsuranceScheme? = null,
    val visaUrl: String? = null,
    val insuranceUrl: String? = null,
    val comment: String? = null,
    val commentTranslations: Map<String, String>? = null,
    val visaComment: String? = null,
    val visaCommentTranslations: Map<String, String>? = null,
    val carnetComment: String? = null,
    val carnetCommentTranslations: Map<String, String>? = null,
    val insuranceComment: String? = null,
    val insuranceCommentTranslations: Map<String, String>? = null,
    val stayDuration: String? = null,
    val stayDurationTranslations: Map<String, String>? = null,
    val money: String? = null,
    val moneyTranslations: Map<String, String>? = null,
    val tip: String? = null,
    val tipTranslations: Map<String, String>? = null,
    /** Neighbouring countries, keyed by ISO code with the country name as value. */
    val bordersMap: Map<String, String>? = null,
    /** `[swLng, swLat, neLng, neLat]`, see [boundsFromJsonArray]. */
    val boundingBox: GeoBounds? = null,
    /** Epoch milliseconds of the server-side `updatedAt`. */
    val updatedAt: Long? = null,
) {
    fun name(lang: String): String = localized(name, translations, lang) ?: name

    fun capital(lang: String): String? = localized(capital, capitalTranslations, lang)

    fun comment(lang: String): String? = localized(comment, commentTranslations, lang)
    fun visaComment(lang: String): String? = localized(visaComment, visaCommentTranslations, lang)
    fun carnetComment(lang: String): String? = localized(carnetComment, carnetCommentTranslations, lang)
    fun insuranceComment(lang: String): String? =
        localized(insuranceComment, insuranceCommentTranslations, lang)
    fun stayDuration(lang: String): String? = localized(stayDuration, stayDurationTranslations, lang)
    fun money(lang: String): String? = localized(money, moneyTranslations, lang)
    fun tip(lang: String): String? = localized(tip, tipTranslations, lang)

    val overlandingStatus get() = CountryOverlanding.fromRaw(overlanding)
    val carnetStatus get() = CountryCarnet.fromRaw(carnet)
    val visaStatus get() = CountryVisa.fromRaw(visa)
    val insuranceStatus get() = CountryInsurance.fromRaw(insurance)

    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>) = Country(
            documentId = documentId,
            name = FS.str(data["name"]) ?: "",
            translations = FS.stringMap(data["translations"]),
            continent = FS.str(data["continent"]),
            capital = FS.str(data["capital"]),
            capitalTranslations = FS.stringMap(data["capital_translations"]),
            area = FS.double(data["area"]),
            population = FS.int(data["population"]),
            currency = FS.str(data["currency"]),
            currencySymbol = FS.str(data["currency_symbol"]),
            driving = FS.str(data["driving"]),
            timezone = FS.str(data["timezone"]),
            isoA2 = FS.str(data["iso_a2"]),
            adm0A3 = FS.str(data["adm0_a3"]) ?: documentId,
            languages = FS.stringList(data["languages"]),
            overlanding = FS.int(data["overlanding"]) ?: CountryOverlanding.OPEN.raw,
            carnet = FS.int(data["carnet"]) ?: CountryCarnet.NOT_REQUIRED.raw,
            visa = FS.int(data["visa"]) ?: CountryVisa.UNKNOWN.raw,
            insurance = FS.int(data["insurance"]) ?: CountryInsurance.UNKNOWN.raw,
            insuranceScheme = InsuranceScheme.fromRaw(FS.str(data["insurance_scheme"])),
            visaUrl = FS.str(data["visa_url"]),
            insuranceUrl = FS.str(data["insurance_url"]),
            comment = FS.str(data["comment"]),
            commentTranslations = FS.stringMap(data["comment_translations"]),
            visaComment = FS.str(data["visa_comment"]),
            visaCommentTranslations = FS.stringMap(data["visa_comment_translations"]),
            carnetComment = FS.str(data["carnet_comment"]),
            carnetCommentTranslations = FS.stringMap(data["carnet_comment_translations"]),
            insuranceComment = FS.str(data["insurance_comment"]),
            insuranceCommentTranslations = FS.stringMap(data["insurance_comment_translations"]),
            stayDuration = FS.str(data["stay_duration"]),
            stayDurationTranslations = FS.stringMap(data["stay_duration_translations"]),
            money = FS.str(data["money"]),
            moneyTranslations = FS.stringMap(data["money_translations"]),
            tip = FS.str(data["tip"]),
            tipTranslations = FS.stringMap(data["tip_translations"]),
            bordersMap = FS.stringMap(data["borders"]),
            boundingBox = boundsFromJsonArray(data["bounding_box"]),
            updatedAt = FS.millis(data["updatedAt"]),
        )
    }
}
