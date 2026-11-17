package ch.overlandmap.map.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.model.CheckIn
import ch.overlandmap.map.model.CheckInReason
import ch.overlandmap.map.social.CoordinateParser
import ch.overlandmap.map.social.SocialRules
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.social.DiscussionVoteButtons
import ch.overlandmap.map.ui.social.LocationInput
import ch.overlandmap.map.ui.social.SocialTarget
import ch.overlandmap.map.ui.social.TranslatedContent
import ch.overlandmap.map.ui.social.asInputText
import ch.overlandmap.map.ui.social.rememberAuthGate
import ch.overlandmap.map.ui.theme.contentTextStyle
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import java.text.DateFormat
import java.util.Date

private val CorrectionRed = Color(0xFFE53935)

@StringRes
private fun reasonLabel(reason: CheckInReason): Int = when (reason) {
    CheckInReason.DOES_NOT_EXIST -> R.string.does_not_exist
    CheckInReason.CLOSED -> R.string.closed
    CheckInReason.REPAIRED -> R.string.repaired
    CheckInReason.WRONG_DESCRIPTION -> R.string.wrong_description
    CheckInReason.WRONG_LOCATION -> R.string.wrong_location
    CheckInReason.CHECK -> R.string.check_in
    CheckInReason.OTHER -> R.string.other
}

/**
 * Correction button for a step or waypoint (iOS `CorrectionButton`): an outlined red icon when
 * nothing was reported, a filled one once a correction exists. A correction is a check-in with a
 * problem reason; the newest one is shown. Opening the form needs a signed-in account, viewing an
 * existing correction does not.
 */
@Composable
fun CorrectionButton(target: SocialTarget) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val gate = rememberAuthGate()
    val corrections by (target.geohash?.let { hash ->
        val geographic = app.socialRepository.observeCorrectionsForGeohash(hash)
        if (target.objectId.isEmpty()) geographic else combine(
            geographic, app.socialRepository.observeCorrectionsForObject(target.objectId),
        ) { current, legacy -> (current + legacy).distinctBy { it.documentId }.sortedByDescending { it.createdAt ?: 0L } }
    } ?: app.socialRepository.observeCorrectionsForObject(target.objectId)).collectAsState(initial = emptyList())
    val correction = corrections.firstOrNull()
    var showSheet by remember { mutableStateOf(false) }

    IconButton(enabled = target.geohash != null, onClick = {
        if (correction == null) gate.require(R.string.sign_in_to_discuss) { showSheet = true } else showSheet = true
    }) {
        if (corrections.isEmpty()) {
            Icon(Icons.Outlined.ErrorOutline, stringResource(R.string.corrections), tint = CorrectionRed)
        } else {
            BadgedBox(badge = { Badge(containerColor = Color.White, contentColor = CorrectionRed) { Text("${corrections.size}") } }) {
                Box(Modifier.size(24.dp).background(Color.White, CircleShape)) {
                    Icon(Icons.Filled.Error, stringResource(R.string.corrections), tint = CorrectionRed, modifier = Modifier.size(24.dp))
                }
            }
        }
    }

    if (showSheet) CorrectionSheet(target, correction, onDismiss = { showSheet = false })
}

