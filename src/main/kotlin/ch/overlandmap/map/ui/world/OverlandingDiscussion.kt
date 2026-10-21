package ch.overlandmap.map.ui.world

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.DiscussionRepository
import ch.overlandmap.map.data.SocialRepository
import ch.overlandmap.map.model.Discussion
import ch.overlandmap.map.model.DiscussionReason
import ch.overlandmap.map.social.DiscussionRules
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.social.DiscussionVoteButtons
import ch.overlandmap.map.ui.social.TranslatedContent
import ch.overlandmap.map.ui.theme.contentTextStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The community discussion of one Overlanding object (country, border, border post, zone),
 * shared by the object's field buttons, its Discussion tab and the add-input sheet. The cached
 * discussions are observed live; the object's remote ones are fetched when it opens (always, as
 * on iOS where the Overlanding views force a remote read).
 */
@Stable
class ObjectDiscussions internal constructor(
    val objectId: String,
    val collection: String,
    private val repository: DiscussionRepository,
    private val social: SocialRepository,
    private val scope: CoroutineScope,
) {
    var discussions by mutableStateOf<List<Discussion>>(emptyList())
        internal set

    var loading by mutableStateOf(false)
        private set

    /** The signed-in session's uid (anonymous included), for "mine" checks. */
    val uid: String? get() = repository.currentUserId

    fun reload() {
        scope.launch {
            loading = true
            try {
                repository.refresh(objectId, force = true)
                repository.refreshVotes(objectId, repository.cachedIds(objectId), force = true)
            } finally {
                loading = false
            }
        }
    }

    fun isOwner(d: Discussion) = DiscussionRules.isOwner(d, uid)

    /** Writes a discussion (correction, input, reply…); the live cache shows it right away. */
    suspend fun create(
        topic: String,
        reason: String,
        content: String,
        lang: String?,
        threadId: String? = null,
        location: ch.overlandmap.map.geo.Coordinate? = null,
        isOpen: Int? = null,
    ) = repository.create(objectId, collection, topic, reason, content, lang, threadId, location, isOpen)

    /** Replies to [parent], on the same object and topic. */
    suspend fun reply(parent: Discussion, text: String, lang: String?) {
        repository.create(
            objectId = parent.objectId ?: objectId,
            collection = parent.collection ?: collection,
            topic = parent.topicString(),
            reason = "followup",
            content = text,
            lang = lang,
            threadId = parent.documentId,
        )
    }

    fun delete(d: Discussion) {
        scope.launch { runCatching { repository.delete(d.documentId) } }
    }

    fun vote(d: Discussion, up: Boolean, allowAnonymous: Boolean = true) {
        scope.launch { runCatching { social.castVote(d.documentId, null, up, allowAnonymous) } }
    }

    fun votes(discussionId: String) = social.observeVotesForObject(discussionId)
}

internal fun Discussion.topicString(): String =
    (extra["topic"] as? String) ?: topic.raw

@Composable
fun rememberObjectDiscussions(objectId: String, collection: String): ObjectDiscussions {
    val app = LocalContext.current.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    val state = remember(objectId, collection) {
        ObjectDiscussions(objectId, collection, app.discussionRepository, app.socialRepository, scope)
    }
    LaunchedEffect(state) {
        launch { app.discussionRepository.observe(objectId).collect { state.discussions = it } }
        state.reload()
    }
    return state
}

// ── Field pencil and its sheet ──────────────────────────────────────────────

/** The pencil before a field: blue when nobody commented on it, red when someone did. */
@Composable
fun FieldDiscussionButton(
    discussions: ObjectDiscussions,
    fieldName: String,
    fieldTitle: String,
    modifier: Modifier = Modifier,
) {
    var showSheet by remember { mutableStateOf(false) }
    val has = DiscussionRules.hasDiscussion(discussions.discussions, fieldName)
    IconButton(onClick = { showSheet = true }, modifier = modifier.size(28.dp)) {
        Icon(
            Icons.Filled.Edit,
            contentDescription = stringResource(R.string.correction_title),
            tint = if (has) Color(0xFFD32F2F) else Color(0xFF1976D2),
            modifier = Modifier.size(18.dp),
        )
    }
    if (showSheet) {
        FieldDiscussionSheet(discussions, fieldName, fieldTitle) { showSheet = false }
    }
}

