package ch.overlandmap.map.ui.social

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.SyncKind
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.social.SocialRules
import ch.overlandmap.map.ui.theme.contentTextStyle
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * What comments hang on: an itinerary ([itineraryId] set, [objectId] its id) or a whole track
 * pack ([itineraryId] null, [objectId] the pack id), as iOS `CommentScope`.
 */
data class CommentScope(val objectId: String, val trackPackId: String, val itineraryId: String?) {
    val isItinerary: Boolean get() = itineraryId != null
}

private val StarGold = Color(0xFFF9A825)

/** Five stars, filled up to [rating]; with [onChange] they are tappable (tapping the same star again clears it). */
@Composable
fun RatingStars(rating: Int, modifier: Modifier = Modifier, size: Int = 18, onChange: ((Int) -> Unit)? = null) {
    Row(modifier) {
        for (i in 1..5) {
            val star = if (i <= rating) Icons.Filled.Star else Icons.Outlined.StarBorder
            Icon(
                star,
                contentDescription = null,
                tint = StarGold,
                modifier = Modifier
                    .size(size.dp)
                    .let { m -> if (onChange != null) m.clickable { onChange(if (i == rating) 0 else i) } else m },
            )
        }
    }
}

/**
 * The comments tab of an itinerary or track pack (iOS `CommentsTabView`): rated tiles that open
 * a detail, and an "Add comment" action for a user who hasn't commented yet. Reading is from the
 * local cache; entering the tab refreshes it when stale.
 */
@Composable
fun CommentsSection(scope: CommentScope, comments: List<Comment>, lang: String) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val gate = rememberAuthGate()
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    var showCompose by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Comment?>(null) }

    LaunchedEffect(scope.trackPackId) { app.socialRepository.syncIfNeeded(scope.trackPackId, SyncKind.COMMENTS) }

    val hasOwn = uid != null && isSignedIn() && comments.any { it.userId == uid }

    Column(Modifier.fillMaxSize()) {
        if (!hasOwn) {
            OutlinedButton(
                onClick = { gate.require(R.string.sign_in_to_comment) { showCompose = true } },
                modifier = Modifier.padding(12.dp),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text("  " + stringResource(R.string.add_comment))
            }
        }
        if (comments.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.no_comment_yet), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(comments, key = { it.documentId }) { comment ->
                    CommentTile(comment, lang, onClick = { opened = comment })
                }
            }
        }
    }

    if (showCompose) CommentComposeSheet(scope, onDismiss = { showCompose = false })
    opened?.let { CommentDetailDialog(it, lang, onDismiss = { opened = null }) }
}

@Composable
private fun CommentTile(comment: Comment, lang: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.userName ?: stringResource(R.string.anonymous),
                    style = contentTextStyle(MaterialTheme.typography.titleSmall),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                comment.createdAt?.let {
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
                        style = contentTextStyle(MaterialTheme.typography.labelSmall),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            RatingStars(comment.ratingValue, Modifier.padding(vertical = 4.dp))
            Text(
                comment.content(lang),
                style = contentTextStyle(MaterialTheme.typography.bodyMedium),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun CommentDetailDialog(comment: Comment, lang: String, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    val uid = FirebaseAuth.getInstance().currentUser?.uid
    val isAdmin = app.entitlements.collectAsState().value?.hasAdmin == true
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(comment.userName ?: stringResource(R.string.anonymous)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                RatingStars(comment.ratingValue, size = 22)
                comment.createdAt?.let {
                    Text(
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                TranslatedContent(
                    original = comment.content,
                    translation = comment.translation(lang),
                    modifier = Modifier.padding(top = 8.dp),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = {
            if (SocialRules.canDeleteComment(comment.userId, uid, isAdmin)) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.delete_comment), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_comment)) },
            text = { Text(stringResource(R.string.delete_comment_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        try {
                            app.socialRepository.deleteComment(comment.documentId)
                            onDismiss()
                        } catch (e: Exception) {
                            error = e.localizedMessage
                        }
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentComposeSheet(scope: CommentScope, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val coroutineScope = rememberCoroutineScope()
    val lang = ch.overlandmap.map.ui.currentLanguage()
    var text by remember { mutableStateOf("") }
    var rating by remember { mutableStateOf(0) }
    var attempted by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val validation = if (attempted) SocialRules.validateComment(text, rating) else null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(
                stringResource(if (scope.isItinerary) R.string.comment_itinerary else R.string.add_comment),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                stringResource(
                    if (scope.isItinerary) R.string.comment_itinerary_instructions else R.string.comment_track_pack_instructions,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            RatingStars(rating, size = 36, onChange = { rating = it })
            if (validation == SocialRules.CommentError.RATING_REQUIRED) {
                Text(stringResource(R.string.comment_rating_required), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.write_comment)) },
                isError = validation == SocialRules.CommentError.TEXT_REQUIRED,
                supportingText = if (validation == SocialRules.CommentError.TEXT_REQUIRED) {
                    { Text(stringResource(R.string.comment_text_required)) }
                } else null,
                minLines = 4,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(
                    enabled = !saving,
                    onClick = {
                        attempted = true
                        if (SocialRules.validateComment(text, rating) != null) return@Button
                        saving = true
                        coroutineScope.launch {
                            try {
                                // The user may already have commented from another device: look first.
                                app.socialRepository.forceSync(scope.trackPackId, SyncKind.COMMENTS)
                                val uid = FirebaseAuth.getInstance().currentUser?.uid
                                val itineraryId = scope.itineraryId
                                val existing = if (itineraryId != null) {
                                    app.libraryRepository.commentsOf(itineraryId)
                                } else app.libraryRepository.commentsOf(scope.objectId)
                                if (existing.none { it.userId == uid }) {
                                    app.socialRepository.createComment(
                                        objectId = scope.objectId,
                                        trackPackId = scope.trackPackId,
                                        itinDocumentId = itineraryId,
                                        content = text.trim(),
                                        langCode = lang,
                                        rating = rating,
                                    )
                                }
                                onDismiss()
                            } catch (e: Exception) {
                                error = e.localizedMessage
                                saving = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }
}
