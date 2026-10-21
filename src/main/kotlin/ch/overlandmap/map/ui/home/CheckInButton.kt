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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.CheckCircle
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
import ch.overlandmap.map.model.CheckIn
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.social.SocialTarget
import ch.overlandmap.map.ui.social.TranslatedContent
import ch.overlandmap.map.ui.social.isSignedIn
import ch.overlandmap.map.ui.social.rememberAuthGate
import ch.overlandmap.map.ui.theme.contentTextStyle
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val CheckInGreen = Color(0xFF2E7D32)

/**
 * Check-in button for a step or a waypoint (iOS `CheckInButton`): an outlined circle with no
 * check-ins, a filled one with a count badge otherwise.
 *
 * - No check-ins: tapping opens the check-in dialog (an anonymous user is asked to sign in first).
 * - Check-ins: tapping lists them, with an inline form to add one (one per user) and delete for
 *   the user's own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckInButton(target: SocialTarget) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    val gate = rememberAuthGate()
    val checkIns by app.socialRepository.observeCheckInsForObject(target.objectId)
        .collectAsState(initial = emptyList())

    var showSheet by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val count = checkIns.size

    IconButton(onClick = {
        if (count == 0) gate.require(R.string.sign_in_to_check_in) { showDialog = true } else showSheet = true
    }) {
        if (count == 0) {
            Icon(Icons.Outlined.CheckCircle, contentDescription = stringResource(R.string.check_in), tint = CheckInGreen)
        } else {
            BadgedBox(badge = { Badge { Text("$count") } }) {
                Icon(Icons.Filled.CheckCircle, contentDescription = stringResource(R.string.check_in), tint = CheckInGreen)
            }
        }
    }

    suspend fun submit(message: String) {
        try {
            app.socialRepository.createCheckIn(
                trackPackId = target.trackPackId,
                itinDocumentId = target.itineraryId,
                collection = target.collection,
                objectId = target.objectId,
                content = message.ifBlank { null },
            )
            error = null
        } catch (e: Exception) {
            error = e.localizedMessage
        }
    }

    // ── Bottom sheet: list of check-ins ─────────────────────────────────────
    if (showSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid
        val userHasCheckIn = checkIns.any { it.userId == currentUserId }
        var toDelete by remember { mutableStateOf<CheckIn?>(null) }
        var message by remember { mutableStateOf("") }
        val lang = currentLanguage()

        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = sheetState) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text(
                    "$count " + stringResource(R.string.check_ins_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(12.dp))

                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(checkIns, key = { it.documentId }) { checkIn ->
                        CheckInListItem(
                            checkIn = checkIn,
                            lang = lang,
                            isOwn = checkIn.userId == currentUserId,
                            onDelete = { toDelete = checkIn },
                        )
                        HorizontalDivider()
                    }
                }

                // One check-in per user: the inline form disappears once there is one.
                if (isSignedIn() && !userHasCheckIn) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        placeholder = { Text(stringResource(R.string.check_in_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 6,
                    )
                    Button(
                        onClick = { scope.launch { submit(message); message = "" } },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) { Text(stringResource(R.string.check_in_action)) }
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        toDelete?.let { checkIn ->
            AlertDialog(
                onDismissRequest = { toDelete = null },
                title = { Text(stringResource(R.string.delete_check_in)) },
                confirmButton = {
                    TextButton(onClick = {
                        toDelete = null
                        scope.launch {
                            try {
                                app.socialRepository.deleteCheckIn(checkIn.documentId)
                            } catch (e: Exception) {
                                error = e.localizedMessage
                            }
                        }
                    }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    // ── Check-in dialog ─────────────────────────────────────────────────────
    if (showDialog) {
        CheckInDialog(
            error = error,
            onDismiss = { showDialog = false; error = null },
            onConfirm = { content ->
                scope.launch {
                    submit(content)
                    if (error == null) showDialog = false
                }
            },
        )
    }
}

@Composable
private fun CheckInListItem(checkIn: CheckIn, lang: String, isOwn: Boolean, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    checkIn.userName ?: stringResource(R.string.anonymous),
                    style = contentTextStyle(MaterialTheme.typography.bodyMedium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                checkIn.createdAt?.let { ts ->
                    Spacer(Modifier.width(8.dp))
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ts)),
                        style = contentTextStyle(MaterialTheme.typography.labelSmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TranslatedContent(original = checkIn.content, translation = checkIn.translation(lang))
        }
        if (isOwn) {
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.delete_check_in),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CheckInDialog(error: String?, onDismiss: () -> Unit, onConfirm: (content: String) -> Unit) {
    var text by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.check_in)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.check_in_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 8,
                )
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
