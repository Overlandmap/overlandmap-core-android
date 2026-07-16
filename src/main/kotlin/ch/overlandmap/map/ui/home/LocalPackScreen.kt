package ch.overlandmap.map.ui.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.R
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.ui.MapObjectPopup
import ch.overlandmap.map.ui.MapSettingsButton
import ch.overlandmap.map.ui.MapPopupKind
import ch.overlandmap.map.ui.MapPopupState
import ch.overlandmap.map.ui.PhotoGridTile
import ch.overlandmap.map.ui.VerticalSplit
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.zoomToPopupObject
import ch.overlandmap.map.ui.markup.MarkupLink
import ch.overlandmap.map.ui.markup.MarkupText
import ch.overlandmap.map.ui.markup.rememberMarkupLinkHandler
import ch.overlandmap.map.ui.overlandApp
import ch.overlandmap.map.ui.shop.CommentsTab
import ch.overlandmap.map.ui.shop.DownloadOverlay
import ch.overlandmap.map.ui.shop.PackTracksMap
import ch.overlandmap.map.ui.shop.zoomToPack
import java.text.DateFormat
import java.util.Date
import org.maplibre.android.maps.MapLibreMap

/**
 * Viewer of a downloaded track pack: like the shop's pack detail, but read
 * from the local library, with four tabs — description, itineraries,
 * sidebars, comments.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPackScreen(
    packId: String,
    onBack: () -> Unit,
    onOpenItinerary: (documentId: String, stepId: Int?) -> Unit,
    onOpenShopPack: (packId: String) -> Unit,
    // When set, a Settings action appears in the top bar. The tab-less
    // single-pack app uses it to reach Settings; the multi-pack app leaves it
    // null and reaches Settings through its bottom tab.
    onOpenSettings: (() -> Unit)? = null,
    // Reached when the user taps "purchase" on a free sample without a real
    // account signed in.
    onOpenSignIn: () -> Unit = {},
    viewModel: LocalPackViewModel = viewModel(key = "local-$packId") {
        LocalPackViewModel(overlandApp(), packId)
    },
) {
    val state by viewModel.state.collectAsState()
    val comments by viewModel.comments.collectAsState()
    val selectedItineraryId by viewModel.selectedItineraryId.collectAsState()
    val updateCheck by viewModel.updateCheck.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val owned by viewModel.owned.collectAsState()
    val signedIn by viewModel.signedIn.collectAsState()
    val prices by viewModel.prices.collectAsState()
    val purchasing by viewModel.purchasing.collectAsState()
    val purchaseError by viewModel.purchaseError.collectAsState()
    val lang = currentLanguage()
    val context = LocalContext.current
    val activity = context.findActivity()
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openSidebar by remember { mutableStateOf<Sidebar?>(null) }
    var openBuyable by remember { mutableStateOf<Itinerary?>(null) }
    var popup by remember { mutableStateOf<MapPopupState?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var showSignInDialog by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    // Snackbar for the "check for update" outcome.
    val updateCheckMessage = when (updateCheck) {
        UpdateCheck.AVAILABLE -> stringResource(R.string.update_available)
        UpdateCheck.UP_TO_DATE -> stringResource(R.string.no_update_available)
        UpdateCheck.FAILED -> stringResource(R.string.update_check_failed)
        null -> null
    }
    LaunchedEffect(updateCheck) {
        val message = updateCheckMessage ?: return@LaunchedEffect
        snackbar.showSnackbar(message)
        // Clearing restarts this effect, which then no-ops on null.
        viewModel.updateCheck.value = null
    }

    LaunchedEffect(map, state.pack) {
        val pack = state.pack ?: return@LaunchedEffect
        map?.let { zoomToPack(it, pack) }
    }

    val landscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Root of a single-track-pack app when Settings is wired in (only the
    // single-pack root passes it): no title bar, no back button, map full-bleed
    // to the top edge, Settings floated on the map instead (see below).
    val isRoot = onOpenSettings != null

    val onLink = rememberMarkupLinkHandler(
        trackPackId = packId,
        sourceItineraryId = null,
        onOpenItinerary = onOpenItinerary,
        onOpenShopPack = onOpenShopPack,
        mapProvider = { map },
    )

    // Same full-display layout as the shop's pack detail.
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (!landscape && !isRoot) {
                TopAppBar(
                    title = { Text(state.pack?.name(lang) ?: "") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                    actions = {
                        state.pack?.let { pack ->
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = null)
                            }
                            PackMenu(
                                pack = pack,
                                expanded = menuOpen,
                                onDismiss = { menuOpen = false },
                                onDelete = { confirmDelete = true },
                                onCheckForUpdate = viewModel::checkForUpdate,
                                onUpdate = viewModel::update,
                                onShare = { sharePackLink(context, pack.documentId) },
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        val pack = state.pack
        if (state.loading || pack == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator()
            }
            return@Scaffold
        }

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
        VerticalSplit(
            modifier = Modifier.fillMaxSize(),
            top = {
                Box(modifier = Modifier.fillMaxSize()) {
                    PackTracksMap(
                        packId = packId,
                        selectedItineraryId = selectedItineraryId,
                        onItineraryTapped = { slug, position ->
                            viewModel.selectItinerary(slug)
                            tab = 1
                            // Buyable teasers of a free sample get the buy
                            // popup; downloaded itineraries the regular one.
                            val target = state.itineraries.firstOrNull {
                                it.itineraryId.equals(slug, ignoreCase = true)
                            }
                            popup = MapPopupState(
                                position,
                                if (target != null && !target.isBuyable) {
                                    MapPopupKind.OfItinerary(target)
                                } else {
                                    MapPopupKind.Buy(packId, pack.name(lang), notInSample = true)
                                },
                            )
                        },
                        onMapReady = { map = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    popup?.let { current ->
                        MapObjectPopup(
                            state = current,
                            onDismiss = { popup = null },
                            onZoom = { kind -> map?.let { zoomToPopupObject(it, kind) } },
                            onOpen = { kind ->
                                when (kind) {
                                    is MapPopupKind.OfItinerary ->
                                        onOpenItinerary(kind.itinerary.documentId, null)
                                    is MapPopupKind.Buy -> onOpenShopPack(kind.packId)
                                    else -> Unit
                                }
                            },
                        )
                    }
                    onOpenSettings?.let { open ->
                        MapSettingsButton(
                            onClick = open,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .statusBarsPadding()
                                .padding(12.dp),
                        )
                    }
                    // A free sample offers to buy (or, once purchased, download)
                    // the full pack, right on the map.
                    if (pack.isFreeSample) {
                        SamplePurchaseActions(
                            owned = owned,
                            price = prices[pack.productId],
                            purchasing = purchasing,
                            purchaseError = purchaseError,
                            downloading = downloadProgress != null,
                            onBuy = {
                                if (signedIn) activity?.let(viewModel::buy)
                                else showSignInDialog = true
                            },
                            onDownloadPack = viewModel::downloadPack,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 16.dp),
                        )
                    }
                }
            },
            bottom = {
                // In landscape the app bar is dropped and this content sits
                // beside the map, so its tab row would fall under the status bar
                // (where the system owns touches). Inset it so the tabs are
                // tappable; portrait keeps the map above it, so no inset needed.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (landscape) Modifier.statusBarsPadding() else Modifier),
                ) {
                    ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
                        Tab(
                            selected = tab == 0,
                            onClick = { tab = 0 },
                            text = {
                                Text(pack.name(lang), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            },
                        )
                        Tab(
                            selected = tab == 1,
                            onClick = { tab = 1 },
                            text = { Text(stringResource(R.string.itineraries)) },
                        )
                        Tab(
                            selected = tab == 2,
                            onClick = { tab = 2 },
                            text = { Text(stringResource(R.string.sidebars)) },
                        )
                        Tab(
                            selected = tab == 3,
                            onClick = { tab = 3 },
                            text = { Text(stringResource(R.string.comments)) },
                        )
                    }
                    when (tab) {
                        0 -> LocalDescriptionTab(state, lang, onLink)
                        1 -> LocalItinerariesTab(state, lang, { onOpenItinerary(it, null) }) {
                            openBuyable = it
                        }
                        2 -> SidebarsTab(state.sidebars, lang) { openSidebar = it }
                        3 -> CommentsTab(comments, lang)
                    }
                }
            },
        )
        // An update's re-download, running in the background.
        downloadProgress?.let {
            DownloadOverlay(it, modifier = Modifier.align(Alignment.BottomCenter))
        }
        }
    }

    openSidebar?.let { sidebar ->
        SidebarDialog(
            sidebar,
            lang,
            trackPackId = packId,
            onOpenItinerary = onOpenItinerary,
            onOpenShopPack = onOpenShopPack,
            onDismiss = { openSidebar = null },
        )
    }
    openBuyable?.let { itinerary ->
        BuyableItineraryDialog(itinerary, lang, onLink, onDismiss = { openBuyable = null })
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(state.pack?.name(lang) ?: "") },
            text = { Text(stringResource(R.string.library_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deletePack(onDeleted = onBack)
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (showSignInDialog) {
        AlertDialog(
            onDismissRequest = { showSignInDialog = false },
            title = { Text(stringResource(R.string.sign_in)) },
            text = { Text(stringResource(R.string.sign_in_to_purchase)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignInDialog = false
                    onOpenSignIn()
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignInDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/**
 * The buy / download button riding on a free sample's map. "Download" once the
 * pack is purchased; otherwise "Purchase all itineraries for {price}" (disabled
 * as "Purchase not available" when Play returns no price). A spinner replaces
 * them while a purchase is processed; the download overlay covers downloading.
 */
