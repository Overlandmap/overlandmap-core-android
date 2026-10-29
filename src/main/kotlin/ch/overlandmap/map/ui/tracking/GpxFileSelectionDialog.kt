package ch.overlandmap.map.ui.tracking

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.overlandmap.map.R
import ch.overlandmap.map.tracking.GpxDownloadParser
import ch.overlandmap.map.tracking.GpxImportSelection
import ch.overlandmap.map.tracking.selectGpxDocuments

/** Checkbox, filename, and track-preview columns; previewing a file does not select it for import. */
@Composable
internal fun GpxFileSelectionDialog(
    documents: List<GpxDownloadParser.Document>,
    onDismiss: () -> Unit,
    onContinue: (GpxImportSelection) -> Unit,
) {
    var selected by remember(documents) { mutableStateOf(emptyList<Int>()) }
    var focused by remember(documents) { mutableIntStateOf(0) }
    val preview = documents.getOrNull(focused)
    val listHeight = (LocalConfiguration.current.screenHeightDp - 220).coerceIn(100, 400).dp
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.widthIn(max = 960.dp).fillMaxWidth(.95f), shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.gpx_choose_files), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.gpx_choose_files_hint), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
                Row(Modifier.fillMaxWidth().height(listHeight)) {
                    Column(Modifier.weight(1.2f)) {
                        Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
                            Spacer(Modifier.width(48.dp))
                            Text(stringResource(R.string.gpx_files), style = MaterialTheme.typography.titleSmall)
                        }
                        HorizontalDivider()
                        LazyColumn(Modifier.weight(1f)) {
                            itemsIndexed(documents) { index, doc ->
                                val selectLabel = stringResource(R.string.gpx_select_file, doc.fileName)
                                Row(Modifier.fillMaxWidth().background(
                                    if (focused == index) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                                ), verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = index in selected, onCheckedChange = { checked ->
                                        selected = if (checked) selected + index else selected - index
                                        focused = index
                                    }, modifier = Modifier.width(48.dp).semantics { contentDescription = selectLabel })
                                    Text(doc.fileName, style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f).clickable { focused = index }.padding(horizontal = 6.dp, vertical = 12.dp))
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    VerticalDivider(Modifier.padding(horizontal = 8.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
                            Text(stringResource(R.string.gpx_tracks), style = MaterialTheme.typography.titleSmall)
                        }
                        HorizontalDivider()
                        LazyColumn(Modifier.weight(1f)) {
                            items(preview?.tracks.orEmpty().filter { it.points.isNotEmpty() }, key = { it.id }) { track ->
                                Text(track.displayName, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp))
                                HorizontalDivider()
                            }
                        }
                        val waypoints = preview?.tracks.orEmpty().sumOf { it.waypoints.size }
                        if (waypoints > 0) Text("${stringResource(R.string.waypoints)}: $waypoints", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp))
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = selected.isNotEmpty(), onClick = {
                        selectGpxDocuments(documents, selected)?.let(onContinue)
                    }) { Text(stringResource(R.string.gpx_continue)) }
                }
            }
        }
    }
}
