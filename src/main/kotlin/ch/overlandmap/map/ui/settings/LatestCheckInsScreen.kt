package ch.overlandmap.map.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.model.FS
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.DateFormat
import java.util.Date

/**
 * A single check-in row prepared for display.
 */
private data class CheckInEntry(
    val documentId: String,
    val objectId: String?,
    val collection: String?,
    val trackPackId: String?,
    val trackPackName: String?,
    val itinDocumentId: String?,
    val reason: String?,
    val content: String?,
    val userName: String?,
    val createdAt: Long?,
    val upVotes: Int?,
    val downVotes: Int?,
    /** Whether the referenced object exists in the local library. */
    val existsLocally: Boolean,
)

/** Detail info loaded when the user taps a check-in row. */
private data class CheckInDetail(
    val checkIn: CheckInEntry,
    val itineraryName: String?,
    val itineraryId: String?,
    val votes: List<VoteEntry>,
    /** If the object is a local step, its itineraryId + stepId for navigation. */
    val localItineraryDocId: String?,
    val localStepId: Int?,
)

private data class VoteEntry(
    val userId: String?,
    val userName: String?,
    val upVote: Boolean,
    val content: String?,
    val createdAt: Long?,
)

/**
 * Debug screen listing the latest 500 check-ins fetched directly from
 * Firestore in reverse chronological order. Tapping any row opens a detail
 * dialog with full metadata, votes, and an optional "Show" button when the
 * referenced object exists locally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LatestCheckInsScreen(
    onBack: () -> Unit,
    onOpenItinerary: (documentId: String, stepId: Int?) -> Unit,
) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    var checkIns by remember { mutableStateOf<List<CheckInEntry>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<CheckInDetail?>(null) }
    var detailLoading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val snapshot = FirebaseFirestore.getInstance()
                .collection("check_in")
                .orderBy("createdAt", Query.Direction.DESCENDING)
                .limit(500)
                .get()
                .await()

            val libraryDao = app.database.libraryDao()
            val packNames = app.trackPackNames

            checkIns = snapshot.documents.map { doc ->
                val data = doc.data ?: emptyMap()
                val trackPackId = data["trackPackId"] as? String
                val objectId = data["objectId"] as? String

                val packName = trackPackId?.let { packNames[it] ?: it }

                val existsLocally = if (objectId != null) {
                    libraryDao.stepByDocumentId(objectId) != null ||
                        libraryDao.waypointByDocumentId(objectId) != null
                } else {
                    false
                }

                CheckInEntry(
                    documentId = doc.id,
                    objectId = objectId,
                    collection = data["collection"] as? String,
                    trackPackId = trackPackId,
                    trackPackName = packName,
                    itinDocumentId = data["itinDocumentId"] as? String,
                    reason = data["reason"] as? String,
                    content = data["content"] as? String,
                    userName = data["userName"] as? String,
                    createdAt = FS.millis(data["createdAt"]),
                    upVotes = (data["upVotes"] as? Long)?.toInt(),
                    downVotes = (data["downVotes"] as? Long)?.toInt(),
                    existsLocally = existsLocally,
                )
            }.sortedByDescending { it.createdAt ?: 0L }
        } catch (e: Exception) {
            error = e.message ?: "Failed to fetch check-ins"
            checkIns = emptyList()
        }
    }

    // Detail dialog
    detail?.let { d ->
        CheckInDetailDialog(
            detail = d,
            onDismiss = { detail = null },
            onShow = if (d.localItineraryDocId != null) {
                {
                    detail = null
                    onOpenItinerary(d.localItineraryDocId, d.localStepId)
                }
            } else null,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Latest checkIns") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        val items = checkIns
        if (items == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    error ?: "No check-ins",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (error != null) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(items, key = { it.documentId }) { checkIn ->
                    CheckInItem(
                        checkIn = checkIn,
                        onClick = {
                            if (!detailLoading) {
                                detailLoading = true
                                scope.launch {
                                    detail = loadCheckInDetail(checkIn, app)
                                    detailLoading = false
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }

        // Overlay loading indicator when fetching detail
        if (detailLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}

/** Fetches itinerary name and votes from Firestore, checks local availability. */
private suspend fun loadCheckInDetail(checkIn: CheckInEntry, app: OverlandApp): CheckInDetail {
    val db = FirebaseFirestore.getInstance()
    val libraryDao = app.database.libraryDao()

    // Fetch itinerary name from Firestore using itinDocumentId.
    var itineraryName: String? = null
    var itinerarySlug: String? = null
    val itinDocId = checkIn.itinDocumentId
    if (itinDocId != null) {
        try {
            val itinDoc = db.collection("itinerary").document(itinDocId).get().await()
            val itinData = itinDoc.data
            if (itinData != null) {
                itineraryName = itinData["name"] as? String
                itinerarySlug = itinData["itineraryId"] as? String
            }
        } catch (_: Exception) {
            // Firestore unavailable — leave null.
        }
    }

    // Fetch votes for this check-in from Firestore.
    val votes = try {
        db.collection("vote")
            .whereEqualTo("objectId", checkIn.documentId)
            .get()
            .await()
            .documents.map { doc ->
                val data = doc.data ?: emptyMap()
                VoteEntry(
                    userId = data["userId"] as? String,
                    userName = data["userName"] as? String,
                    upVote = data["upVote"] == true,
                    content = data["content"] as? String,
                    createdAt = FS.millis(data["createdAt"]),
                )
            }
    } catch (_: Exception) {
        emptyList()
    }

    // Check if the objectId exists locally and resolve navigation info.
    var localItineraryDocId: String? = null
    var localStepId: Int? = null
    val objectId = checkIn.objectId
    if (objectId != null) {
        val step = libraryDao.stepByDocumentId(objectId)
        if (step != null) {
            localItineraryDocId = step.itineraryId
            localStepId = step.stepId
        } else {
            val waypoint = libraryDao.waypointByDocumentId(objectId)
            if (waypoint != null) {
                // Waypoints belong to an itinerary — open it without a step.
                localItineraryDocId = waypoint.itineraryId
            }
        }
    }

    return CheckInDetail(
        checkIn = checkIn,
        itineraryName = itineraryName,
        itineraryId = itinerarySlug,
        votes = votes,
        localItineraryDocId = localItineraryDocId,
        localStepId = localStepId,
    )
}

