package ch.overlandmap.map.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.data.UserPreferences
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.ui.markup.MarkupLink
import ch.overlandmap.map.ui.markup.MarkupText
import coil.compose.AsyncImage

/**
 * Popups for objects that markup links (and grid tiles) can open. Their
 * descriptions are markup themselves, so each takes the enclosing
 * MarkupLinkHost's handler as [onLink].
 */

/** Full-screen reader for one sidebar article. */
@Composable
fun SidebarDialog(
    sidebar: Sidebar,
    lang: String,
    onLink: ((MarkupLink, String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                @OptIn(ExperimentalMaterial3Api::class)
                TopAppBar(
                    title = { Text(sidebar.name(lang), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState()),
            ) {
                sidebar.titlePhotoUrl?.let {
                    AsyncImage(
                        model = it,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
                        contentScale = ContentScale.Crop,
                    )
                }
                sidebar.description(lang)?.let {
                    MarkupText(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        onLinkClick = onLink,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                // Breathing room so the last line clears the bottom edge.
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/** Description-only popup for an itinerary of the full pack not in the sample. */
@Composable
fun BuyableItineraryDialog(
    itinerary: Itinerary,
    lang: String,
    onLink: ((MarkupLink, String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(itinerary.name(lang)) },
        text = {
            MarkupText(
                itinerary.description(lang) ?: "",
                onLinkClick = onLink,
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}

/** Details of a waypoint opened from a markup link. */
@Composable
fun WaypointDialog(
    waypoint: Waypoint,
    lang: String,
    onLink: ((MarkupLink, String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val useFeet by app.userPreferences.useFeet.collectAsState(initial = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(waypoint.name(lang)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                waypoint.ele?.let {
                    Text(
                        UserPreferences.formatElevationM(it, useFeet),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                waypoint.description(lang)?.let {
                    MarkupText(it, onLinkClick = onLink)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}