/** Views the discussions about a field and files a new correction (reason `correction`, topic = field). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FieldDiscussionSheet(
    discussions: ObjectDiscussions,
    fieldName: String,
    fieldTitle: String,
    onDismiss: () -> Unit,
) {
    val existing = DiscussionRules.forField(discussions.discussions, fieldName)
    val lang = currentLanguage()
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                fieldTitle.ifEmpty { stringResource(R.string.correction_title) },
                style = MaterialTheme.typography.titleLarge,
            )
            if (existing.isNotEmpty()) {
                Text(stringResource(R.string.existing_discussions), style = MaterialTheme.typography.titleSmall)
                existing.forEach { item ->
                    Column(Modifier.fillMaxWidth()) {
                        Row {
                            Text(
                                item.userName ?: stringResource(R.string.anonymous),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            item.createdAt?.let {
                                Text(formatDateTime(it), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        item.content?.takeIf { it.isNotEmpty() }?.let { Text(it) }
                    }
                    HorizontalDivider()
                }
            }
            Text(stringResource(R.string.new_correction), style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(
                    enabled = text.isNotBlank() && !submitting,
                    onClick = {
                        submitting = true
                        error = null
                        scope.launch {
                            try {
                                discussions.create(fieldName, "correction", text.trim(), lang)
                                onDismiss()
                            } catch (e: Exception) {
                                error = e.localizedMessage
                                submitting = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

// ── Add your input ──────────────────────────────────────────────────────────

@Composable
fun AddInputButton(discussions: ObjectDiscussions, modifier: Modifier = Modifier) {
    var show by remember { mutableStateOf(false) }
    TextButton(onClick = { show = true }, modifier = modifier) {
        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("  " + stringResource(R.string.add_your_input), fontWeight = FontWeight.SemiBold)
    }
    if (show) AddInputSheet(discussions) { show = false }
}

/** The placeholder of a tab without content: an invitation to contribute. */
@Composable
fun EmptyTabContent(discussions: ObjectDiscussions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.help_by_entering_comment),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AddInputButton(discussions)
    }
}

/** Free input about the object with a chosen reason (topic = reason). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddInputSheet(discussions: ObjectDiscussions, onDismiss: () -> Unit) {
    val lang = currentLanguage()
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf(DiscussionReason.GENERAL) }
    var expanded by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.add_your_input), style = MaterialTheme.typography.titleLarge)
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = reason.label(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.reason_colon)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DiscussionReason.inputReasons.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.label()) },
                            onClick = { reason = option; expanded = false },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.add_your_input)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(
                    enabled = text.isNotBlank() && !submitting,
                    onClick = {
                        submitting = true
                        error = null
                        scope.launch {
                            try {
                                discussions.create(reason.raw, reason.raw, text.trim(), lang)
                                onDismiss()
                            } catch (e: Exception) {
                                error = e.localizedMessage
                                submitting = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

@Composable
fun DiscussionReason.label(): String = stringResource(
    when (this) {
        DiscussionReason.GENERAL -> R.string.general
        DiscussionReason.VISA -> R.string.visa
        DiscussionReason.EMBASSY -> R.string.embassy
        DiscussionReason.SAFETY -> R.string.safety
        DiscussionReason.BORDER_CROSSING -> R.string.border_crossing
        DiscussionReason.HEALTH -> R.string.health
        DiscussionReason.VEHICLE_INSURANCE -> R.string.vehicle_insurance
        DiscussionReason.VEHICLE_TIP -> R.string.vehicle_tip
        DiscussionReason.CARNET -> R.string.carnet
        DiscussionReason.VEHICLE_STORAGE -> R.string.vehicle_storage
        DiscussionReason.FUEL -> R.string.fuel
        DiscussionReason.TELCOS -> R.string.telcos
        DiscussionReason.PETS -> R.string.pets
        DiscussionReason.FOLLOWUP -> R.string.reply
        DiscussionReason.SUGGESTION -> R.string.suggestion
        DiscussionReason.OTHER -> R.string.other
    }
)

/** The badge text of a field discussion: the field's title or the reason's label. */
@Composable
private fun topicLabel(raw: String): String {
    val key = raw.lowercase()
    val res = when (key) {
        "status" -> R.string.topic_status
        "comment" -> R.string.topic_comment
        "description" -> R.string.description
        "insurancescheme" -> R.string.insurance_scheme
        "stayduration" -> R.string.stay_duration
        "zonetype" -> R.string.topic_zone_type
        else -> null
    }
    if (res != null) return stringResource(res)
    val reason = DiscussionReason.entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) }
    return reason?.label() ?: raw.replaceFirstChar { it.uppercase() }
}