@Composable
private fun CheckInDetailDialog(
    detail: CheckInDetail,
    onDismiss: () -> Unit,
    onShow: (() -> Unit)?,
) {
    val checkIn = detail.checkIn
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check-in detail") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DetailRow("Track pack", checkIn.trackPackName ?: "—")
                DetailRow("Itinerary", detail.itineraryName ?: "—")
                DetailRow("Itinerary ID", detail.itineraryId ?: "—")
                DetailRow("Collection", checkIn.collection ?: "—")
                DetailRow("Document ID", checkIn.objectId ?: "—")
                DetailRow("Reason", checkIn.reason ?: "—")
                checkIn.content?.let { DetailRow("Content", it) }
                DetailRow("User", checkIn.userName ?: "—")
                checkIn.createdAt?.let {
                    DetailRow(
                        "Date",
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(it)),
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Votes (${detail.votes.size})",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (detail.votes.isEmpty()) {
                    Text(
                        "No votes",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    detail.votes.forEach { vote ->
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row {
                            Text(
                                text = if (vote.upVote) "+1" else "-1",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (vote.upVote) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.error,
                            )
                            Text(
                                text = " ${vote.userName ?: vote.userId ?: "anonymous"}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            vote.createdAt?.let { ts ->
                                Text(
                                    text = DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ts)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        vote.content?.let { c ->
                            Text(
                                text = c,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 24.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (onShow != null) {
                Button(onClick = onShow) { Text("Show") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CheckInItem(checkIn: CheckInEntry, onClick: () -> Unit) {
    val grayBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val modifier = Modifier
        .fillMaxWidth()
        .then(if (!checkIn.existsLocally) Modifier.background(grayBg) else Modifier)
        .clickable(onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 12.dp)

    Column(modifier = modifier) {
        // Track pack name + date
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = checkIn.trackPackName ?: "—",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            checkIn.createdAt?.let { ts ->
                Text(
                    text = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(ts)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Reason
        checkIn.reason?.let { reason ->
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
        }

        // Content (user comment)
        checkIn.content?.let { content ->
            Text(
                text = content,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        // Votes + user name
        val meta = buildString {
            checkIn.upVotes?.let { append("+$it") }
            checkIn.downVotes?.let {
                if (isNotEmpty()) append(" / ")
                append("-$it")
            }
            checkIn.userName?.let { name ->
                if (isNotEmpty()) append(" · ")
                append(name)
            }
        }
        if (meta.isNotEmpty()) {
            Text(
                text = meta,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
