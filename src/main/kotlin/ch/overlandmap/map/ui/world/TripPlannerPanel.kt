package ch.overlandmap.map.ui.world

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.foundation.background
import androidx.compose.material3.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Anchor
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import ch.overlandmap.map.R
import ch.overlandmap.map.ui.theme.contentTextStyle
import ch.overlandmap.map.ui.markup.MarkupText
import ch.overlandmap.map.model.CountryVisa
import ch.overlandmap.map.model.CountryCarnet
import ch.overlandmap.map.model.Country
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.layout.PaddingValues
import java.time.format.FormatStyle
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.time.LocalDate
import java.time.Instant
import ch.overlandmap.map.data.routes.PlanMessage
import ch.overlandmap.map.data.routes.TripPlanner
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.planning.PlanWaypoint
import ch.overlandmap.map.planning.PlannedTrip
import ch.overlandmap.map.planning.RouteMode
import ch.overlandmap.map.planning.RoutePlanner
import ch.overlandmap.map.planning.RoutingRestrictions
import ch.overlandmap.map.planning.TripLeg
import ch.overlandmap.map.planning.TripSummary
import ch.overlandmap.map.planning.tripDays
import ch.overlandmap.map.ui.rememberUnitFormatter

/*
 * The panes of planning mode (iOS `TripPlannerPanel`, `TripBreakdown`): the planner shown while
 * planning, and the read-only detail of a trip tapped on the map.
 */

private val Ferry = Color(0xFF2563EB)
private val Shipping = Color(0xFF5856D6)
private val Warn = Color(0xFFF59E0B)

/** The hex colours a trip can be given. */
private val TripColors = listOf(
    "#ff0000", "#f97316", "#eab308", "#22c55e", "#14b8a6",
    "#2563eb", "#6366f1", "#a855f7", "#ec4899", "#111111",
)

internal fun tripColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Red)

@Composable
private fun PlanMessage.text(): String = when (this) {
    PlanMessage.NoConnection -> stringResource(R.string.plan_no_connection)
    PlanMessage.GeometryUnavailable -> stringResource(R.string.plan_geometry_unavailable)
    is PlanMessage.Failure -> text
}

// ── Planner ─────────────────────────────────────────────────────────────────

/**
 * The pane shown in planning mode: trips, the active trip's name and colour, its breakdown, and the
 * routing options.
 */
@Composable
internal fun TripPlannerPanel(
    vm: OverlandingViewModel,
    data: OverlandData,
    lang: String,
    selectedLegId: String?,
    onSelectLeg: (TripLeg) -> Unit,
    onNewTrip: () -> Unit,
    onDeleteTrip: () -> Unit,
) {
    val planner = vm.planner
    val state by vm.plannerState.collectAsState()
    val installed by vm.plannerDataInstalled.collectAsState()
    val online by vm.isOnline.collectAsState()
    val isReady = installed && state.graphLoaded
    val trip = state.activeTrip

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.plan_your_trip),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onNewTrip, enabled = isReady) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.plan_new_trip))
            }
        }
        if (!online) {
            Row(
                Modifier.fillMaxWidth().background(Warn).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.WifiOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.plan_offline), color = Color.White, style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // At the top, where it cannot be scrolled out of sight below a long breakdown.
            if (isReady) state.message?.let {
                Text(it.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (!isReady) {
                PlannerDataSection(installed, state.loadingGraph, state.message, onDownload = vm::downloadRouteGraph)
            } else {
                if (trip != null) {
                    val kmPerDay = state.kmPerDay(trip)
                    TripHeader(planner, state.trips, trip, kmPerDay, onDeleteTrip, onTextEditingChange = vm::setTextEditing)
                    TripStatsRow(planner, trip, state.geometryEpoch, kmPerDay)
                    if (trip.segmentIds.isEmpty()) {
                        PlanHint(noTrips = false)
                    } else {
                        TripBreakdownSection(
                            planner = planner,
                            trip = trip,
                            geometryEpoch = state.geometryEpoch,
                            data = data,
                            lang = lang,
                            selectedLegId = selectedLegId,
                            onSelectLeg = onSelectLeg,
                            onOpenLeg = vm::showLeg,
                            kmPerDay = kmPerDay,
                        )
                    }
                } else {
                    TripPicker(planner, state.trips, state.activeId)
                    PlanHint(noTrips = state.trips.isEmpty())
                }
            }
        }
    }
}

