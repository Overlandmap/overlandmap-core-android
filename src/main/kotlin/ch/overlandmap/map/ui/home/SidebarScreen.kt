package ch.overlandmap.map.ui.home

import androidx.compose.foundation.clickable
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import ch.overlandmap.map.R
import ch.overlandmap.map.geo.GeoBounds
import ch.overlandmap.map.geo.TrackGeometry
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryDifficulty
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.OpenKind
import ch.overlandmap.map.model.Waypoint
import ch.overlandmap.map.ui.MapObjectPopup
import ch.overlandmap.map.ui.MapPopupKind
import ch.overlandmap.map.ui.MapPopupState
import ch.overlandmap.map.ui.rememberUnitFormatter
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.markup.Markup
import ch.overlandmap.map.ui.markup.MarkupText
import ch.overlandmap.map.ui.markup.rememberMarkupLinkHandler
import ch.overlandmap.map.ui.theme.contentTextStyle
import coil.compose.AsyncImage

private val Green = Color(0xFF2E7D32)
private val Orange = Color(0xFFF57C00)
private val Red = Color(0xFFD32F2F)

/** The pack's objects a sidebar's map and table are built from. */
private class SidebarPackData(
    val waypoints: List<Waypoint>,
    val steps: List<ItineraryStep>,
    val itineraries: List<Itinerary>,
    /** Every track of the pack as (lat, lon) points, thinned for drawing. */
    val lines: List<List<Pair<Double, Double>>>,
)

