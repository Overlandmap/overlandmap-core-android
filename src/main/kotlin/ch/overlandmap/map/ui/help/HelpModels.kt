package ch.overlandmap.map.ui.help

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** A localized string: language code → text (or image URL). */
typealias LocalizedText = Map<String, String>

/** The text for [lang], falling back to English then any available language. */
fun LocalizedText?.textFor(lang: String): String =
    this?.get(lang) ?: this?.get("en") ?: this?.values?.firstOrNull() ?: ""

/** A section on the help home screen (one entry of help.yaml). */
data class HelpSection(
    val id: String,
    val title: LocalizedText,
    val description: LocalizedText,
    /** Language → banner image URL (from the `image_<lang>` keys). */
    val image: LocalizedText,
)

/** A feature shown on a section's first carousel page. */
data class HelpFeature(
    val title: LocalizedText,
    val description: LocalizedText,
    val icon: String,
)

/** A tutorial page in a section's carousel: a title, an image, and text. */
data class HelpPage(
    val title: LocalizedText,
    /** One entry for a `body`, several for `paragraphs`. */
    val paragraphs: List<LocalizedText>,
    /** Language → screenshot URL (from `imageUrl`). */
    val image: LocalizedText,
)

/** The full content of a section (one `<section>.yaml`). */
data class HelpContent(
    val features: List<HelpFeature>,
    val pages: List<HelpPage>,
)

/**
 * Loads the help/tutorial content from the app's `help/` YAML assets. The
 * screens live in core but the YAML is shipped per app, so this reads through
 * the (merged) asset manager.
 */
class HelpRepository(private val context: Context) {

    /** The sections of the home screen, in file order. */
    suspend fun sections(): List<HelpSection> = withContext(Dispatchers.IO) {
        load("help/help.yaml").mapNotNull { (id, value) ->
            val map = value.asStringMap() ?: return@mapNotNull null
            HelpSection(
                id = id,
                title = map.localized("title"),
                description = map.localized("description"),
                image = map.imageByLang(),
            )
        }
    }

    /** The features and pages of one section (`<sectionId>.yaml`). */
    suspend fun content(sectionId: String): HelpContent = withContext(Dispatchers.IO) {
        val root = load("help/$sectionId.yaml")
        val features = (root["features"] as? List<*>).orEmpty().mapNotNull { entry ->
            val map = entry.singleValue().asStringMap() ?: return@mapNotNull null
            HelpFeature(
                title = map.localized("title"),
                description = map.localized("description"),
                // Some icons carry a stray trailing comma in the YAML.
                icon = (map["icon"] as? String).orEmpty().trim().trimEnd(',').trim(),
            )
        }
        val pages = (root["pages"] as? List<*>).orEmpty().mapNotNull { entry ->
            val map = entry.singleValue().asStringMap() ?: return@mapNotNull null
            HelpPage(
                title = map.localized("title"),
                paragraphs = map.paragraphs(),
                image = map.localized("imageUrl"),
            )
        }
        HelpContent(features, pages)
    }

    private fun load(path: String): Map<String, Any?> =
        runCatching {
            val yaml = Yaml(SafeConstructor(LoaderOptions()))
            context.assets.open(path).use { (yaml.load(it) as? Map<*, *>).asStringMap() ?: emptyMap() }
        }.getOrDefault(emptyMap())
}

// ── YAML shape helpers ──────────────────────────────────────────────────────

@Suppress("UNCHECKED_CAST")
private fun Any?.asStringMap(): Map<String, Any?>? =
    (this as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }

/** A list item like `- Home: { … }` — return the single value under its key. */
private fun Any?.singleValue(): Any? = (this as? Map<*, *>)?.values?.firstOrNull()

/** The localized-text map under [key] (language code → text). */
private fun Map<String, Any?>.localized(key: String): LocalizedText =
    (this[key] as? Map<*, *>)?.entries
        ?.mapNotNull { (k, v) -> if (v is String) k.toString() to v else null }
        ?.toMap()
        .orEmpty()

/** help.yaml banner image, from the flat `image_<lang>` keys → language → URL. */
private fun Map<String, Any?>.imageByLang(): LocalizedText =
    entries.mapNotNull { (k, v) ->
        if (k.startsWith("image_") && v is String) k.removePrefix("image_") to v else null
    }.toMap()

/** A page's text: its single `body`, or the `paragraphs` list of `text` blocks. */
private fun Map<String, Any?>.paragraphs(): List<LocalizedText> {
    localized("body").takeIf { it.isNotEmpty() }?.let { return listOf(it) }
    return (this["paragraphs"] as? List<*>).orEmpty().mapNotNull { p ->
        p.asStringMap()?.localized("text")?.takeIf { it.isNotEmpty() }
    }
}