@Composable
private fun PlannerDataSection(installed: Boolean, loading: Boolean, message: PlanMessage?, onDownload: () -> Unit) {
    when {
        !installed -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.plan_graph_missing), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onDownload) { Text(stringResource(R.string.plan_download_graph)) }
        }
        message != null && !loading ->
            Text(message.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.plan_loading_graph), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PlanHint(noTrips: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        Icon(Icons.Filled.Flag, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            stringResource(if (noTrips) R.string.plan_no_trips else R.string.plan_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TripPicker(planner: TripPlanner, trips: List<PlannedTrip>, activeId: String?) {
    var open by remember { mutableStateOf(false) }
    val active = trips.firstOrNull { it.id == activeId }
    Box {
        TextButton(onClick = { open = true }, enabled = trips.isNotEmpty()) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(tripColor(active?.displayColor ?: PlannedTrip.DEFAULT_COLOR)))
            Spacer(Modifier.width(8.dp))
            Text(active?.name ?: stringResource(R.string.plan_trips), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Filled.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (trip in trips) {
                DropdownMenuItem(
                    text = { Text(trip.name) },
                    leadingIcon = {
                        if (trip.id == activeId) Icon(Icons.Filled.Check, contentDescription = null)
                        else Box(Modifier.size(24.dp))
                    },
                    onClick = {
                        planner.select(trip.id)
                        open = false
                    },
                )
            }
        }
    }
}

/**
 * The active trip: its name with its colour, a pencil that opens the editing controls (name, colour,
 * kilometres per day; a checkmark closes them), and the eye that shows or hides it on the map.
 */
@Composable
private fun TripHeader(
    planner: TripPlanner,
    trips: List<PlannedTrip>,
    trip: PlannedTrip,
    kmPerDay: Double,
    onDelete: () -> Unit,
    onTextEditingChange: (Boolean) -> Unit,
) {
    var editing by remember(trip.id) { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var nameDraft by remember(trip.id, trip.name) { mutableStateOf(trip.name) }

    fun commit() {
        val name = nameDraft.trim()
        if (name.isEmpty()) nameDraft = trip.name
        else if (name != trip.name) planner.rename(trip.id, name)
    }
    fun dismissKeyboard() {
        focus.clearFocus()
        keyboard?.hide()
    }
    // The keyboard follows the editing controls: leaving them clears the map's "typing" state.
    DisposableEffect(trip.id) { onDispose { onTextEditingChange(false) } }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f, fill = false)) { TripPicker(planner, trips, trip.id) }
            IconButton(onClick = {
                if (editing) {
                    commit()
                    dismissKeyboard()
                    onTextEditingChange(false)
                }
                editing = !editing
            }) {
                Icon(
                    if (editing) Icons.Filled.Check else Icons.Filled.Edit,
                    contentDescription = stringResource(if (editing) R.string.ok else R.string.plan_edit_trip),
                )
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { planner.setVisible(trip.id, !trip.isVisible) }) {
                Icon(
                    if (trip.isVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = stringResource(if (trip.isVisible) R.string.plan_hide_trip else R.string.plan_show_trip),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.plan_delete_trip))
            }
        }
        if (editing) {
            val done = KeyboardActions(onDone = {
                commit()
                dismissKeyboard()
            })
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it },
                singleLine = true,
                label = { Text(stringResource(R.string.plan_trip_name)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = done,
                modifier = Modifier.fillMaxWidth().onFocusChanged { onTextEditingChange(it.isFocused) },
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val current = trip.displayColor.lowercase()
                for (hex in TripColors) {
                    val selected = hex == current
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .background(tripColor(hex))
                            .border(
                                if (selected) 3.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                CircleShape,
                            )
                            .clickable(onClickLabel = stringResource(R.string.plan_color)) { planner.setColor(trip.id, hex) },
                    )
                }
            }
            KmPerDayField(
                km = kmPerDay,
                onChange = { planner.setKmPerDay(trip.id, it) },
                onFocusChange = onTextEditingChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val MILES_PER_KM = 0.621371

/** The value shown in a kilometres- or miles-per-day field: whole miles, trimmed kilometres. */
private fun kmPerDayText(km: Double, metric: Boolean): String {
    if (!metric) return Math.round(km * MILES_PER_KM).toString()
    return if (km == Math.rint(km)) km.toLong().toString() else km.toString()
}

/**
 * A field for a distance per day, shown in the user's units (kilometres or miles, from the unit
 * preference) and reported in kilometres. Saved on the keyboard's OK or when it loses focus; an
 * unreadable value goes back to the current one, and a value left as shown changes nothing.
 */
@Composable
internal fun KmPerDayField(
    km: Double,
    onChange: (Double) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metric = rememberUnitFormatter().format.distanceMetric
    val shown = kmPerDayText(km, metric)
    var draft by remember(shown) { mutableStateOf(shown) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun commit() {
        val value = draft.replace(',', '.').toDoubleOrNull()
        if (value == null || value <= 0) {
            draft = shown
        } else if (value != shown.toDoubleOrNull()) {
            onChange(if (metric) value else value / MILES_PER_KM)
        }
    }
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        singleLine = true,
        label = { Text(stringResource(if (metric) R.string.plan_km_per_day else R.string.plan_miles_per_day)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            commit()
            focus.clearFocus()
            keyboard?.hide()
        }),
        modifier = modifier.onFocusChanged {
            onFocusChange(it.isFocused)
            if (!it.isFocused) commit()
        },
    )
}

@Composable
private fun TripBreakdownSection(
    planner: TripPlanner,
    trip: PlannedTrip,
    geometryEpoch: Int,
    data: OverlandData,
    lang: String,
    selectedLegId: String?,
    onSelectLeg: (TripLeg) -> Unit,
    onOpenLeg: (TripLeg) -> Unit,
    kmPerDay: Double,
) {
    val summary = remember(trip.segmentIds, trip.waypoints, geometryEpoch) { planner.summary(trip) } ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.plan_breakdown),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (planner.activeTripConflicts()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = Warn)
                Text(stringResource(R.string.plan_conflict), color = Warn, style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { planner.trimSegment(RoutePlanner.TripEnd.START) }) {
                Text(stringResource(R.string.plan_remove_first), style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { planner.trimSegment(RoutePlanner.TripEnd.END) }) {
                Text(stringResource(R.string.plan_remove_last), style = MaterialTheme.typography.bodySmall)
            }
        }
        TripLegsList(
            summary = summary,
            data = data,
            lang = lang,
            waypoints = trip.waypoints.orEmpty(),
            onDeleteWaypoint = { planner.removeWaypoint(it) },
            selectedLegId = selectedLegId,
            onSelectLeg = onSelectLeg,
            onOpenLeg = onOpenLeg,
            kmPerDay = kmPerDay,
            startDate = trip.startDate,
            onStartDateChange = { planner.setStartDate(trip.id, it) },
        )
    }
}

