package ch.overlandmap.map.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import ch.overlandmap.map.data.local.CheckInRow
import ch.overlandmap.map.model.ItineraryStep
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val CheckInGreen = Color(0xFF2E7D32)

/**
 * Check-in button for a step. Shows an outlined circle when there are no
 * check-ins, or a filled circle with a badge count when there are.
 *
 * - No check-ins → press opens the check-in dialog directly.
 * - Has check-ins → press opens a bottom sheet listing them, with the option
 *   to add a new one (if the user hasn't already) or delete the user's own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInButton(step: ItineraryStep) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    val checkIns by app.socialRepository.observeCheckInsForObject(step.documentId)
        .collectAsState(initial = emptyList())

    var showSheet by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    val count = checkIns.size

    // The icon button with optional badge.
    IconButton(onClick = {
        if (count == 0) showDialog = true else showSheet = true
    }) {
        if (count == 0) {
            Icon(
                Icons.Outlined.CheckCircle,
                contentDescription = stringResource(R.string.check_in),
                tint = CheckInGreen,
            )
        } else {
            BadgedBox(badge = {
                Badge { Text("$count") }
            }) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.check_in),
                    tint = CheckInGreen,
                )
            }
        }
    }

    // ── Bottom sheet: list of check-ins ─────────────────────────────────────
    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        val userHasCheckIn = checkIns.any { it.userId == currentUserId }

        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.check_ins_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(checkIns, key = { it.documentId }) { checkIn ->
                        CheckInListItem(
                            checkIn = checkIn,
                            isOwn = checkIn.userId == currentUserId,
                            onDelete = {
                                scope.launch {
                                    runCatching { app.socialRepository.deleteCheckIn(checkIn.documentId) }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }

                if (!userHasCheckIn) {
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            showSheet = false
                            showDialog = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.check_in))
                    }
                }
            }
        }
    }

    // ── Check-in dialog ─────────────────────────────────────────────────────
    if (showDialog) {
        CheckInDialog(
            onDismiss = { showDialog = false },
            onConfirm = { content ->
                showDialog = false
                scope.launch {
                    runCatching {
                        app.socialRepository.createCheckIn(
                            trackPackId = step.trackPackId,
                            itinDocumentId = step.itineraryId,
                            collection = "itinerary/${step.itineraryId}/steps",
                            objectId = step.documentId,
                            content = content.ifBlank { null },
                        )
                    }
                }
            },
        )
    }
}

@Composable
private fun CheckInListItem(
    checkIn: CheckInRow,
    isOwn: Boolean,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    checkIn.userName ?: checkIn.userId ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                checkIn.createdAt?.let { ts ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ts)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            checkIn.reason?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            checkIn.content?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isOwn) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CheckInDialog(
    onDismiss: () -> Unit,
    onConfirm: (content: String) -> Unit,
) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.check_in)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.check_in_hint)) },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = 6,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) {
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
