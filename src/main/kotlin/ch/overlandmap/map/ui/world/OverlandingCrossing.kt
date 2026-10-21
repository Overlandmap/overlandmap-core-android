package ch.overlandmap.map.ui.world

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.model.BorderOpenState
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.CrossingVehicle
import ch.overlandmap.map.model.Discussion
import ch.overlandmap.map.social.CoordinateParser
import ch.overlandmap.map.social.DiscussionRules
import ch.overlandmap.map.ui.currentLanguage
import ch.overlandmap.map.ui.social.DiscussionVoteButtons
import ch.overlandmap.map.ui.social.LocationInput
import ch.overlandmap.map.ui.social.TranslatedContent
import ch.overlandmap.map.ui.social.rememberAuthGate
import kotlinx.coroutines.launch
import java.util.Date
import java.util.TimeZone

private val ReportBlue = Color(0xFF1976D2)
private val ReportOrange = Color(0xFFF57C00)
private val SuggestionPurple = Color(0xFF7B1FA2)

fun crossingReportCount(discussions: ObjectDiscussions): Int =
    DiscussionRules.crossingReports(discussions.discussions).size

// ── Vehicle labels ──────────────────────────────────────────────────────────

@Composable
fun CrossingVehicle.title(): String = stringResource(
    when (this) {
        CrossingVehicle.ON_FOOT -> R.string.vehicle_on_foot
        CrossingVehicle.BICYCLE -> R.string.vehicle_bicycle
        CrossingVehicle.CAR -> R.string.vehicle_car
        CrossingVehicle.MOTO -> R.string.motorcycle
        CrossingVehicle.CAMPER -> R.string.vehicle_camper
        CrossingVehicle.TRUCK -> R.string.vehicle_truck
        CrossingVehicle.OTHER -> R.string.other
    }
)

/** The short chip text of a vehicle, hand-translated as on iOS (`CrossingVehicle.shortTitle`). */
fun CrossingVehicle.shortTitle(language: String): String = when (this) {
    CrossingVehicle.ON_FOOT -> when (language) {
        "fr" -> "À pied"; "de" -> "Zu Fuß"; "es" -> "A pie"; "it" -> "A piedi"
        "pt" -> "A pé"; "nl" -> "Te voet"; "ru" -> "Пешком"; else -> "On foot"
    }
    CrossingVehicle.BICYCLE -> when (language) {
        "fr" -> "Vélo"; "de" -> "Rad"; "es", "it" -> "Bici"; "pt" -> "Bicicleta"
        "nl" -> "Fiets"; "ru" -> "Вело"; else -> "Bike"
    }
    CrossingVehicle.CAR -> when (language) {
        "fr" -> "Voiture"; "de", "es", "it", "nl" -> "Auto"; "pt" -> "Carro"; "ru" -> "Авто"; else -> "Car"
    }
    CrossingVehicle.MOTO -> when (language) {
        "de" -> "Motorrad"; "nl" -> "Motor"; "ru" -> "Мото"; else -> "Moto"
    }
    CrossingVehicle.CAMPER -> when (language) {
        "fr" -> "Camping-car"; "es" -> "Cámper"; "pt" -> "Trailer"; "ru" -> "Кемпер"; else -> "Camper"
    }
    CrossingVehicle.TRUCK -> when (language) {
        "fr" -> "Camion"; "de" -> "Lkw"; "es" -> "Camión"; "it" -> "Camion"
        "pt" -> "Caminhão"; "nl" -> "Vrachtwagen"; "ru" -> "Грузовик"; else -> "Truck"
    }
    CrossingVehicle.OTHER -> ""
}

// ── Crossing reports tab ────────────────────────────────────────────────────