@Composable
private fun SamplePurchaseActions(
    owned: Boolean,
    price: String?,
    purchasing: Boolean,
    purchaseError: String?,
    downloading: Boolean,
    onBuy: () -> Unit,
    onDownloadPack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        purchaseError?.let {
            Surface(shape = MaterialTheme.shapes.small, tonalElevation = 3.dp) {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        when {
            downloading -> Unit // the progress overlay is already showing
            purchasing -> CircularProgressIndicator()
            owned -> Button(onClick = onDownloadPack) {
                Text(stringResource(R.string.download))
            }
            price.isNullOrBlank() -> Button(onClick = {}, enabled = false) {
                Text(stringResource(R.string.purchase_not_available))
            }
            else -> Button(onClick = onBuy) {
                Text(stringResource(R.string.purchase_all_itineraries_for, price))
            }
        }
    }
}

/**
 * The hosting Activity behind a Compose `LocalContext`, unwrapping the
 * `ContextWrapper` chain — the Play billing flow needs the real Activity.
 */
private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * The pack's overflow menu: version info, delete, update handling (check, or
 * apply when one is known to exist) and link sharing.
 */
@Composable
private fun PackMenu(
    pack: TrackPack,
    expanded: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onCheckForUpdate: () -> Unit,
    onUpdate: () -> Unit,
    onShare: () -> Unit,
) {
    val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Version and generation date of the local copy; not actionable.
        DropdownMenuItem(
            text = {
                val date = pack.lastUpdate?.let { " · " + dateFormat.format(Date(it)) } ?: ""
                Text("v. ${pack.version ?: "—"}$date")
            },
            onClick = {},
            enabled = false,
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.delete)) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
            onClick = {
                onDismiss()
                onDelete()
            },
        )
        if (pack.needsUpdate) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.update)) },
                leadingIcon = { Icon(Icons.Filled.Update, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onUpdate()
                },
            )
        } else {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.check_for_update)) },
                leadingIcon = { Icon(Icons.Filled.Update, contentDescription = null) },
                onClick = {
                    onDismiss()
                    onCheckForUpdate()
                },
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.share_link)) },
            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
            onClick = {
                onDismiss()
                onShare()
            },
        )
    }
}

