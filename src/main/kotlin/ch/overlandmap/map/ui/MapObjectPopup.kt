package ch.overlandmap.map.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.CarRepair
import androidx.compose.material.icons.filled.Church
import androidx.compose.material.icons.filled.Deck
import androidx.compose.material.icons.filled.Festival
import androidx.compose.material.icons.filled.Forest
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Museum
import androidx.compose.material.icons.filled.Park
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SportsBar
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Toll
import androidx.compose.material.icons.filled.WineBar
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.data.UserPreferences
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.ui.shop.zoomToItinerary
import ch.overlandmap.map.ui.shop.zoomToPoint
import kotlin.math.roundToInt
import org.maplibre.android.maps.MapLibreMap

/**
 * What the popup opened by a map tap shows. Port of the Flutter app's
 * `showPopup` pull-down menus (models/overland_object.dart): a header naming
 * the tapped object over a zoom/open action row, or a single buy button when
 * the object is not in the local library.
 */
sealed interface MapPopupKind {
    /** An itinerary present in the local library. */
    data class OfItinerary(val itinerary: Itinerary) : MapPopupKind

    /** A step of the itinerary currently on screen. */
    data class OfStep(val itinerarySlug: String, val step: ItineraryStep) : MapPopupKind

    /** A waypoint present in the local library. */
    data class OfWaypoint(val waypoint: Waypoint) : MapPopupKind

    /**
     * Something that is not on the device: a pack never downloaded
     * ([notInSample] false) or an itinerary the free sample leaves out
     * ([notInSample] true). The single button navigates to the pack's shop
     * detail screen, which has the purchase button.
     */
    data class Buy(
        val packId: String,
        val packName: String?,
        val notInSample: Boolean,
    ) : MapPopupKind
}

/**
 * A [MapPopupKind] anchored at the tap position, in pixels of the map view;
 * null centers the popup (used for taps on text links, which have no map
 * position).
 */
data class MapPopupState(val position: Offset?, val kind: MapPopupKind)

/**
 * The popup itself. Compose it inside the Box holding the map view so the
 * anchor coordinates line up. [onZoom] moves the camera to the object;
 * [onOpen] opens its viewer (or, for [MapPopupKind.Buy], the shop detail of
 * the pack). Both dismiss the popup first.
 */