// ── Stats and legs ──────────────────────────────────────────────────────────

/** Distance, countries and ferry crossings of a trip. */
@Composable
internal fun TripStatsRow(planner: TripPlanner, trip: PlannedTrip, geometryEpoch: Int, kmPerDay: Double) {
    val summary = remember(trip.segmentIds, trip.waypoints, geometryEpoch) { planner.summary(trip) }
    val units = rememberUnitFormatter()
    val meters = summary?.distanceMeters ?: trip.distanceMeters
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Stat(meters?.let { units.distanceKm(it / 1000.0) } ?: "—", stringResource(R.string.distance))
        // The legs' exact fractions of days add up to this; only the total shown is rounded.
        Stat(tripDays(meters, kmPerDay)?.let { Math.round(it).toString() } ?: "—", stringResource(R.string.plan_days_label))
        Stat("${summary?.countries?.size ?: 0}", stringResource(R.string.plan_countries))
        Stat("${summary?.ferryCrossings ?: 0}", stringResource(R.string.plan_ferries))
    }
}

@Composable
private fun Stat(value: String, title: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private val TimelineBackground = Color(0xFFEFEFF3)
private val TimelineLine = Color(0xFFB8B8C2)
private val TileInk = Color(0xFF1B1B1B)
private val TileSecondary = Color(0xFF6B6B73)
private val TileSelected = Color(0xFFD9F8FF)

/** One tile of the timeline: the badge on the line (the tile's icon), its background, and what it shows. */
private class TimelineEntry(
    val badge: @Composable () -> Unit,
    val tile: Color = Color.White,
    val content: @Composable () -> Unit,
)

/** A round white badge on the timeline, ringed in [ring], holding the icon of its tile. */
@Composable
private fun TimelineBadge(ring: Color, icon: @Composable () -> Unit) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(Color.White).border(1.5.dp, ring, CircleShape),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

@Composable
private fun IconBadge(icon: ImageVector, tint: Color) {
    TimelineBadge(ring = tint) { Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp)) }
}

