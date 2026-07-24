package ch.overlandmap.map.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.data.UserPreferences
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.ui.markup.MarkupLink
import ch.overlandmap.map.ui.markup.MarkupText
import ch.overlandmap.map.ui.theme.contentTextStyle

/**
 * Small popups for objects that markup links can open. Their descriptions are
 * markup themselves, so each takes the enclosing MarkupLinkHost's handler as
 * [onLink]. (Sidebars, being full articles, open as their own screen — see
 * [SidebarScreen] — rather than a dialog.)
 */

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
                        style = contentTextStyle(MaterialTheme.typography.labelLarge),
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
