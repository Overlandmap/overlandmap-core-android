package ch.overlandmap.map.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.local.DiscussionRow
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.ui.theme.contentTextStyle
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val CorrectionRed = Color(0xFFE53935)

/**
 * Correction button for a step. Shows an outlined error icon when there are no
 * corrections, or a filled icon (always red) with a badge count (>1) when there
 * are corrections.
 *
 * - No corrections -> press opens the "suggest a correction" dialog.
 * - Has corrections -> press opens a bottom sheet listing them, with the option
 *   to add a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrectionButton(step: ItineraryStep) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    val corrections by app.socialRepository.observeCorrections(step.documentId)
        .collectAsState(initial = emptyList())

    // Sync discussions from Firestore the first time this step is viewed.
    LaunchedEffect(step.documentId) {
        runCatching { app.socialRepository.syncDiscussionsForObject(step.documentId) }
    }

    var showSheet by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    val count = corrections.size

    // The icon button with optional badge.
    IconButton(onClick = {
        if (count == 0) showDialog = true else showSheet = true
    }) {
        if (count == 0) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = stringResource(R.string.corrections),
                tint = CorrectionRed,
            )
        } else if (count == 1) {
            Icon(
                Icons.Filled.ErrorOutline,
                contentDescription = stringResource(R.string.corrections),
                tint = CorrectionRed,
            )
        } else {
            BadgedBox(badge = {
                Badge { Text("$count") }
            }) {
                Icon(
                    Icons.Filled.ErrorOutline,
                    contentDescription = stringResource(R.string.corrections),
                    tint = CorrectionRed,
                )
            }
        }
    }

    // ── Bottom sheet: list of corrections ───────────────────────────────────
    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.corrections),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(corrections, key = { it.documentId }) { correction ->
                        CorrectionListItem(correction)
                        HorizontalDivider()
                    }
                }

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        showSheet = false
                        showDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.suggest_correction))
                }
            }
        }
    }

    // ── Add correction dialog ───────────────────────────────────────────────
    if (showDialog) {
        CorrectionDialog(
            onDismiss = { showDialog = false },
            onConfirm = { content ->
                showDialog = false
                scope.launch {
                    runCatching {
                        app.socialRepository.createCorrection(
                            objectId = step.documentId,
                            content = content,
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun CorrectionListItem(correction: DiscussionRow) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    correction.userId ?: "",
                    style = contentTextStyle(MaterialTheme.typography.bodyMedium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                correction.createdAt?.let { ts ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ts)),
                        style = contentTextStyle(MaterialTheme.typography.labelSmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Content is stored in the json field; for now show the topic or a
            // placeholder. A full implementation would parse the json blob.
            correction.topic?.let {
                Text(
                    it,
                    style = contentTextStyle(MaterialTheme.typography.bodySmall),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CorrectionDialog(
    onDismiss: () -> Unit,
    onConfirm: (content: String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.suggest_correction)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.correction_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                enabled = text.isNotBlank(),
            ) {
                Text(stringResource(R.string.confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
