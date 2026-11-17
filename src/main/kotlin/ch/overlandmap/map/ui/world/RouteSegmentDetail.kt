package ch.overlandmap.map.ui.world

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.AddComment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.routes.RouteSegmentDetails
import ch.overlandmap.map.planning.RouteMode
import ch.overlandmap.map.ui.markup.MarkupText
import ch.overlandmap.map.ui.rememberUnitFormatter
import ch.overlandmap.map.ui.theme.contentTextStyle

private val FerryBlue = Color(0xFF2563EB)
private val ShippingIndigo = Color(0xFF5856D6)
private val LandGray = Color(0xFF8E8E93)

/**
 * Detail of a route segment tapped on the Overlanding map (iOS `RouteSegmentDetailView`).
 *
 * A land segment shows its name, countries and comment. A ferry or shipping segment shows its name
 * and the buttons for contributing a correction or new information, both stored as discussions on
 * the `segment` collection.
 */
@Composable
internal fun RouteSegmentDetail(segment: RouteSegmentSelection, data: OverlandData, lang: String) {
    val app = LocalContext.current.applicationContext as OverlandApp
    var details by remember(segment.id) { mutableStateOf<RouteSegmentDetails?>(null) }
    LaunchedEffect(segment.id) { details = app.routeSegmentRepository.details(segment.id) }
    val discussions = rememberObjectDiscussions(segment.id, COLLECTION)
    val units = rememberUnitFormatter()

    val title = segment.name?.trim()?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.route_unnamed)
    val comment = details?.comment(lang)
    var showCorrection by remember(segment.id) { mutableStateOf(false) }
    var showInput by remember(segment.id) { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title,
                style = contentTextStyle(MaterialTheme.typography.titleLarge),
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
            val (label, color) = when (segment.mode) {
                RouteMode.LAND -> stringResource(R.string.route_land) to LandGray
                RouteMode.FERRY -> stringResource(R.string.route_ferry) to FerryBlue
                RouteMode.SHIPPING -> stringResource(R.string.route_shipping) to ShippingIndigo
            }
            StatusPill(label, color)
        }
        segment.lengthKilometers?.takeIf { segment.mode != RouteMode.SHIPPING }?.let { kilometres ->
            Text(
                stringResource(R.string.route_length) + ": " + units.distanceKm(kilometres, wholeNumbers = true),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        for (code in segment.countries) {
            val country = data.country(code)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                flagEmoji(country?.isoA2).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
                Text(country?.name(lang) ?: code)
            }
        }

        if (segment.mode == RouteMode.LAND) {
            if (comment != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Top) {
                    FieldDiscussionButton(discussions, "comment", title)
                    Comment(comment)
                }
            } else {
                EmptyTabContent(discussions)
            }
        } else {
            if (comment != null) Comment(comment)
            OutlinedButton(onClick = { showCorrection = true }) {
                Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.route_add_correction))
            }
            OutlinedButton(onClick = { showInput = true }) {
                Icon(Icons.Filled.AddComment, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.route_add_information))
            }
        }
    }

    if (showCorrection) FieldDiscussionSheet(discussions, "name", title) { showCorrection = false }
    if (showInput) AddInputSheet(discussions) { showInput = false }
}

@Composable
private fun Comment(text: String) {
    MarkupText(
        text = text,
        style = contentTextStyle(MaterialTheme.typography.bodyLarge),
        onLinkClick = rememberOverlandLinkHandler(),
    )
}

private const val COLLECTION = "segment"
