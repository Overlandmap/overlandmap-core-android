package ch.overlandmap.map.model


/** Enum ports of `models/country.dart`; raw values match the Firestore ints. */
enum class CountryOverlanding(val raw: Int, val color: Long) {
    FORBIDDEN(0, 0xFF1A1A1A), DANGEROUS(1, 0xFFDC2626), RESTRICTED(2, 0xFFEAB308),
    OPEN(3, 0xFF16A34A), UNKNOWN(4, 0xFF9CA3AF);

    companion object {
        fun fromRaw(raw: Int?) = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
    }
}

enum class CountryCarnet(val raw: Int) {
    NOT_REQUIRED(0), REQUIRED_IN_SOME_SITUATIONS(1), MANDATORY(2), UNKNOWN(3);

    companion object {
        fun fromRaw(raw: Int?) = entries.firstOrNull { it.raw == raw } ?: UNKNOWN
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

/**
 * A country with its overlanding facts (Firestore collection `country`).
 * Port of `models/country.dart`. Cached in Room so the world map works
 * offline.
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
    val overlanding: Int = CountryOverlanding.UNKNOWN.raw,
    val carnet: Int = CountryCarnet.UNKNOWN.raw,
    val visa: Int = CountryVisa.UNKNOWN.raw,
    val insurance: Int = CountryInsurance.UNKNOWN.raw,
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
    /** Border document IDs, keyed by ID with the border name as value. */
    val bordersMap: Map<String, String>? = null,
) {
    fun name(lang: String): String = localized(name, translations, lang) ?: name

    fun capital(lang: String): String? = localized(capital, capitalTranslations, lang)

    fun comment(lang: String): String? = localized(comment, commentTranslations, lang)

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
            capitalTranslations = FS.stringMap(data["capitalTranslations"]),
            area = FS.double(data["area"]),
            population = FS.int(data["population"]),
            currency = FS.str(data["currency"]),
            currencySymbol = FS.str(data["currencySymbol"]),
            driving = FS.str(data["driving"]),
            timezone = FS.str(data["timezone"]),
            isoA2 = FS.str(data["iso_a2"]),
            adm0A3 = FS.str(data["adm0_a3"]),
            overlanding = FS.int(data["overlanding"]) ?: CountryOverlanding.UNKNOWN.raw,
            carnet = FS.int(data["carnet"]) ?: CountryCarnet.UNKNOWN.raw,
            visa = FS.int(data["visa"]) ?: CountryVisa.UNKNOWN.raw,
            insurance = FS.int(data["insurance"]) ?: CountryInsurance.UNKNOWN.raw,
            visaUrl = FS.str(data["visaUrl"]),
            insuranceUrl = FS.str(data["insuranceUrl"]),
            comment = FS.str(data["comment_original"]),
            commentTranslations = FS.stringMap(data["comment_translations"]),
            visaComment = FS.str(data["visa_comment_original"]),
            visaCommentTranslations = FS.stringMap(data["visa_comment_translations"]),
            carnetComment = FS.str(data["carnet_comment_original"]),
            carnetCommentTranslations = FS.stringMap(data["carnet_comment_translations"]),
            insuranceComment = FS.str(data["insurance_comment_original"]),
            insuranceCommentTranslations = FS.stringMap(data["insurance_comment_translations"]),
            stayDuration = FS.str(data["stay_duration_original"]),
            stayDurationTranslations = FS.stringMap(data["stay_duration_translations"]),
            bordersMap = FS.stringMap(data["bordersMap"]),
        )
    }
}