/**
 * The correction form and viewer (iOS `CorrectionSheet`).
 *  - No correction yet: the reason picker, the explanation, and for "wrong location" the right position.
 *  - A correction exists: its reason, text and votes; its author can edit or delete it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CorrectionSheet(target: SocialTarget, existing: CheckIn?, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    val gate = rememberAuthGate()
    val lang = currentLanguage()
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val isOwn = existing != null && existing.userId == uid
    var editing by remember { mutableStateOf(false) }
    val showForm = existing == null || editing

    var reason by remember { mutableStateOf(existing?.reason?.takeIf { it in SocialRules.correctionReasons }) }
    var content by remember { mutableStateOf(existing?.content.orEmpty()) }
    var locationText by remember {
        mutableStateOf(existing?.let { c -> c.lat?.let { lat -> c.lon?.let { lon -> Coordinate(lat, lon).asInputText() } } }.orEmpty())
    }
    var attempted by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<GeoBounds?>(null) }
    LaunchedEffect(target.trackPackId) {
        bounds = app.libraryRepository.trackPack(target.trackPackId)?.let { p ->
            val (a, b, c, d) = listOf(p.latMin, p.latMax, p.lonMin, p.lonMax)
            if (a != null && b != null && c != null && d != null) GeoBounds(a, b, c, d) else null
        }
    }

    val error = if (attempted) {
        SocialRules.validateCorrection(reason, content, locationText, null, bounds)
    } else null
    val locationError = when (error) {
        SocialRules.CorrectionError.LOCATION_REQUIRED -> stringResource(R.string.location_required)
        SocialRules.CorrectionError.LOCATION_INVALID -> stringResource(R.string.please_provide_location)
        SocialRules.CorrectionError.LOCATION_OUTSIDE -> stringResource(R.string.location_outside_track_pack)
        else -> null
    }

    fun submit() {
        attempted = true
        if (SocialRules.validateCorrection(reason, content, locationText, null, bounds) != null) return
        val chosen = reason ?: return
        val position = if (chosen == CheckInReason.WRONG_LOCATION) CoordinateParser.parse(locationText) else null
        submitting = true
        scope.launch {
            try {
                if (existing == null) {
                    app.socialRepository.createCorrection(
                        trackPackId = target.trackPackId,
                        itinDocumentId = target.itineraryId,
                        collection = target.collection,
                        geohash = target.geohash,
                        reason = chosen,
                        content = content.trim(),
                        location = position,
                    )
                } else {
                    app.socialRepository.updateCorrection(existing.documentId, chosen, content.trim(), position)
                }
                onDismiss()
            } catch (e: Exception) {
                problem = e.localizedMessage
                submitting = false
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(if (existing == null) R.string.share_correction else R.string.shared_correction),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(12.dp))

            if (showForm) {
                SocialRules.correctionReasons.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { reason = option },
                    ) {
                        RadioButton(selected = reason == option, onClick = { reason = option })
                        Text(stringResource(reasonLabel(option)))
                    }
                }
                if (error == SocialRules.CorrectionError.REASON_REQUIRED) {
                    Text(
                        stringResource(R.string.please_fill_all_fields),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.explain_correction)) },
                    isError = error == SocialRules.CorrectionError.CONTENT_REQUIRED,
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                if (reason == CheckInReason.WRONG_LOCATION) {
                    LocationInput(
                        value = locationText,
                        onValueChange = { locationText = it; problem = null },
                        error = locationError ?: problem,
                        onProblem = { problem = it },
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    problem?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (existing != null) {
                        OutlinedButton(onClick = { confirmDelete = true }) {
                            Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { if (existing != null) editing = false else onDismiss() }) {
                        Text(stringResource(R.string.cancel))
                    }
                    Button(onClick = ::submit, enabled = !submitting) {
                        Text(stringResource(if (existing == null) R.string.share else R.string.save))
                    }
                }
            } else if (existing != null) {
                Text(
                    stringResource(R.string.reason_colon) + stringResource(reasonLabel(existing.reason)),
                    style = MaterialTheme.typography.titleSmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text(
                        existing.userName ?: stringResource(R.string.anonymous),
                        style = contentTextStyle(MaterialTheme.typography.bodyMedium),
                    )
                    existing.createdAt?.let {
                        Text(
                            "  " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
                            style = contentTextStyle(MaterialTheme.typography.labelSmall),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TranslatedContent(
                    original = existing.content,
                    translation = existing.translation(lang),
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (existing.reason == CheckInReason.WRONG_LOCATION && existing.lat != null && existing.lon != null) {
                    Text(
                        Coordinate(existing.lat!!, existing.lon!!).asInputText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                val votes by app.socialRepository.observeVotesForObject(existing.documentId)
                    .collectAsState(initial = emptyList())
                val mine = votes.firstOrNull { it.userId == uid }?.isUpVote
                DiscussionVoteButtons(
                    upCount = votes.count { it.isUpVote },
                    downCount = votes.count { !it.isUpVote },
                    userVote = mine,
                    modifier = Modifier.padding(top = 12.dp),
                    onVote = { up ->
                        gate.require(R.string.sign_in_to_discuss) {
                            scope.launch {
                                try {
                                    app.socialRepository.castVote(existing.documentId, target.trackPackId, up)
                                } catch (e: Exception) {
                                    problem = e.localizedMessage
                                }
                            }
                        }
                    },
                )
                problem?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Spacer(Modifier.weight(1f))
                    if (isOwn) {
                        OutlinedButton(onClick = { editing = true }) {
                            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.edit_comment))
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            app.socialRepository.deleteCheckIn(existing.documentId)
                            onDismiss()
                        } catch (e: Exception) {
                            problem = e.localizedMessage
                        }
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