/** The badge of a leg: the flag of its country on land, a boat for a ferry, an anchor for shipping. */
@Composable
private fun LegBadge(leg: TripLeg, data: OverlandData) {
    when (leg.mode) {
        RouteMode.LAND -> {
            val flag = flagEmoji(leg.countries.firstOrNull()?.let { data.country(it)?.isoA2 })
            if (flag.isNotEmpty()) {
                TimelineBadge(ring = MaterialTheme.colorScheme.primary) { Text(flag, fontSize = 15.sp) }
            } else {
                IconBadge(Icons.Filled.Timeline, MaterialTheme.colorScheme.primary)
            }
        }
        RouteMode.FERRY -> IconBadge(Icons.Filled.DirectionsBoat, Ferry)
        RouteMode.SHIPPING -> IconBadge(Icons.Filled.Anchor, Shipping)
    }
}

/**
 * The segments of a trip, grouped into legs, with the border posts crossed between them, as white
 * raised tiles along a vertical timeline on a light gray background. In planning mode
 * ([onDeleteWaypoint] set) the flags are listed under the leg they sit on, each with a delete
 * button, and tapping a leg highlights it on the map. [onOpenLeg] adds an arrow to each leg that
 * opens its country (land) or its ferry or shipping segment.
 */
@Composable
internal fun TripLegsList(
    summary: TripSummary,
    data: OverlandData,
    lang: String,
    onOpenBorderPost: ((BorderPost) -> Unit)? = null,
    waypoints: List<PlanWaypoint> = emptyList(),
    onDeleteWaypoint: ((String) -> Unit)? = null,
    selectedLegId: String? = null,
    onSelectLeg: ((TripLeg) -> Unit)? = null,
    onOpenLeg: ((TripLeg) -> Unit)? = null,
    kmPerDay: Double = PlannedTrip.DEFAULT_KM_PER_DAY,
    startDate: LocalDate? = null,
    onStartDateChange: ((LocalDate?) -> Unit)? = null,
) {
    val units = rememberUnitFormatter()
    val entries = ArrayList<TimelineEntry>()
    // In planning mode the start flag comes first, before the first segment.
    if (onDeleteWaypoint != null) {
        waypoints.firstOrNull()?.let { start ->
            val color = flagTint(1, waypoints.size)
            entries += TimelineEntry(badge = { IconBadge(Icons.Filled.Flag, color) }) {
                FlagRow(1, waypoints.size, onDelete = { onDeleteWaypoint(start.id) }) {
                    StartDateField(startDate, onStartDateChange)
                }
            }
        }
    }
    // The start of each leg is the start date plus the days (exact fractions) of the legs before it.
    var metersBefore = 0.0
    for (leg in summary.legs) {
        val legStart = startDate?.plusDays(Math.floor(metersBefore / 1000.0 / kmPerDay).toLong())
        metersBefore += leg.lengthMeters ?: 0.0
        leg.borderPostId?.let { data.post(it) }?.let { post ->
            // A boom gate, in the status colour of the post.
            entries += TimelineEntry(badge = { IconBadge(ImageVector.vectorResource(R.drawable.ic_boom_gate), post.openState.color()) }) {
                BorderPostRow(post, onOpenBorderPost)
            }
        }
        val selected = leg.id == selectedLegId
        entries += TimelineEntry(
            badge = { LegBadge(leg, data) },
            tile = if (selected) TileSelected else Color.White,
        ) {
            LegRow(
                leg = leg, data = data, lang = lang, units = units, kmPerDay = kmPerDay, start = legStart,
                onClick = onSelectLeg?.let { select -> { select(leg) } },
                onOpen = onOpenLeg?.takeIf { legTarget(leg, data) }?.let { open -> { open(leg) } },
            )
        }
        if (onDeleteWaypoint != null) {
            waypoints.forEachIndexed { index, waypoint ->
                if (index > 0 && waypoint.segmentIndex in leg.startIndex..leg.endIndex) {
                    val color = flagTint(index + 1, waypoints.size)
                    entries += TimelineEntry(badge = { IconBadge(Icons.Filled.Flag, color) }) {
                        FlagRow(index + 1, waypoints.size, onDelete = { onDeleteWaypoint(waypoint.id) })
                    }
                }
            }
        }
    }
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(TimelineBackground).padding(vertical = 8.dp, horizontal = 6.dp),
    ) {
        entries.forEachIndexed { index, entry ->
            TimelineItem(first = index == 0, last = index == entries.lastIndex, entry = entry)
        }
    }
}