internal fun formatDateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

// ── Discussion tab and cards ────────────────────────────────────────────────

@Composable
fun DiscussionTab(discussions: ObjectDiscussions, modifier: Modifier = Modifier) {
    val roots = DiscussionRules.roots(discussions.discussions)
    if (roots.isEmpty()) {
        EmptyTabContent(discussions, modifier)
        return
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        roots.forEach { root ->
            FieldDiscussionCard(root, DiscussionRules.followups(root, discussions.discussions), discussions)
        }
        AddInputButton(discussions)
    }
}

/** One discussion: topic, author, text, votes, a reply box and the replies (iOS `FieldDiscussionCard`). */
@Composable
fun FieldDiscussionCard(
    discussion: Discussion,
    followups: List<Discussion>,
    discussions: ObjectDiscussions,
    modifier: Modifier = Modifier,
    badge: String? = null,
    badgeColor: Color = Color(0xFFD32F2F),
    headline: String? = null,
    showContent: Boolean = true,
    extraHeader: @Composable () -> Unit = {},
) {
    val lang = currentLanguage()
    val scope = rememberCoroutineScope()
    var showReply by remember { mutableStateOf(false) }
    var replyText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var cannotDelete by remember { mutableStateOf(false) }
    val votes by remember(discussion.documentId) { discussions.votes(discussion.documentId) }.collectAsState(initial = emptyList())
    val mine = votes.firstOrNull { it.userId == discussions.uid }
    val up = maxOf(votes.count { it.isUpVote }, discussion.upVotes)
    val down = maxOf(votes.count { !it.isUpVote }, discussion.downVotes)

    OutlinedCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    badge ?: topicLabel(discussion.topicString()),
                    color = badgeColor,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(badgeColor.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
                Text(
                    discussion.userName ?: stringResource(R.string.anonymous),
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                discussion.createdAt?.let { Text(formatDateTime(it), style = MaterialTheme.typography.labelSmall) }
                if (discussions.isOwner(discussion)) {
                    IconButton(
                        onClick = { if (followups.isNotEmpty()) cannotDelete = true else confirmDelete = true },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    }
                }
            }
            extraHeader()
            headline?.let { Text(it, fontWeight = FontWeight.Bold) }
            discussion.content?.takeIf { showContent && it.isNotEmpty() }?.let {
                TranslatedContent(original = it, translation = discussion.translation(lang))
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                DiscussionVoteButtons(
                    upCount = up, downCount = down, userVote = mine?.isUpVote,
                    onVote = { discussions.vote(discussion, it) },
                )
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { showReply = !showReply }) {
                    Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" " + stringResource(R.string.reply))
                }
            }
            if (showReply) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = replyText,
                        onValueChange = { replyText = it },
                        placeholder = { Text(stringResource(R.string.reply_hint)) },
                        modifier = Modifier.weight(1f),
                    )
                    Button(
                        enabled = replyText.isNotBlank() && !sending,
                        onClick = {
                            sending = true
                            scope.launch {
                                try {
                                    discussions.reply(discussion, replyText.trim(), lang)
                                    replyText = ""
                                    showReply = false
                                } catch (_: Exception) {
                                } finally {
                                    sending = false
                                }
                            }
                        },
                    ) { Text(stringResource(R.string.send)) }
                }
            }
            if (followups.isNotEmpty()) {
                HorizontalDivider(Modifier.padding(vertical = 2.dp))
                Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    followups.forEach { Followup(it, discussions) }
                }
            }
        }
    }

    if (cannotDelete) {
        AlertDialog(
            onDismissRequest = { cannotDelete = false },
            title = { Text(stringResource(R.string.cannot_delete_discussion_with_followups)) },
            confirmButton = { TextButton(onClick = { cannotDelete = false }) { Text(stringResource(R.string.ok)) } },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_discussion_confirmation)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; discussions.delete(discussion) }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun Followup(followup: Discussion, discussions: ObjectDiscussions) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                followup.userName ?: stringResource(R.string.anonymous),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            followup.createdAt?.let { Text(formatDateTime(it), style = MaterialTheme.typography.labelSmall) }
            if (discussions.isOwner(followup)) {
                IconButton(onClick = { discussions.delete(followup) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                }
            }
        }
        followup.content?.takeIf { it.isNotEmpty() }?.let {
            Text(it, style = contentTextStyle(MaterialTheme.typography.bodyMedium))
        }
    }
}
