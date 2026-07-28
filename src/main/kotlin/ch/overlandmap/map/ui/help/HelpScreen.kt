package ch.overlandmap.map.ui.help

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R
import ch.overlandmap.map.ui.currentLanguage

/** The blue used for the feature titles/links and icons on the help screens. */
internal val HelpLink = Color(0xFF1976D2)

/**
 * Help/tutorial home: the app icon on top, then the sections of `help.yaml` as a
 * numbered list (title + subtitle). Shown as onboarding on first launch and from
 * the in-app Help button. Tapping a feature opens its carousel ([HelpSectionScreen]).
 */
@Composable
fun HelpScreen(
    onOpenSection: (String) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val lang = currentLanguage()
    val repo = remember { HelpRepository(context) }
    val sections by produceState<List<HelpSection>?>(initialValue = null) {
        value = repo.sections()
    }

    Column(Modifier.fillMaxSize()) {
        // Header: app icon, title, and a help/close action on the right.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier.size(64.dp),
            )
            Text(
                stringResource(R.string.help),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        }

        val list = sections
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            itemsIndexed(list, key = { _, s -> s.id }) { index, section ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenSection(section.id) },
                ) {
                    Text(
                        "${index + 1}. ${section.title.textFor(lang)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = HelpLink,
                    )
                    section.description.textFor(lang).takeIf { it.isNotEmpty() }?.let { subtitle ->
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
