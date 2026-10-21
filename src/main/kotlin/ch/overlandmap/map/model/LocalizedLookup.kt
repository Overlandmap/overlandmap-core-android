package ch.overlandmap.map.model

/**
 * Resolves a value out of a `{languageCode: value}` map for a requested language.
 * Port of iOS `LocalizedLookup`.
 *
 * Language codes reach the app in more than one shape. The app's own setting is a
 * bare code ("pt"), but authored data — translation maps written by the backend or
 * by hand — may carry a region ("pt-PT") or an underscore ("pt_BR"), in any case. An
 * exact match is tried first so a specific variant wins; otherwise the first key whose
 * *base* code matches is used, so selecting Portuguese still finds "pt-PT".
 */
object LocalizedLookup {

    /** Strips a region/script suffix and lowercases: "pt-PT" -> "pt", "PT_br" -> "pt". */
    fun canonicalCode(language: String): String {
        val lower = language.lowercase()
        val cut = lower.indexOfFirst { it == '-' || it == '_' }
        return if (cut >= 0) lower.substring(0, cut) else lower
    }

    /** The map's value for [language], or null when the map is empty or has no match. */
    fun <V> value(map: Map<String, V>?, language: String): V? {
        if (map.isNullOrEmpty()) return null
        map[language]?.let { return it }
        val requested = canonicalCode(language)
        // Sorted so the choice is deterministic when several keys share a base code
        // ("pt" and "pt-PT").
        for (key in map.keys.sorted()) {
            if (canonicalCode(key) == requested) return map[key]
        }
        return null
    }
}