@Composable
fun MapObjectPopup(
    state: MapPopupState,
    onDismiss: () -> Unit,
    onZoom: (MapPopupKind) -> Unit,
    onOpen: (MapPopupKind) -> Unit,
) {
    val positionProvider = remember(state.position) {
        TapPopupPositionProvider(
            state.position?.let { IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
        )
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Column(modifier = Modifier.width(IntrinsicSize.Max).widthIn(min = 180.dp, max = 280.dp)) {
                when (val kind = state.kind) {
                    is MapPopupKind.Buy -> TextButton(
                        onClick = {
                            onDismiss()
                            onOpen(kind)
                        },
                    ) {
                        Text(
                            if (kind.notInSample) stringResource(R.string.not_in_free_sample)
                            else stringResource(R.string.buy_the_pack, kind.packName ?: ""),
                        )
                    }
                    else -> {
                        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            PopupHeader(kind)
                        }
                        HorizontalDivider()
                        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                            ActionButton(Icons.Filled.ZoomIn, stringResource(R.string.zoom_to_object)) {
                                onDismiss()
                                onZoom(kind)
                            }
                            VerticalDivider()
                            ActionButton(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                stringResource(R.string.open_viewer),
                            ) {
                                onDismiss()
                                onOpen(kind)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.ActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, modifier = Modifier.weight(1f)) {
        Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun PopupHeader(kind: MapPopupKind) {
    val lang = currentLanguage()
    when (kind) {
        // Slug and name in a column, the route icon to the right.
        is MapPopupKind.OfItinerary -> Row(verticalAlignment = Alignment.CenterVertically) {
            val itinerary = kind.itinerary
            Column(modifier = Modifier.weight(1f)) {
                if (itinerary.itineraryId.isNotEmpty()) {
                    Text(itinerary.itineraryId, style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    itinerary.name(lang),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                Icons.Filled.Route,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        // Slug and the circled step number, the step name below.
        is MapPopupKind.OfStep -> Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (kind.itinerarySlug.isNotEmpty()) {
                    Text(kind.itinerarySlug, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                StepCircle(kind.step.stepId)
            }
            Text(
                kind.step.name(lang),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // Name and altitude in a column, the type's icon to the right.
        is MapPopupKind.OfWaypoint -> Row(verticalAlignment = Alignment.CenterVertically) {
            val app = LocalContext.current.applicationContext as OverlandApp
            val useFeet by app.userPreferences.useFeet.collectAsState(initial = false)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    kind.waypoint.name(lang),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                kind.waypoint.ele?.let {
                    Text(
                        UserPreferences.formatElevationM(it, useFeet),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                openStatusText(kind.waypoint.openKind, kind.waypoint.openDetails)?.let { status ->
                    Text(status, style = MaterialTheme.typography.bodySmall)
                }
            }
            Icon(
                waypointIcon(kind.waypoint.type),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
        is MapPopupKind.Buy -> Unit // rendered as a single button, no header
    }
}

/** The step number as drawn on the map: black digits in an outlined circle. */
@Composable
private fun StepCircle(stepId: Int) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(24.dp)
            .border(1.5.dp, MaterialTheme.colorScheme.onSurface, CircleShape),
    ) {
        Text("$stepId", style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Centers the popup on the tap, above it when there is room and below it
 * otherwise, clamped to the window. Without a tap it centers in the window.
 */
private class TapPopupPositionProvider(private val tap: IntOffset?) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        if (tap == null) {
            return IntOffset(
                (windowSize.width - popupContentSize.width) / 2,
                (windowSize.height - popupContentSize.height) / 2,
            )
        }
        val margin = 16
        val x = (anchorBounds.left + tap.x - popupContentSize.width / 2)
            .coerceIn(margin, maxOf(margin, windowSize.width - popupContentSize.width - margin))
        val above = anchorBounds.top + tap.y - popupContentSize.height - margin
        val y = if (above >= margin) above else anchorBounds.top + tap.y + margin
        return IntOffset(x, y)
    }
}

/** Camera move for the popup's magnifier action. */
fun zoomToPopupObject(map: MapLibreMap, kind: MapPopupKind) {
    when (kind) {
        is MapPopupKind.OfItinerary -> zoomToItinerary(map, kind.itinerary)
        is MapPopupKind.OfStep -> {
            val lat = kind.step.lat ?: return
            val lon = kind.step.lon ?: return
            zoomToPoint(map, lat, lon)
        }
        is MapPopupKind.OfWaypoint -> {
            val lat = kind.waypoint.lat ?: return
            val lon = kind.waypoint.lon ?: return
            zoomToPoint(map, lat, lon)
        }
        is MapPopupKind.Buy -> Unit
    }
}

/**
 * Icon of a waypoint's Garmin type, by way of the maki name — the same
 * two-step mapping as the Flutter app's `Waypoint.makiToIcon`.
 */
private fun waypointIcon(type: String?): ImageVector = when (garminTypeToMaki[type]) {
    "car-repair" -> Icons.Filled.CarRepair
    "bar" -> Icons.Filled.SportsBar
    "campsite" -> Icons.Filled.Festival
    "drinking-water" -> Icons.Filled.LocalDrink
    "information" -> Icons.Filled.Info
    "lodging" -> Icons.Filled.Hotel
    "museum" -> Icons.Filled.Museum
    "picnic-site" -> Icons.Filled.Deck
    "restaurant" -> Icons.Filled.Restaurant
    "viewpoint" -> Icons.Filled.PhotoCamera
    "toll" -> Icons.Filled.Toll
    "alcohol-shop" -> Icons.Filled.WineBar
    "fuel" -> Icons.Filled.LocalGasStation
    "swimming" -> Icons.Filled.Pool
    "mountain" -> Icons.Filled.Terrain
    "cross" -> Icons.Filled.AltRoute
    "shop" -> Icons.Filled.ShoppingCart
    "park" -> Icons.Filled.Park
    "forest" -> Icons.Filled.Forest
    "religious-christian", "religious-buddhist" -> Icons.Filled.Church
    "police" -> Icons.Filled.LocalPolice
    else -> Icons.Filled.Place
}

private val garminTypeToMaki = mapOf(
    "ATV" to "car-repair",
    "Bar" to "bar",
    "Campground" to "campsite",
    "Drinking Water" to "drinking-water",
    "Information" to "information",
    "Lodge" to "lodging",
    "Lodging" to "lodging",
    "Museum" to "museum",
    "Picnic Area" to "picnic-site",
    "Restaurant" to "restaurant",
    "Scenic Area" to "viewpoint",
    "Toll Booth" to "toll",
    "Winery" to "alcohol-shop",
    "Gas Station" to "fuel",
    "Swimming Area" to "swimming",
    "Beach" to "swimming",
    "Summit" to "mountain",
    "Tunnel" to "tunnel",
    "Crossing" to "cross",
    "Shopping Center" to "shop",
    "Animal Tracks" to "zoo",
    "Park" to "park",
    "Forest" to "forest",
    "Church" to "religious-christian",
    "Police Station" to "police",
)
