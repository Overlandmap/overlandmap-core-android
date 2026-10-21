package ch.overlandmap.map.ui.social

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R
import ch.overlandmap.map.ui.theme.contentTextStyle

/**
 * User text with its machine translation (iOS `TranslatedContentView`): the translation is shown
 * when there is one that differs from the [original], with "Translated by AI · Show original" to
 * flip back. An empty original reads as the italic "Valid" (a check-in without a message).
 */
@Composable
fun TranslatedContent(
    original: String?,
    translation: String?,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    var showOriginal by remember { mutableStateOf(false) }
    val hasTranslation = !translation.isNullOrBlank() && translation != original
    Column(modifier) {
        if (original.isNullOrBlank()) {
            Text(
                stringResource(R.string.validated),
                style = contentTextStyle(MaterialTheme.typography.bodySmall),
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Text(
            if (hasTranslation && !showOriginal) translation!! else original,
            style = contentTextStyle(MaterialTheme.typography.bodyMedium),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasTranslation) {
            TextButton(onClick = { showOriginal = !showOriginal }, modifier = Modifier.padding(0.dp)) {
                Text(
                    stringResource(R.string.translated_by_ai) + " · " +
                        stringResource(if (showOriginal) R.string.translate else R.string.show_original),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
