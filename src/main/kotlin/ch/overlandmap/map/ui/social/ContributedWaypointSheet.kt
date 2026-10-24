package ch.overlandmap.map.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.model.ContributedWaypoint
import ch.overlandmap.map.social.CoordinateParser
import ch.overlandmap.map.social.SocialRules
import ch.overlandmap.map.ui.currentLanguage
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch

/**
 * The community's shared waypoints of an itinerary (iOS `ContributedWaypointSheet`): the list, an
 * editor for the user's own, a viewer with votes for the others', and "Add new waypoint".
 *
 * [known] are the points a new waypoint must not duplicate (the itinerary's steps and waypoints);
 * the other shared waypoints are added here. [onZoom] flies the map to a position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributedWaypointSheet(
    trackPackId: String,
    itineraryId: String,
    known: List<SocialRules.KnownPoint>,
    onZoom: (Coordinate) -> Unit,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val waypoints by app.socialRepository.observeContributedWaypoints(itineraryId).collectAsState(initial = emptyList())
    var bounds by remember { mutableStateOf<GeoBounds?>(null) }
    var editing by remember { mutableStateOf<ContributedWaypoint?>(null) }
    var creating by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<ContributedWaypoint?>(null) }
    LaunchedEffect(trackPackId) {
        bounds = app.libraryRepository.trackPack(trackPackId)?.let { p ->
            val (a, b, c, d) = listOf(p.latMin, p.latMax, p.lonMin, p.lonMax)
            if (a != null && b != null && c != null && d != null) GeoBounds(a, b, c, d) else null
        }
    }
    val uid = FirebaseAuth.getInstance().currentUser?.uid

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.contributed_waypoints), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.share_waypoint_with_community),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            if (waypoints.isEmpty()) {
                Text(stringResource(R.string.no_contributed_waypoints), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                waypoints.forEach { w ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { if (w.userId == uid) editing = w else viewing = w }
                            .padding(vertical = 10.dp),
                    ) {
                        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(w.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                stringResource(R.string.shared_by) + " " + (w.userName ?: stringResource(R.string.anonymous)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (w.userId == uid) Icon(Icons.Filled.Edit, contentDescription = null)
                    }
                    HorizontalDivider()
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.add_contributed_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  " + stringResource(R.string.add_contributed_waypoint))
            }
        }
    }

    val others = waypoints.mapNotNull { w ->
        if (w.lat != null && w.lon != null) SocialRules.KnownPoint(w.name, Coordinate(w.lat!!, w.lon!!)) else null
    }
    if (creating) {
        WaypointEditor(
            trackPackId, itineraryId, existing = null, bounds, known + others,
            onDone = { creating = false },
        )
    }
    editing?.let { w ->
        WaypointEditor(
            trackPackId, itineraryId, existing = w, bounds,
            known + others.filterNot { it.name == w.name && it.coordinate.latitude == w.lat },
            onDone = { editing = null },
        )
    }
    viewing?.let { w ->
        ContributedWaypointViewer(
            w, trackPackId,
            onZoom = { w.lat?.let { lat -> w.lon?.let { lon -> onZoom(Coordinate(lat, lon)); onDismiss() } } },
            onDismiss = { viewing = null },
        )
    }
}

/** Create (existing == null) or edit a shared waypoint; the author can also delete it. */
@Composable
private fun WaypointEditor(
    trackPackId: String,
    itineraryId: String,
    existing: ContributedWaypoint?,
    bounds: GeoBounds?,
    known: List<SocialRules.KnownPoint>,
    onDone: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var content by remember { mutableStateOf(existing?.content.orEmpty()) }
    var locationText by remember {
        mutableStateOf(existing?.let { w -> w.lat?.let { la -> w.lon?.let { lo -> Coordinate(la, lo).asInputText() } } }.orEmpty())
    }
    var attempted by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val check = if (attempted) SocialRules.validateWaypoint(name, content, locationText, null, bounds, known) else null
    val locationError = when (check?.error) {
        SocialRules.WaypointError.LOCATION_INVALID -> stringResource(R.string.please_provide_location)
        SocialRules.WaypointError.LOCATION_OUTSIDE -> stringResource(R.string.location_outside_track_pack)
        SocialRules.WaypointError.DUPLICATE -> stringResource(R.string.location_close_to_duplicate, check?.duplicateOf.orEmpty())
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(if (existing == null) R.string.new_waypoint else R.string.shared_waypoint)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.name)) },
                    singleLine = true,
                    isError = check?.error == SocialRules.WaypointError.FIELDS_REQUIRED && name.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.description)) },
                    isError = check?.error == SocialRules.WaypointError.FIELDS_REQUIRED && content.isBlank(),
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                LocationInput(
                    value = locationText,
                    onValueChange = { locationText = it; problem = null },
                    error = locationError ?: problem,
                    onProblem = { problem = it },
                )
                if (check?.error == SocialRules.WaypointError.FIELDS_REQUIRED) {
                    Text(
                        stringResource(R.string.please_fill_all_fields),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving,
                onClick = {
                    attempted = true
                    val result = SocialRules.validateWaypoint(name, content, locationText, null, bounds, known)
                    val coordinate = CoordinateParser.parse(locationText)
                    if (result.error != null || coordinate == null) return@Button
                    saving = true
                    scope.launch {
                        try {
                            if (existing == null) {
                                app.socialRepository.createContributedWaypoint(trackPackId, itineraryId, name.trim(), content.trim(), coordinate)
                            } else {
                                app.socialRepository.updateContributedWaypoint(existing.documentId, name.trim(), content.trim(), coordinate)
                            }
                            onDone()
                        } catch (e: Exception) {
                            problem = e.localizedMessage
                            saving = false
                        }
                    }
                },
            ) { Text(stringResource(if (existing == null) R.string.share else R.string.save)) }
        },
        dismissButton = {
            Row {
                if (existing != null) {
                    TextButton(onClick = { confirmDelete = true }) {
                        Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
            }
        },
    )

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(existing.name) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            app.socialRepository.deleteContributedWaypoint(existing.documentId)
                            onDone()
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

/** Someone else's shared waypoint: the text, who shared it, votes, and a way to zoom to it. */
@Composable
internal fun ContributedWaypointViewer(w: ContributedWaypoint, trackPackId: String, onZoom: (() -> Unit)?, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    val gate = rememberAuthGate()
    val lang = currentLanguage()
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val votes by app.socialRepository.observeVotesForObject(w.documentId).collectAsState(initial = emptyList())
    var problem by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(w.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.shared_by) + " " + (w.userName ?: stringResource(R.string.anonymous)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TranslatedContent(
                    original = w.content,
                    translation = (w.extra["translations"] as? Map<*, *>)?.get(lang) as? String,
                    modifier = Modifier.padding(top = 8.dp),
                )
                DiscussionVoteButtons(
                    upCount = votes.count { it.isUpVote },
                    downCount = votes.count { !it.isUpVote },
                    userVote = votes.firstOrNull { it.userId == uid }?.isUpVote,
                    modifier = Modifier.padding(top = 12.dp),
                    onVote = { up ->
                        gate.require(R.string.sign_in_to_discuss) {
                            scope.launch {
                                try {
                                    app.socialRepository.castVote(w.documentId, trackPackId, up)
                                } catch (e: Exception) {
                                    problem = e.localizedMessage
                                }
                            }
                        }
                    },
                )
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = {
            if (onZoom != null) OutlinedButton(onClick = onZoom) {
                Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(R.string.show_on_map))
            }
        },
    )
}