/** Whether [leg] has something to open: a country (land), or its segment (ferry, shipping). */
private fun legTarget(leg: TripLeg, data: OverlandData): Boolean =
    if (leg.mode == RouteMode.LAND) leg.countries.firstOrNull()?.let { data.country(it) } != null
    else leg.segmentIds.isNotEmpty()

@Composable
private fun TimelineItem(first: Boolean, last: Boolean, entry: TimelineEntry) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        // The timeline: a line through the whole item, the tile's badge at its middle.
        Box(
            Modifier.width(36.dp).fillMaxHeight().drawBehind {
                val x = size.width / 2
                val middle = size.height / 2
                drawLine(
                    TimelineLine, Offset(x, if (first) middle else 0f), Offset(x, if (last) middle else size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            },
            contentAlignment = Alignment.Center,
        ) { entry.badge() }
        Surface(
            modifier = Modifier.weight(1f).padding(vertical = 3.dp),
            shape = RoundedCornerShape(10.dp),
            color = entry.tile,
            contentColor = TileInk,
            shadowElevation = 1.5.dp,
        ) { entry.content() }
    }
}

@Composable
private fun LegRow(
    leg: TripLeg,
    data: OverlandData,
    lang: String,
    units: ch.overlandmap.map.units.UnitFormatter,
    kmPerDay: Double,
    start: LocalDate?,
    onClick: (() -> Unit)?,
    onOpen: (() -> Unit)?,
) {
    // A ferry or shipping leg is titled with its segment's name. A land leg with the names of its
    // countries; when the graph gives the segment no country, with the segment's own name.
    val title = when {
        leg.mode != RouteMode.LAND -> leg.segmentName ?: leg.name ?: modeLabel(leg.mode)
        else -> leg.countries.joinToString(" → ") { data.country(it)?.name(lang) ?: it }
            .ifEmpty { leg.segmentName ?: stringResource(R.string.route_unnamed) }
    }
    // The countries of a land leg that ask for a visa (at the border, e-visa, embassy) or a carnet.
    val countries = if (leg.mode == RouteMode.LAND) leg.countries.mapNotNull { data.country(it) } else emptyList()
    val visaCountries = countries.filter { it.visaStatus in VisaNeeded }
    val carnetCountries = countries.filter { it.carnetStatus in CarnetNeeded }
    var notice by remember { mutableStateOf<Notice?>(null) }
    notice?.let { kind ->
        CountryNoticeDialog(kind, if (kind == Notice.VISA) visaCountries else carnetCountries, lang) { notice = null }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = if (onOpen != null) 2.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            start?.let { Text(formatDate(it), style = MaterialTheme.typography.bodySmall, color = TileSecondary) }
            if (visaCountries.isNotEmpty() || carnetCountries.isNotEmpty()) {
                Row {
                    if (visaCountries.isNotEmpty()) NoticeButton(R.string.plan_visa_button) { notice = Notice.VISA }
                    if (carnetCountries.isNotEmpty()) NoticeButton(R.string.plan_carnet_button) { notice = Notice.CARNET }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (leg.mode != RouteMode.SHIPPING && leg.lengthMeters != null) {
                Text(units.distanceKm(leg.lengthMeters / 1000.0), style = MaterialTheme.typography.bodyMedium)
            }
            if (leg.mode == RouteMode.LAND) {
                // The exact fraction of days is what adds up to the trip's total; it is only rounded
                // for display.
                tripDays(leg.lengthMeters, kmPerDay)?.let { exact ->
                    val days = Math.round(exact).toInt()
                    Text(
                        pluralStringResource(R.plurals.plan_days, days, days),
                        style = MaterialTheme.typography.bodySmall,
                        color = TileSecondary,
                    )
                }
            }
        }
        if (onOpen != null) {
            IconButton(onClick = onOpen, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = title, tint = TileSecondary)
            }
        }
    }
}

