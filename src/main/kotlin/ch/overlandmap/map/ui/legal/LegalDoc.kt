package ch.overlandmap.map.ui.legal

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R
import ch.overlandmap.map.ui.currentLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The two legal documents shipped as `legal/` Markdown assets. */
enum class LegalDoc(val slug: String, val fileBase: String, val titleRes: Int) {
    PRIVACY("privacy", "privacy", R.string.settings_privacy_policy),
    TERMS("terms", "tos", R.string.settings_terms_of_service);

    companion object {
        fun fromSlug(slug: String?): LegalDoc = entries.firstOrNull { it.slug == slug } ?: PRIVACY
    }
}

/** Reads a legal doc's Markdown for [lang], falling back to English. Only en/fr ship. */
fun loadLegalMarkdown(context: Context, doc: LegalDoc, lang: String): String {
    val suffix = if (lang == "fr") "fr" else "en"
    fun read(name: String) = context.assets.open("legal/$name").use { it.readBytes().decodeToString() }
    return try {
        read("${doc.fileBase}-$suffix.md")
    } catch (e: Exception) {
        read("${doc.fileBase}-en.md")
    }
}

/** Full-screen viewer for a legal document, opened from Settings and onboarding. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalDocScreen(doc: LegalDoc, onBack: () -> Unit) {
    val context = LocalContext.current
    val lang = currentLanguage()
    val markdown by produceState("", doc, lang) {
        value = withContext(Dispatchers.IO) { loadLegalMarkdown(context, doc, lang) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(doc.titleRes)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        MarkdownText(
            markdown,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        )
    }
}

/**
 * A small Markdown renderer — enough for the legal docs: ATX headings (`#`..`###`),
 * bullet (`-`/`*`) and numbered lists with nesting by indent, blank-line spacing,
 * paragraphs, and inline `**bold**`. Everything else renders as plain text.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val lines = remember(markdown) { markdown.replace("\r\n", "\n").split("\n") }
    Column(modifier) {
        for (raw in lines) {
            val line = raw.trimEnd()
            val trimmed = line.trimStart()
            val indent = line.length - trimmed.length
            when {
                line.isBlank() -> Spacer(Modifier.height(8.dp))
                trimmed.startsWith("### ") ->
                    Heading(trimmed.removePrefix("### "), MaterialTheme.typography.titleSmall)
                trimmed.startsWith("## ") ->
                    Heading(trimmed.removePrefix("## "), MaterialTheme.typography.titleMedium)
                trimmed.startsWith("# ") ->
                    Heading(trimmed.removePrefix("# "), MaterialTheme.typography.titleLarge)
                trimmed.startsWith("- ") || trimmed.startsWith("* ") ->
                    Bullet("•", trimmed.drop(2), indent)
                NUMBERED.matchEntire(trimmed) != null -> {
                    val m = NUMBERED.matchEntire(trimmed)!!
                    Bullet("${m.groupValues[1]}.", m.groupValues[2], indent)
                }
                else -> Text(
                    mdInline(line),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

private val NUMBERED = Regex("""(\d+)\.\s+(.*)""")
private val BOLD = Regex("""\*\*(.+?)\*\*""")

@Composable
private fun Heading(text: String, style: TextStyle) {
    Text(
        mdInline(text),
        style = style,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun Bullet(marker: String, text: String, indentChars: Int) {
    Row(Modifier.padding(start = (8 + indentChars * 4).dp, top = 2.dp, bottom = 2.dp)) {
        Text("$marker ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        Text(mdInline(text), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Renders inline `**bold**` runs; the rest is literal. */
private fun mdInline(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in BOLD.findAll(text)) {
        append(text.substring(last, m.range.first))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.groupValues[1]) }
        last = m.range.last + 1
    }
    append(text.substring(last))
}
