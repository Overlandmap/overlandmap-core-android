package ch.overlandmap.map.ui.world

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.model.UniversalLink
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.home.CheckInButton
import ch.overlandmap.map.ui.home.CorrectionButton
import ch.overlandmap.map.ui.social.SocialTarget
import kotlinx.coroutines.flow.first

private data class LinkObject(val name: String, val collection: String, val id: String, val url: String?)

/** Object preview shared by markup in packs and in the Overlanding detail panes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OverlandingLinkSheet(selection: OverlandingSelection, onDismiss: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    val world = (context.applicationContext as OverlandApp).worldRepository
    val lang = currentLanguage()
    val item by produceState<LinkObject?>(null, selection, lang) {
        value = when (selection) {
            is OverlandingSelection.OfCountry -> world.country(selection.id)?.let {
                LinkObject(it.name(lang), "country", it.documentId, UniversalLink.Country(it.adm0A3 ?: it.documentId).url())
            }
            is OverlandingSelection.OfBorder -> world.border(selection.id)?.let {
                val data = OverlandData(countries = world.observeCountries().first())
                LinkObject(data.borderTitle(it, lang, it.name), "border", it.documentId, UniversalLink.Border(it.documentId).url())
            }
            is OverlandingSelection.OfBorderPost -> world.borderPost(selection.id)?.let {
                LinkObject(it.name, "border_post", it.documentId, UniversalLink.BorderPost(it.documentId).url())
            }
            is OverlandingSelection.OfZone -> world.zone(selection.id)?.let {
                LinkObject(it.name, "zone", it.documentId, UniversalLink.Zone(it.documentId).url())
            }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            item?.let { obj ->
                Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
                    Text(obj.name.ifEmpty { stringResource(R.string.no_name) }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(when (selection) {
                        is OverlandingSelection.OfCountry -> Icons.Filled.Public
                        is OverlandingSelection.OfBorder -> Icons.Filled.Timeline
                        is OverlandingSelection.OfBorderPost -> Icons.Filled.Place
                        is OverlandingSelection.OfZone -> Icons.Filled.Warning
                    }, contentDescription = stringResource(R.string.open_viewer), modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(20.dp))
                val target = SocialTarget(obj.id, "", "", obj.collection)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    // Opening an Overlanding selection also frames its bounds or point.
                    IconButton(onClick = onOpen) { Icon(Icons.Filled.ZoomIn, stringResource(R.string.zoom_in)) }
                    CheckInButton(target)
                    CorrectionButton(target)
                    IconButton(onClick = {
                        obj.url?.let { url ->
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, url)
                                putExtra(Intent.EXTRA_SUBJECT, obj.name)
                            }, null))
                        }
                    }) { Icon(Icons.Filled.Share, stringResource(R.string.share)) }
                }
            } ?: CircularProgressIndicator()
        }
    }
}
