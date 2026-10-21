package ch.overlandmap.map.model

/** One "Official website" row: a government's travel advice for the country on screen. */
data class TravelAdvisoryLink(
    /** Alpha-3 of the country giving the advice (`GBR`, `USA`, `CAN`). */
    val sourceAlpha3: String,
    /** That country's localized name. */
    val sourceName: String,
    val url: String,
)

/** Builds the advisory rows from the `advisory_url_template` global setting (iOS `TravelAdvisoryLinks`). */
object TravelAdvisoryLinks {

    /**
     * Template keys are `<alpha3>_<language>` (`"gbr_en"`); only entries for [language] are used.
     * `{translation}` becomes the subject country's name lowercased and `{Translation}` the name as
     * written. Rows are sorted by name (Firestore returns the map unordered).
     */
    fun make(
        templates: Map<String, String>,
        language: String,
        countryName: String,
        nameForAlpha3: (String) -> String?,
    ): List<TravelAdvisoryLink> = templates.mapNotNull { (key, template) ->
        val alpha3 = sourceAlpha3(key, language) ?: return@mapNotNull null
        val url = template
            .replace("{translation}", countryName.lowercase())
            .replace("{Translation}", countryName)
        if (url.isBlank() || url.contains(' ')) return@mapNotNull null
        TravelAdvisoryLink(alpha3, nameForAlpha3(alpha3) ?: alpha3, url)
    }.sortedWith { a, b -> NaturalOrder.compare(a.sourceName, b.sourceName) }

    /** `"gbr_en"` → `"GBR"` for an English request; null when the key isn't for this language. */
    private fun sourceAlpha3(key: String, language: String): String? {
        val parts = key.split('_', limit = 2).filter { it.isNotEmpty() }
        if (parts.size != 2 || parts[0].length != 3 || !key.endsWith(language)) return null
        return parts[0].uppercase()
    }
}