/** Shares the pack's universal link through the system share sheet. */
private fun sharePackLink(context: Context, packId: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, "https://overlandmap.ch/trackpack/$packId")
    }
    context.startActivity(Intent.createChooser(intent, null))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocalDescriptionTab(
    state: LocalPackState,
    lang: String,
    onLink: (MarkupLink, String) -> Unit,
) {
    val pack = state.pack ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        pack.description(lang)?.let {
            MarkupText(
                it,
                style = MaterialTheme.typography.bodyMedium,
                onLinkClick = onLink,
            )
        }
    }
}

@Composable
private fun LocalItinerariesTab(
    state: LocalPackState,
    lang: String,
    onOpenItinerary: (String) -> Unit,
    onOpenBuyable: (Itinerary) -> Unit,
) {
    // The "free" banner only makes sense in a sample download, where the free
    // itinerary is the single local one among buyable teasers. In a fully
    // downloaded (purchased) pack every itinerary is local, so it's dropped.
    val isSampleDownload = state.itineraries.count { !it.isBuyable } <= 1
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.itineraries.size) { index ->
            val itinerary = state.itineraries[index]
            PhotoGridTile(
                photoUrl = itinerary.titlePhotoUrl,
                label = itinerary.name(lang),
                freeBanner = itinerary.isFree && isSampleDownload,
                onClick = {
                    // A buyable teaser has no steps or tracks to show, so it
                    // gets a description popup instead of the full screen.
                    if (itinerary.isBuyable) {
                        onOpenBuyable(itinerary)
                    } else {
                        onOpenItinerary(itinerary.documentId)
                    }
                },
            )
        }
    }
}

@Composable
private fun SidebarsTab(sidebars: List<Sidebar>, lang: String, onOpen: (Sidebar) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(sidebars.size) { index ->
            val sidebar = sidebars[index]
            PhotoGridTile(
                photoUrl = sidebar.titlePhotoUrl,
                label = sidebar.name(lang),
                onClick = { onOpen(sidebar) },
            )
        }
    }
}