/**
 * Full-screen reader for one sidebar article, loaded by [sidebarId]. A normal
 * navigation destination (not a Dialog): being a regular screen in the app's
 * own window, its Scaffold gets correct system-bar insets — the top bar sits
 * below the status bar and the content clears the navigation bar — with no
 * inset workarounds. Its description's markup links open in place: other
 * sidebars stack on the back stack via [onOpenSidebar].
 *
 * As on iOS, an article can carry the pack's map (`hasMap`), a table of the pack's waypoints,
 * steps or itineraries (`tableColumns`…) whose rows are also the map's dots, and extra photos.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SidebarScreen(
    sidebarId: String,
    onBack: () -> Unit,
    onOpenItinerary: (documentId: String, stepId: Int?) -> Unit,
    onOpenSidebar: (sidebarId: String) -> Unit,
    onOpenShopPack: ((packId: String) -> Unit)? = null,
) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val lang = currentLanguage()
    val sidebar by produceState<Sidebar?>(initialValue = null, sidebarId) {
        value = app.libraryRepository.sidebarById(sidebarId)
    }
    var fullScreenMap by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            if (!fullScreenMap) {
                TopAppBar(
                    title = {
                        Text(
                            sidebar?.name(lang) ?: "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                )
            }
        },
    ) { padding ->
        when (val current = sidebar) {
            null -> Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }

            else -> SidebarContent(
                sidebar = current,
                lang = lang,
                fullScreenMap = fullScreenMap,
                onFullScreenMap = { fullScreenMap = it },
                onOpenItinerary = onOpenItinerary,
                onOpenSidebar = onOpenSidebar,
                onOpenShopPack = onOpenShopPack,
                modifier = Modifier.fillMaxSize().padding(padding),
            )
        }
    }
}

@Composable
private fun SidebarContent(
    sidebar: Sidebar,
    lang: String,
    fullScreenMap: Boolean,
    onFullScreenMap: (Boolean) -> Unit,
    onOpenItinerary: (documentId: String, stepId: Int?) -> Unit,
    onOpenSidebar: (sidebarId: String) -> Unit,
    onOpenShopPack: ((packId: String) -> Unit)?,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val units = rememberUnitFormatter()
    val onLink = rememberMarkupLinkHandler(
        trackPackId = sidebar.trackPackId,
        sourceItineraryId = null,
        onOpenItinerary = onOpenItinerary,
        onOpenSidebar = onOpenSidebar,
        onOpenShopPack = onOpenShopPack,
    )
    val needsPackData = sidebar.hasMap || sidebar.hasTable
    val data by produceState<SidebarPackData?>(initialValue = null, sidebar.documentId) {
        if (!needsPackData) return@produceState
        val library = app.libraryRepository
        val itineraries = library.itinerariesOf(sidebar.trackPackId)
        val mapItineraries = if (sidebar.tableEntity == "Itinerary") itineraries.filter {
            ch.overlandmap.map.model.FS.str(it.extra["sidebarDocId"]) == sidebar.documentId
        } else itineraries
        val lines = mapItineraries.flatMap { library.tracks(it.documentId) }.mapNotNull { track ->
            val points = TrackGeometry.simplify(TrackGeometry.decode(track.coordsBase64), 0.005)
            points.map { it.latitude to it.longitude }.takeIf { it.size > 1 }
        }
        value = SidebarPackData(
            library.waypointsOfPack(sidebar.trackPackId), library.stepsOfPack(sidebar.trackPackId), itineraries, lines,
        )
    }
    val labels = remember(context) { ContextSidebarLabels(context) }
    val rows = remember(sidebar, data, units, lang) {
        val d = data
        if (!sidebar.hasTable || d == null) emptyList()
        else SidebarTable.build(sidebar, d.waypoints, d.steps, d.itineraries, units, lang, labels)
    }
    val mapRows = remember(sidebar, data, units, lang) {
        val d = data
        if (!sidebar.hasMap || d == null) emptyList()
        else SidebarTable.buildForMap(sidebar, d.waypoints, d.steps, d.itineraries, units, lang, labels)
    }
    var focus by remember { mutableStateOf<SidebarFocus?>(null) }
    var focusToken by remember { mutableStateOf(0) }
    // The map first frames the table's objects rather than the whole pack.
    LaunchedEffect(mapRows, data) {
        val boxes = mapRows.mapNotNull { r ->
            r.bounds ?: if (r.lat != null && r.lon != null) GeoBounds(r.lat, r.lat, r.lon, r.lon) else null
        }
        val lineBounds = if (sidebar.tableEntity == "Itinerary") GeoBounds.containing(
            data?.lines.orEmpty().flatten().map { (lat, lon) -> ch.overlandmap.map.geo.Coordinate(lat, lon) },
        ) else null
        val allBoxes = boxes + listOfNotNull(lineBounds)
        if (allBoxes.isNotEmpty()) {
            focus = SidebarFocus(
                bounds = GeoBounds(
                    allBoxes.minOf { it.latMin }, allBoxes.maxOf { it.latMax }, allBoxes.minOf { it.lonMin }, allBoxes.maxOf { it.lonMax },
                ),
                token = ++focusToken,
            )
        }
    }
    var popupRow by remember { mutableStateOf<SidebarRow?>(null) }
    var popupItinerary by remember { mutableStateOf<Itinerary?>(null) }
    var openedPhoto by remember { mutableStateOf<Int?>(null) }

    val photos = remember(sidebar, lang) {
        buildList {
            if (sidebar.titlePhotoId != null || sidebar.localPhotoPath != null) {
                add(viewerPhoto(sidebar.titlePhotoId, sidebar.localPhotoPath, sidebar.titlePhotoCaption(lang)))
            }
            sidebar.localOtherPhotoPaths?.forEachIndexed { index, path ->
                add(ViewerPhoto("file://$path", placeholderUrl = null, caption = sidebar.otherPhotoCaption(index, lang)))
            }
        }
    }
    val hasTitlePhoto = sidebar.titlePhotoId != null || sidebar.localPhotoPath != null

    fun zoomTo(row: SidebarRow) {
        focus = SidebarFocus(bounds = row.bounds, lat = row.lat, lon = row.lon, token = ++focusToken)
    }

    BoxWithConstraints(modifier) {
        val mapHeight = maxOf(220.dp, maxHeight * 0.45f)
        val map: @Composable (Modifier) -> Unit = { mod ->
            Box(mod) {
                SidebarMap(
                    lines = data?.lines.orEmpty(),
                    markers = mapRows.mapNotNull { row ->
                        val lat = row.lat ?: row.bounds?.centre?.latitude ?: return@mapNotNull null
                        val lon = row.lon ?: row.bounds?.centre?.longitude ?: return@mapNotNull null
                        SidebarMarker(row.id, lat, lon)
                    },
                    focus = focus,
                    onMarkerTapped = { id ->
                        val row = mapRows.firstOrNull { it.id == id }
                        if (row?.itinerary != null) popupItinerary = row.itinerary else popupRow = row
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                IconButton(
                    onClick = { onFullScreenMap(!fullScreenMap) },
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f), RoundedCornerShape(10.dp)),
                ) {
                    Icon(
                        if (fullScreenMap) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        contentDescription = stringResource(R.string.full_screen_map),
                    )
                }
            }
        }

        val textContent: @Composable () -> Unit = {
            sidebar.description(lang)?.let {
                MarkupText(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    onLinkClick = onLink,
                    modifier = Modifier.padding(16.dp),
                    allowsTitles = true,
                )
            }
            if (sidebar.hasTable && rows.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    sidebar.tableNames?.get(lang)?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
                    }
                    SidebarTableView(
                        columnTitles = SidebarTable.columnTitles(sidebar, lang),
                        rows = rows,
                        onCellTap = { row, cell ->
                            when (val link = cell.link) {
                                CellLink.OwnEntity ->
                                    if (row.itinerary != null) popupItinerary = row.itinerary else popupRow = row
                                is CellLink.Itinerary ->
                                    popupItinerary = data?.itineraries?.firstOrNull { it.documentId == link.documentId }
                                null -> Unit
                            }
                        },
                        onZoom = ::zoomTo,
                    )
                }
            }
        }
        val extraPhotos: @Composable () -> Unit = {
            // The extra photos, each with its caption.
            photos.drop(if (hasTitlePhoto) 1 else 0).forEachIndexed { offset, photo ->
                val index = offset + (if (hasTitlePhoto) 1 else 0)
                Column(Modifier.padding(horizontal = 15.dp, vertical = 8.dp)) {
                    AsyncImage(
                        model = photo.placeholderUrl ?: photo.url,
                        contentDescription = photo.caption,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { openedPhoto = index },
                    )
                    photo.caption?.takeIf { it.isNotBlank() }?.let { caption ->
                        Text(
                            caption,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        val landscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        if (fullScreenMap && sidebar.hasMap) {
            map(Modifier.fillMaxSize())
        } else if (landscape && (sidebar.hasMap || photos.isNotEmpty())) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    if (sidebar.hasMap) map(Modifier.fillMaxWidth().height(mapHeight))
                    if (hasTitlePhoto) TitlePhoto(sidebar, photos) { openedPhoto = 0 }
                    extraPhotos()
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    textContent()
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                if (sidebar.hasMap) map(Modifier.fillMaxWidth().height(mapHeight))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                    if (!sidebar.hasMap && hasTitlePhoto) TitlePhoto(sidebar, photos) { openedPhoto = 0 }
                    textContent()
                    if (sidebar.hasMap && hasTitlePhoto) TitlePhoto(sidebar, photos) { openedPhoto = 0 }
                    extraPhotos()
                }
            }
        }
    }

    // A row's own object: a waypoint opens its sheet, a step or itinerary the small popup.
    popupRow?.let { row ->
        when (val source = row.source) {
            is Waypoint -> WaypointBottomSheet(
                waypoint = source,
                onDismiss = { popupRow = null },
                onZoom = { popupRow = null; zoomTo(row) },
            )
            is ItineraryStep -> {
                val slug = data?.itineraries?.firstOrNull { it.documentId == source.itineraryId }?.itineraryId.orEmpty()
                MapObjectPopup(
                    state = MapPopupState(position = null, kind = MapPopupKind.OfStep(slug, source)),
                    onDismiss = { popupRow = null },
                    onZoom = { popupRow = null; zoomTo(row) },
                    onOpen = { popupRow = null; onOpenItinerary(source.itineraryId, source.stepId) },
                )
            }
            else -> popupRow = null
        }
    }
    popupItinerary?.let { itinerary ->
        MapObjectPopup(
            state = MapPopupState(position = null, kind = MapPopupKind.OfItinerary(itinerary)),
            onDismiss = { popupItinerary = null },
            onZoom = {
                popupItinerary = null
                (mapRows + rows).firstOrNull { it.id == itinerary.documentId }?.let(::zoomTo)
            },
            onOpen = { popupItinerary = null; onOpenItinerary(itinerary.documentId, null) },
        )
    }
    openedPhoto?.let { start -> FullScreenPhotoViewer(photos, start) { openedPhoto = null } }
}

@Composable
private fun TitlePhoto(sidebar: Sidebar, photos: List<ViewerPhoto>, onClick: () -> Unit) {
    val lang = currentLanguage()
    val url = sidebar.titlePhotoUrl ?: return
    AsyncImage(
        model = url,
        contentDescription = sidebar.titlePhotoCaption(lang),
        modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clickable(onClick = onClick),
        contentScale = ContentScale.Crop,
    )
    photos.firstOrNull()?.caption?.takeIf { it.isNotBlank() }?.let { caption ->
        Text(
            Markup.plainText(caption),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }
}

/** The labels from string resources, for [SidebarTable]. */
private class ContextSidebarLabels(private val context: Context) : SidebarLabels {
    override fun openKind(kind: OpenKind): String {
        val res = when (kind) {
            OpenKind.OPEN -> R.string.open_kind_open
            OpenKind.CLOSED -> R.string.open_kind_closed
            OpenKind.TEMP_CLOSED -> R.string.open_kind_temp_closed
            OpenKind.PERMIT -> R.string.open_kind_permit
            OpenKind.RESTRICTED -> R.string.open_kind_restricted
            OpenKind.TOLL -> R.string.open_kind_toll
            OpenKind.OTHER -> return ""
        }
        return context.getString(res)
    }