private enum class Notice { VISA, CARNET }

private val VisaNeeded = setOf(CountryVisa.BORDER, CountryVisa.E_VISA, CountryVisa.EMBASSY)
private val CarnetNeeded = setOf(CountryCarnet.MANDATORY, CountryCarnet.REQUIRED_IN_SOME_SITUATIONS)

@Composable
private fun NoticeButton(label: Int, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(30.dp),
    ) { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) }
}

/**
 * The visa or carnet situation of the countries of a leg: the status as the title, the (localized)
 * comment as the content. With several countries each gets a heading of its own.
 */
@Composable
private fun CountryNoticeDialog(kind: Notice, countries: List<Country>, lang: String, onDismiss: () -> Unit) {
    @Composable fun status(c: Country) = if (kind == Notice.VISA) c.visaStatus.label() else c.carnetStatus.label()
    fun comment(c: Country) = (if (kind == Notice.VISA) c.visaComment(lang) else c.carnetComment(lang))?.takeIf { it.isNotBlank() }
    val onLink = rememberOverlandLinkHandler()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (countries.size == 1) status(countries[0])
                else stringResource(if (kind == Notice.VISA) R.string.plan_visa_button else R.string.plan_carnet_button),
            )
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (country in countries) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (countries.size > 1) {
                            Text(
                                listOf(flagEmoji(country.isoA2), country.name(lang), "·", status(country))
                                    .filter { it.isNotEmpty() }.joinToString(" "),
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        val text = comment(country)
                        if (text != null) {
                            MarkupText(text = text, style = contentTextStyle(MaterialTheme.typography.bodyLarge), onLinkClick = onLink)
                        } else {
                            Text(stringResource(R.string.route_no_comment), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
private fun modeLabel(mode: RouteMode): String = stringResource(
    when (mode) {
        RouteMode.LAND -> R.string.route_land
        RouteMode.FERRY -> R.string.route_ferry
        RouteMode.SHIPPING -> R.string.route_shipping
    }
)

private fun flagTint(number: Int, count: Int): Color = when (number) {
    1 -> Color(0xFF22C55E)
    count -> Color(0xFFEF4444)
    else -> Color.Gray
}

/** A flag of the trip: "Start" for the first, "End" for the last, "Waypoint n" in between. */
@Composable
private fun FlagRow(number: Int, count: Int, onDelete: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    val title = when (number) {
        1 -> stringResource(R.string.plan_start)
        count -> stringResource(R.string.plan_end)
        else -> stringResource(R.string.plan_waypoint, number)
    }
    Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 2.dp, bottom = if (extra != null) 8.dp else 0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.plan_delete_waypoint) + " " + title,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
        extra?.let { Box(Modifier.padding(end = 10.dp)) { it() } }
    }
}

private fun formatDate(date: LocalDate): String =
    date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/**
 * The trip's start date, edited with a date picker. A field that opens the picker; the picker can
 * also clear the date. Without [onChange] (read-only) it only shows the date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StartDateField(date: LocalDate?, onChange: ((LocalDate?) -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        enabled = onChange != null,
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        contentColor = TileInk,
        border = BorderStroke(1.dp, TimelineLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = TileSecondary, modifier = Modifier.size(20.dp))
            Column {
                Text(stringResource(R.string.plan_start_date), style = MaterialTheme.typography.labelSmall, color = TileSecondary)
                Text(
                    date?.let(::formatDate) ?: stringResource(R.string.plan_set_start_date),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (date != null) TileInk else TileSecondary,
                )
            }
        }
    }
    if (open && onChange != null) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                Row {
                    if (date != null) {
                        TextButton(onClick = {
                            onChange(null)
                            open = false
                        }) { Text(stringResource(R.string.plan_clear_date)) }
                    }
                    TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) }
                }
            },
        ) { DatePicker(state = state) }
    }
}

/** A border post crossed between two legs, with its status in the status colour. */
@Composable
private fun BorderPostRow(post: BorderPost, onOpen: ((BorderPost) -> Unit)?) {
    val state = post.openState
    val color = state.color()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable { onOpen(post) } else Modifier)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                post.name.ifEmpty { stringResource(R.string.border_posts) },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(state.label(), style = MaterialTheme.typography.bodySmall, color = color)
        }
        if (onOpen != null) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = TileSecondary)
        }
    }
}

