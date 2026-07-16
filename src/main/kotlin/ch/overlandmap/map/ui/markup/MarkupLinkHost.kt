package ch.overlandmap.map.ui.markup

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.ui.MapObjectPopup
import ch.overlandmap.map.ui.MapPopupKind
import ch.overlandmap.map.ui.MapPopupState
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.home.SidebarPreviewDialog
import ch.overlandmap.map.ui.home.WaypointDialog
import ch.overlandmap.map.ui.zoomToPopupObject
import kotlinx.coroutines.launch
import org.maplibre.android.maps.MapLibreMap

/** A link's popup: what it shows plus what its open (arrow/buy) action does. */
private data class LinkPopup(val kind: MapPopupKind, val open: () -> Unit)

/**
 * Returns the click handler to pass to the screen's [MarkupText]s, and owns
 * the popups their links can open. Like the Flutter app, a link to a
 * waypoint, itinerary or step first shows the small object popup (zoom /
 * open); the open arrow then delegates: cross-itinerary links to
 * [onOpenItinerary], same-itinerary step links to [onJumpToStep] (screens
 * without a current itinerary leave it null), buyable content to
 * [onOpenShopPack] (the pack's shop detail screen, which has the purchase
 * button), web links to the browser. [mapProvider] hands the popup's zoom
 * button the screen's map, when there is one.
 */
@Composable
fun rememberMarkupLinkHandler(
    trackPackId: String,
    sourceItineraryId: String?,
    onOpenItinerary: (documentId: String, stepId: Int?) -> Unit,
    onOpenSidebar: (sidebarId: String) -> Unit,
    onJumpToStep: ((stepId: Int) -> Unit)? = null,
    onOpenShopPack: ((packId: String) -> Unit)? = null,
    mapProvider: () -> MapLibreMap? = { null },
): (MarkupLink, String) -> Unit {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    val lang = currentLanguage()
    val router = remember(trackPackId, sourceItineraryId) {
        MarkupRouter(app.libraryRepository, trackPackId, sourceItineraryId)
    }
    var waypoint by remember { mutableStateOf<Waypoint?>(null) }
    var sidebarPreview by remember { mutableStateOf<Sidebar?>(null) }
    var unavailable by remember { mutableStateOf<String?>(null) }
    var popup by remember { mutableStateOf<LinkPopup?>(null) }

    // The popups' own descriptions contain markup too, so they take this
    // same handler: following a link there replaces the open popup.
    fun handleLink(link: MarkupLink, shownText: String) {
        scope.launch {
            when (val destination = router.resolve(link)) {
                is MarkupDestination.JumpToStep -> {
                    val step = destination.step
                    if (step == null) {
                        onJumpToStep?.invoke(destination.stepId)
                    } else {
                        popup = LinkPopup(MapPopupKind.OfStep(sourceItineraryId ?: "", step)) {
                            onJumpToStep?.invoke(step.stepId)
                        }
                    }
                }
                is MarkupDestination.OpenItinerary -> {
                    val kind = when (val step = destination.step) {
                        null -> MapPopupKind.OfItinerary(destination.itinerary)
                        else -> MapPopupKind.OfStep(destination.itinerary.itineraryId, step)
                    }
                    popup = LinkPopup(kind) {
                        onOpenItinerary(destination.itinerary.documentId, destination.stepId)
                    }
                }
                is MarkupDestination.ShowBuyable -> {
                    val packId = destination.itinerary.trackPackId
                    popup = LinkPopup(
                        MapPopupKind.Buy(packId, packName = null, notInSample = true),
                    ) { onOpenShopPack?.invoke(packId) }
                }
                is MarkupDestination.ShowSidebar -> sidebarPreview = destination.sidebar
                is MarkupDestination.ShowWaypoint -> {
                    val target = destination.waypoint
                    popup = LinkPopup(MapPopupKind.OfWaypoint(target)) { waypoint = target }
                }
                is MarkupDestination.OpenUrl -> try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, destination.url.toUri()))
                } catch (_: ActivityNotFoundException) {
                }
                MarkupDestination.Unavailable -> unavailable = shownText
            }
        }
    }

    popup?.let { current ->
        MapObjectPopup(
            state = MapPopupState(position = null, kind = current.kind),
            onDismiss = { popup = null },
            onZoom = { kind -> mapProvider()?.let { zoomToPopupObject(it, kind) } },
            onOpen = { current.open() },
        )
    }
    waypoint?.let {
        WaypointDialog(it, lang, onLink = ::handleLink, onDismiss = { waypoint = null })
    }
    sidebarPreview?.let { sb ->
        SidebarPreviewDialog(
            sidebar = sb,
            lang = lang,
            onOpen = {
                sidebarPreview = null
                onOpenSidebar(sb.documentId)
            },
            onDismiss = { sidebarPreview = null },
        )
    }
    unavailable?.let { title ->
        AlertDialog(
            onDismissRequest = { unavailable = null },
            title = { Text(title) },
            text = { Text(stringResource(R.string.not_available)) },
            confirmButton = {
                TextButton(onClick = { unavailable = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    return ::handleLink
}