    override fun difficulty(difficulty: ItineraryDifficulty) = context.getString(
        when (difficulty) {
            ItineraryDifficulty.EASY -> R.string.difficulty_easy
            ItineraryDifficulty.NORMAL -> R.string.difficulty_normal
            ItineraryDifficulty.HARD -> R.string.difficulty_hard
            ItineraryDifficulty.EXTREME -> R.string.difficulty_extreme
        }
    )

    override fun offroad(percent: Int?) = when (percent) {
        null -> context.getString(R.string.unknown)
        0 -> context.getString(R.string.paved)
        30 -> context.getString(R.string.partly_offroad)
        75 -> context.getString(R.string.mostly_offroad)
        100 -> context.getString(R.string.fully_offroad)
        else -> context.getString(R.string.offroad_percent, percent)
    }
}

@Composable
private fun CellTone.color(): Color = when (this) {
    CellTone.NONE -> MaterialTheme.colorScheme.onSurface
    CellTone.NORMAL -> MaterialTheme.colorScheme.onSurface
    CellTone.GOOD -> Green
    CellTone.WARNING -> Orange
    CellTone.BAD -> Red
    CellTone.ACCENT -> MaterialTheme.colorScheme.primary
    CellTone.MUTED -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** A sidebar's table: a header, then one line per object with a zoom button (iOS `SidebarTableView`). */
@Composable
private fun SidebarTableView(
    columnTitles: List<String>,
    rows: List<SidebarRow>,
    onCellTap: (SidebarRow, SidebarCell) -> Unit,
    onZoom: (SidebarRow) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))) {
            columnTitles.forEach { title ->
                Text(
                    title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.width(36.dp))
        }
        HorizontalDivider()
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                row.cells.forEach { cell ->
                    val tappable = cell.link != null
                    Text(
                        cell.text,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (tappable) FontWeight.Bold else null,
                        color = if (tappable) MaterialTheme.colorScheme.primary else cell.tone.color(),
                        modifier = Modifier
                            .weight(1f)
                            .then(if (tappable) Modifier.clickable { onCellTap(row, cell) } else Modifier)
                            .padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
                IconButton(
                    onClick = { onZoom(row) },
                    enabled = row.bounds != null || (row.lat != null && row.lon != null),
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(R.string.zoom), modifier = Modifier.size(18.dp))
                }
            }
            HorizontalDivider()
        }
    }
}

/**
 * Tap-preview shown when a markup link points to a sidebar: a very small
 * title-photo thumbnail on the left, the name and first lines on the right.
 * Tapping anywhere opens the full [SidebarScreen].
 */
@Composable
fun SidebarPreviewDialog(
    sidebar: Sidebar,
    lang: String,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            tonalElevation = 6.dp,
            modifier = Modifier.clickable(onClick = onOpen),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(12.dp),
            ) {
                sidebar.titlePhotoUrl?.let { url ->
                    AsyncImage(
                        model = url,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(MaterialTheme.shapes.small),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        sidebar.name(lang),
                        style = contentTextStyle(MaterialTheme.typography.titleSmall),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    sidebar.description(lang)?.let { desc ->
                        Text(
                            Markup.plainText(desc, allowsTitles = true),
                            style = contentTextStyle(MaterialTheme.typography.bodySmall),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
