package ch.overlandmap.map.ui.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.data.UserPreferences
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.ui.markup.MarkupLink
import ch.overlandmap.map.ui.markup.MarkupText
import ch.overlandmap.map.ui.markup.rememberMarkupLinkHandler
import coil.compose.AsyncImage

/**
 * Popups for objects that markup links (and grid tiles) can open. Their
 * descriptions are markup themselves, so each takes the enclosing
 * MarkupLinkHost's handler as [onLink].
 */

/**
 * Full-screen reader for one sidebar article. It owns its own markup link
 * handler so the popups its description's links open (itinerary, waypoint,
 * nested sidebar) render inside this dialog's window — on top — rather than in
 * the host composition behind it.
 */
@Composable
fun SidebarDialog(
    sidebar: Sidebar,
    lang: String,
    trackPackId: String,
    onOpenItinerary: (documentId: String, stepId: Int?) -> Unit,
    onOpenShopPack: ((packId: String) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // The dialog window doesn't report the system nav-bar inset to Compose,
        // so read it from the host Activity and add a 16dp gap on top. Without
        // this the last line sits under the nav bar (any height: ~24dp gesture,
        // ~48dp 3-button) with no visible space.
        val bottomGap = activityBottomInset() + 16.dp
        val onLink = rememberMarkupLinkHandler(
            trackPackId = trackPackId,
            sourceItineraryId = null,
            onOpenItinerary = onOpenItinerary,
            onOpenShopPack = onOpenShopPack,
        )
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
                    // On the scroll viewport (not the content) so the gap stays
                    // visible; sized to the nav bar + 16dp (see [bottomGap]).
                    .padding(bottom = bottomGap)
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
            }
        }
    }
}

/**
 * The host Activity's bottom system-bar (navigation bar) inset, in dp. Read
 * from the Activity window because a Dialog window reports its own insets as
 * zero. Returns 0 if it can't be resolved.
 */
@Composable
private fun activityBottomInset(): Dp {
    val context = LocalContext.current
    val density = LocalDensity.current
    val px = remember(context) {
        var c: Context? = context
        while (c is ContextWrapper && c !is Activity) c = c.baseContext
        (c as? Activity)?.window?.decorView
            ?.let { ViewCompat.getRootWindowInsets(it) }
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
            ?: 0
    }
    return with(density) { px.toDp() }
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