@Composable
fun CrossingReportsTab(
    post: BorderPost,
    border: CountryBorder?,
    data: OverlandData,
    lang: String,
    discussions: ObjectDiscussions,
) {
    val gate = rememberAuthGate()
    var showSheet by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<Discussion?>(null) }
    val reports = DiscussionRules.crossingReports(discussions.discussions)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { gate.require(R.string.sign_in_to_discuss) { showSheet = true } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  " + stringResource(R.string.crossing_report), fontWeight = FontWeight.Bold)
        }
        when {
            discussions.loading && reports.isEmpty() ->
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            reports.isEmpty() ->
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.crossing_reports), style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.no_testimonial),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            else -> reports.forEach { report ->
                CrossingReportTile(report, data, lang, discussions, onDelete = { toDelete = report })
            }
        }
    }

    if (showSheet) {
        CrossingReportSheet(post, border, data, lang, discussions) { showSheet = false }
    }
    toDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_testimonial_confirmation)) },
            confirmButton = {
                TextButton(onClick = { toDelete = null; discussions.delete(target) }) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
private fun CrossingReportTile(
    report: Discussion,
    data: OverlandData,
    lang: String,
    discussions: ObjectDiscussions,
    onDelete: () -> Unit,
) {
    val gate = rememberAuthGate()
    var expanded by remember { mutableStateOf(false) }
    val votes by remember(report.documentId) { discussions.votes(report.documentId) }.collectAsState(initial = emptyList())
    val mine = votes.firstOrNull { it.userId == discussions.uid }
    val name = report.userName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.anonymous)
    val date = report.crossingDate ?: report.createdAt ?: System.currentTimeMillis()

    OutlinedCard(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.trim().firstOrNull()?.uppercase() ?: "?",
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(name, fontWeight = FontWeight.Bold)
                    Text(DiscussionRules.formatCrossingDate(date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                report.crossingDirection?.takeIf { it.isNotEmpty() }?.let { Chip(it, ReportBlue) }
                if (discussions.isOwner(report)) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.delete), modifier = Modifier.size(18.dp))
                    }
                }
            }
            val content = report.displayContent(lang).orEmpty()
            if (content.isNotEmpty()) {
                if (expanded) TranslatedContent(original = report.content, translation = report.translation(lang))
                else Column {
                    Text(content, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("(…)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    report.crossingVehicle?.takeIf { it != CrossingVehicle.OTHER }?.let { Chip(it.shortTitle(lang), ReportOrange) }
                    report.crossingPassport?.takeIf { it.isNotEmpty() }?.let { code ->
                        val country = data.country(code)?.name(lang) ?: code
                        DetailLine("${stringResource(R.string.passport)}: $country")
                    }
                    report.crossingTimeString?.takeIf { it.isNotEmpty() }?.let {
                        DetailLine("${stringResource(R.string.time_spent_at_border)}: $it")
                    }
                    report.crossingAmountString?.takeIf { it.isNotEmpty() }?.let {
                        DetailLine("${stringResource(R.string.amount_paid)}: $it")
                    }
                }
            }
            DiscussionVoteButtons(
                upCount = votes.count { it.isUpVote },
                downCount = votes.count { !it.isUpVote },
                userVote = mine?.isUpVote,
                onVote = { up -> gate.require(R.string.sign_in_to_discuss) { discussions.vote(report, up, allowAnonymous = false) } },
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DetailLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Chip(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

// ── Crossing report form ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CrossingReportSheet(
    post: BorderPost,
    border: CountryBorder?,
    data: OverlandData,
    lang: String,
    discussions: ObjectDiscussions,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as OverlandApp
    val options = remember(post, border, data, lang) {
        DiscussionRules.directionOptions(
            post.countries, border?.country1, border?.country2,
        ) { code -> data.country(code)?.name(lang) }
    }
    var direction by remember { mutableStateOf(options.firstOrNull()?.code) }
    var passportQuery by remember { mutableStateOf("") }
    var passportCode by remember { mutableStateOf<String?>(null) }
    var vehicle by remember { mutableStateOf<CrossingVehicle?>(null) }
    var timeSpent by remember { mutableStateOf("") }
    var amountPaid by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    var showDatePicker by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempted by remember { mutableStateOf(false) }

    val passportMatches = remember(passportQuery, data, lang) {
        val q = passportQuery.trim().lowercase()
        if (q.isEmpty()) emptyList()
        else data.countries.filter { it.name(lang).lowercase().contains(q) || (it.adm0A3 ?: "").lowercase().contains(q) }.take(5)
    }
    fun onPassportTyped(text: String) {
        passportQuery = text
        val t = text.trim()
        passportCode = if (t.isEmpty()) null else data.countries.firstOrNull {
            it.name(lang).equals(t, ignoreCase = true) || (it.adm0A3 ?: "").equals(t, ignoreCase = true)
        }?.documentId
    }
    val noPassportYet = attempted && passportCode == null
    val missingDirection = attempted && options.isNotEmpty() && direction == null
    val missingVehicle = attempted && vehicle == null
    val missingContent = attempted && content.isBlank()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.testimonial), style = MaterialTheme.typography.titleLarge)
            error?.let {
                Text(
                    it, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(8.dp),
                )
            }

            FieldLabel(stringResource(R.string.crossing_direction), missingDirection)
            if (options.isEmpty()) {
                Text(stringResource(R.string.no_border_information), style = MaterialTheme.typography.bodySmall)
            } else {
                options.forEach { option ->
                    val selected = direction == option.code
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { direction = option.code }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(option.label, modifier = Modifier.weight(1f))
                        Icon(
                            if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

            FieldLabel(stringResource(R.string.passport), noPassportYet)
            OutlinedTextField(
                value = passportQuery,
                onValueChange = ::onPassportTyped,
                placeholder = { Text(stringResource(R.string.start_typing_country)) },
                singleLine = true,
                isError = noPassportYet,
                trailingIcon = { if (passportCode != null) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (passportCode == null && passportMatches.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    passportMatches.forEach { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                passportCode = c.documentId
                                passportQuery = c.name(lang)
                            }.padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(c.name(lang), modifier = Modifier.weight(1f))
                            Text(c.adm0A3.orEmpty(), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider()
                    }
                }
            }

            FieldLabel(stringResource(R.string.vehicle_label), missingVehicle)
            CrossingVehicle.inputCases.chunked(3).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    rowItems.forEach { v ->
                        FilterChip(
                            selected = vehicle == v,
                            onClick = { vehicle = v },
                            label = { Text(v.title(), maxLines = 1) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            FieldLabel(stringResource(R.string.time_spent_at_border), false)
            OutlinedTextField(
                value = timeSpent, onValueChange = { timeSpent = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.crossing_time_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            FieldLabel(stringResource(R.string.amount_paid), false)
            OutlinedTextField(
                value = amountPaid, onValueChange = { amountPaid = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.crossing_amount_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            FieldLabel(stringResource(R.string.crossing_date), false)
            OutlinedTextField(
                value = dateText, onValueChange = { dateText = it }, singleLine = true,
                placeholder = { Text(stringResource(R.string.crossing_date_hint)) },
                isError = dateText.isNotBlank() && DiscussionRules.parseCrossingDate(dateText) == null,
                trailingIcon = {
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Filled.CalendarMonth, contentDescription = stringResource(R.string.crossing_date))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            FieldLabel(stringResource(R.string.description), missingContent)
            OutlinedTextField(
                value = content, onValueChange = { content = it },
                placeholder = { Text(stringResource(R.string.no_testimonial)) },
                isError = missingContent,
                minLines = 4, maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss, enabled = !submitting) { Text(stringResource(R.string.cancel)) }
                val required = stringResource(R.string.please_fill_all_fields)
                Button(
                    enabled = !submitting,
                    onClick = {
                        attempted = true
                        val trimmed = content.trim()
                        val dirOk = options.isEmpty() || direction != null
                        if (!dirOk || passportCode == null || vehicle == null || trimmed.isEmpty()) {
                            error = required
                            return@Button
                        }
                        error = null
                        submitting = true
                        scope.launch {
                            try {
                                app.discussionRepository.createCrossingReport(
                                    borderPostId = post.documentId,
                                    direction = direction.orEmpty(),
                                    passportCountryId = passportCode.orEmpty(),
                                    vehicle = vehicle!!,
                                    timeSpent = timeSpent, amountPaid = amountPaid,
                                    crossingDateMs = DiscussionRules.parseCrossingDate(dateText),
                                    content = trimmed, lang = lang,
                                )
                                onDismiss()
                            } catch (e: Exception) {
                                error = e.localizedMessage
                                submitting = false
                            }
                        }
                    },
                ) { Text(stringResource(R.string.send), fontWeight = FontWeight.Bold) }
            }
        }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { utcMidnight ->
                        // The picker answers midnight UTC of the chosen day; show that day, not the local one.
                        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        dateText = fmt.format(Date(utcMidnight))
                    }
                    showDatePicker = false
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun FieldLabel(text: String, invalid: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
    )
}

// ── Suggested border posts ──────────────────────────────────────────────────

/** The border's suggested posts, shown under its real ones with votes and replies. */
@Composable
fun SuggestedPosts(discussions: ObjectDiscussions, onZoom: (Coordinate) -> Unit) {
    val suggestions = DiscussionRules.suggestions(discussions.discussions)
    suggestions.forEach { s ->
        val status = s.extra["is_open"]?.let { (it as? Number)?.toInt() }?.let { BorderOpenState.fromRaw(it) }
        val coordinate = s.lat?.let { la -> s.lon?.let { lo -> Coordinate(la, lo) } }
        FieldDiscussionCard(
            discussion = s,
            followups = DiscussionRules.followups(s, discussions.discussions),
            discussions = discussions,
            badge = stringResource(R.string.suggested),
            badgeColor = SuggestionPurple,
            headline = s.content?.takeIf { it.isNotBlank() } ?: stringResource(R.string.no_name),
            showContent = false,
            extraHeader = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (coordinate != null) {
                        IconButton(onClick = { onZoom(coordinate) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(R.string.zoom))
                        }
                    }
                    status?.let { StatusPill(it.label(), it.color()) }
                }
            },
        )
    }
}

@Composable
fun AddBorderPostButton(border: CountryBorder, data: OverlandData, discussions: ObjectDiscussions) {
    var show by remember { mutableStateOf(false) }
    HorizontalDivider()
    TextButton(onClick = { show = true }) {
        Icon(Icons.Filled.AddCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
        Text("  " + stringResource(R.string.add_new_border_post), fontWeight = FontWeight.SemiBold)
    }
    if (show) SuggestBorderPostSheet(border, data, discussions) { show = false }
}

/** The status raw values a suggestion can carry: a border post's (0..4) plus unknown (5). */
private val SuggestionStatuses = listOf(
    0 to BorderOpenState.CLOSED, 1 to BorderOpenState.BILATERAL, 2 to BorderOpenState.OPEN,
    3 to BorderOpenState.RESTRICTIONS, 4 to BorderOpenState.TEMPORARILY_CLOSED, 5 to BorderOpenState.UNKNOWN,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuggestBorderPostSheet(
    border: CountryBorder,
    data: OverlandData,
    discussions: ObjectDiscussions,
    onDismiss: () -> Unit,
) {
    val lang = currentLanguage()
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<Pair<Int, BorderOpenState>?>(null) }
    var statusMenu by remember { mutableStateOf(false) }
    var locationText by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmOutside by remember { mutableStateOf<Coordinate?>(null) }

    val location = remember(locationText) { CoordinateParser.parse(locationText) }
    val fillAll = stringResource(R.string.please_fill_all_fields)
    val tooCloseFormat = stringResource(R.string.border_post_too_close_message, "%s")
    val fallbackName = stringResource(R.string.border_crossing)

    fun save(at: Coordinate, isOpen: Int) {
        submitting = true
        error = null
        scope.launch {
            try {
                discussions.create(
                    topic = "borderCrossing", reason = "suggestion", content = name.trim(), lang = lang,
                    location = at, isOpen = isOpen,
                )
                onDismiss()
            } catch (e: Exception) {
                error = e.localizedMessage
                submitting = false
            }
        }
    }

    fun validateAndSubmit() {
        attempted = true
        val at = location
        val chosen = status
        if (name.isBlank() || at == null || chosen == null) {
            error = fillAll
            return
        }
        DiscussionRules.tooClosePost(at, data.posts)?.let { close ->
            error = tooCloseFormat.replace("%s", close.name.ifEmpty { fallbackName })
            return
        }
        val bounds = listOfNotNull(
            data.country(border.country1)?.boundingBox, data.country(border.country2)?.boundingBox, border.boundingBox,
        )
        if (!DiscussionRules.isWithinBounds(at, bounds)) {
            confirmOutside = at
            return
        }
        save(at, chosen.first)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.add_new_border_post), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name, onValueChange = { name = it }, singleLine = true,
                label = { Text(stringResource(R.string.name)) },
                placeholder = { Text(stringResource(R.string.suggest_post_name_hint)) },
                isError = attempted && name.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            ExposedDropdownMenuBox(expanded = statusMenu, onExpandedChange = { statusMenu = it }) {
                OutlinedTextField(
                    value = status?.second?.let { it.label() } ?: stringResource(R.string.please_select_status),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.border_status)) },
                    isError = attempted && status == null,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(statusMenu) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                )
                ExposedDropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                    SuggestionStatuses.forEach { option ->
                        DropdownMenuItem(
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(option.second.color())) },
                            text = { Text(option.second.label()) },
                            onClick = { status = option; statusMenu = false },
                        )
                    }
                }
            }
            LocationInput(
                value = locationText,
                onValueChange = { locationText = it; error = null },
                error = if (attempted && location == null) fillAll else null,
                onProblem = { error = it },
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(
                    enabled = !submitting && name.isNotBlank() && location != null && status != null,
                    onClick = ::validateAndSubmit,
                ) { Text(stringResource(R.string.save)) }
            }
        }
    }

    confirmOutside?.let { at ->
        AlertDialog(
            onDismissRequest = { confirmOutside = null },
            title = { Text(stringResource(R.string.location_outside_country_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmOutside = null
                    status?.let { save(at, it.first) }
                }) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { confirmOutside = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