// ── Trip detail (normal mode) ───────────────────────────────────────────────

/** Read-only detail of a trip selected on the map in normal mode. */
@Composable
internal fun TripDetail(
    tripId: String,
    vm: OverlandingViewModel,
    data: OverlandData,
    lang: String,
    onClose: () -> Unit,
    onEdit: () -> Unit,
    onOpenBorderPost: (BorderPost) -> Unit,
) {
    val planner = vm.planner
    val state by vm.plannerState.collectAsState()
    val trip = state.trips.firstOrNull { it.id == tripId }
    if (trip == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(tripColor(trip.displayColor)))
            Text(
                trip.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
            }
        }
        TripStatsRow(planner, trip, state.geometryEpoch, state.kmPerDay(trip))
        OutlinedButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.plan_plan_trip))
        }
        val summary = remember(trip.segmentIds, trip.waypoints, state.geometryEpoch, state.graphLoaded) { planner.summary(trip) }
        if (summary != null) {
            Text(
                stringResource(R.string.plan_breakdown),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TripLegsList(
                summary = summary, data = data, lang = lang, onOpenBorderPost = onOpenBorderPost,
                onOpenLeg = vm::showLeg,
                kmPerDay = state.kmPerDay(trip),
                startDate = trip.startDate,
            )
        } else if (trip.segmentIds.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.plan_loading_graph), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

// ── Planning options box ────────────────────────────────────────────────────

/**
 * Replaces the legend in planning mode: what the planner avoids (the routing restrictions, on by
 * default) and the options, here the default distance covered in a day.
 */
@Composable
internal fun PlanningOptionsBox(
    restrictions: RoutingRestrictions,
    defaultKmPerDay: Double,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onChange: (RoutingRestrictions) -> Unit,
    onDefaultKmPerDayChange: (Double) -> Unit,
    onTextEditingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    LegendCard(expanded = expanded, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { onExpandedChange(!expanded) },
        ) {
            Text(stringResource(R.string.planning_title), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = LegendAccent,
                modifier = Modifier.size(18.dp),
            )
        }
        if (expanded) {
            Text(stringResource(R.string.plan_avoid), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            RestrictionRow(stringResource(R.string.plan_unsafe_countries), restrictions.avoidUnsafe) {
                onChange(restrictions.copy(avoidUnsafe = it))
            }
            RestrictionRow(stringResource(R.string.plan_restricted_countries), restrictions.avoidRestricted) {
                onChange(restrictions.copy(avoidRestricted = it))
            }
            RestrictionRow(stringResource(R.string.plan_closed_borders), restrictions.excludeClosedBorders) {
                onChange(restrictions.copy(excludeClosedBorders = it))
            }
            Text(
                stringResource(R.string.plan_options),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 2.dp),
            )
            KmPerDayField(
                km = defaultKmPerDay,
                onChange = onDefaultKmPerDayChange,
                onFocusChange = onTextEditingChange,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RestrictionRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.padding(start = 8.dp).height(28.dp))
    }
}
