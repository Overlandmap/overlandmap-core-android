package ch.overlandmap.map.model

import ch.overlandmap.map.AppConfig

/**
 * An informational article of a track pack (Firestore collection `sidebar`).
 * Port of `models/sidebar.dart`, reduced to the displayable fields.
 */
data class Sidebar(
    val documentId: String,
    val trackPackId: String,
    val name: String,
    val translatedName: Map<String, String>? = null,
    val description: String? = null,
    val translatedDesc: Map<String, String>? = null,
    val titlePhotoId: String? = null,
    val titlePhotoCaption: String? = null,
    /** Absolute path of the title photo unpacked from a downloaded zip. */
    val localPhotoPath: String? = null,
) {
    val titlePhotoUrl: String?
        get() = localPhotoPath?.let { "file://$it" } ?: titlePhotoId?.let(AppConfig::photoUrl)

    fun name(lang: String): String = localized(name, translatedName, lang) ?: name

    fun description(lang: String): String? = localized(description, translatedDesc, lang)

    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>) = Sidebar(
            documentId = documentId,
            trackPackId = FS.str(data["trackPackId"]) ?: "",
            name = FS.str(data["name"]) ?: "",
            translatedName = FS.stringMap(data["translatedName"]),
            description = FS.str(data["description"]),
            translatedDesc = FS.stringMap(data["translatedDesc"]),
            titlePhotoId = FS.str(data["titlePhotoId"]),
            titlePhotoCaption = FS.str(data["titlePhotoCaption"]),
        )
    }
}
