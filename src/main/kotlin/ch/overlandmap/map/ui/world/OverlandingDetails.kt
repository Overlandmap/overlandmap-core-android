package ch.overlandmap.map.ui.world

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.geo.Coordinate
import ch.overlandmap.map.model.BorderOpenState
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.CountryCarnet
import ch.overlandmap.map.model.CountryInsurance
import ch.overlandmap.map.model.CountryVisa
import ch.overlandmap.map.model.TravelAdvisoryLinks
import ch.overlandmap.map.model.Zone
import ch.overlandmap.map.ui.markup.EntityKind
import ch.overlandmap.map.ui.markup.Markup
import ch.overlandmap.map.ui.markup.MarkupLink
import ch.overlandmap.map.ui.markup.MarkupText
import ch.overlandmap.map.ui.rememberUnitFormatter
import ch.overlandmap.map.ui.theme.contentTextStyle

/*
 * The detail panes of the Overlanding tab (iOS CountryDetailView, BorderDetailView,
 * BorderPostDetailView, ZoneDetailView): a header with the status pill, a tab row, and the tab.
 */

// Flutter's blue.shade50 and green.shade50, as iOS uses them.
private val VisaBackground = Color(0xFFE3F2FC)
private val MoneyBackground = Color(0xFFE8F5E8)
private val BoxText = Color(0xFF1B1B1B)

/** Shows an Overlanding object in the Overlanding tab; provided by the navigation. */
val LocalOpenOverlanding = androidx.compose.runtime.staticCompositionLocalOf<(OverlandingSelection) -> Unit> { {} }

/** Opens web links of the prose in the browser and Overlanding links in the shared map. */
@Composable
fun rememberOverlandLinkHandler(): (MarkupLink, String) -> Unit {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    return remember {
        { link, _ ->
            when (link) {
                is MarkupLink.Url -> openUrl(context, link.url)
                is MarkupLink.EntityRef -> app.overlandingLink.value = when (link.kind) {
                    EntityKind.COUNTRY -> OverlandingSelection.OfCountry(link.id)
                    EntityKind.BORDER -> OverlandingSelection.OfBorder(link.id)
                    EntityKind.BORDER_POST -> OverlandingSelection.OfBorderPost(link.id)
                    EntityKind.ZONE -> OverlandingSelection.OfZone(link.id)
                }
                else -> Unit // pack-bound links have no meaning here
            }
        }
    }
}

internal fun openUrl(context: android.content.Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
    }
}

@Composable
private fun Prose(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val onLink = rememberOverlandLinkHandler()
    var style = contentTextStyle(MaterialTheme.typography.bodyLarge)
    if (color != null) style = style.copy(color = color)
    MarkupText(text = text, modifier = modifier, style = style, onLinkClick = onLink)
}

@Composable
private fun DetailHeader(
    title: String,
    pill: String,
    pillColor: Color,
    leading: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (!leading.isNullOrEmpty()) {
            Text(leading, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.padding(end = 8.dp))
        }
        Text(
            title,
            style = contentTextStyle(MaterialTheme.typography.titleLarge),
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.padding(end = 8.dp))
        StatusPill(pill, pillColor)
    }
}

@Composable
private fun DetailTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    TabRow(selectedTabIndex = selected.coerceIn(0, titles.lastIndex)) {
        titles.forEachIndexed { index, title ->
            Tab(
                selected = index == selected,
                onClick = { onSelect(index) },
                text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@Composable
private fun TabBody(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

/** A field's prose with its discussion pencil in front. */
@Composable
private fun FieldProse(discussions: ObjectDiscussions, field: String, title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
        FieldDiscussionButton(discussions, field, title)
        Prose(text, Modifier.weight(1f))
    }
}

/** The rounded, tinted panel iOS uses for prose a correction can be filed against. */
@Composable
private fun InfoBox(
    title: String,
    background: Color,
    body: String,
    discussions: ObjectDiscussions,
    topic: String? = null,
    url: String? = null,
) {
    val context = LocalContext.current
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (topic != null) FieldDiscussionButton(discussions, topic, title)
            Text(title, fontWeight = FontWeight.Bold, color = BoxText)
            if (url != null) {
                Text(
                    stringResource(R.string.website_link),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { openUrl(context, url) },
                )
            }
        }
        Prose(body, color = BoxText)
    }
}

/** A "Label: value" line, with the label in bold. */
@Composable
private fun InfoRow(
    label: String?,
    value: String,
    discussions: ObjectDiscussions,
    topic: String? = null,
    title: String? = null,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (topic != null) FieldDiscussionButton(discussions, topic, title ?: label ?: value)
        val text = buildAnnotatedString {
            if (label != null) {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("$label: ") }
            }
            append(value)
        }
        Text(text, style = contentTextStyle(MaterialTheme.typography.bodyLarge), modifier = Modifier.weight(1f))
    }
}

// ── Country ─────────────────────────────────────────────────────────────────

@Composable
fun CountryDetail(
    country: Country,
    data: OverlandData,
    lang: String,
    advisoryTemplates: Map<String, String>,
    tab: Int,
    onTab: (Int) -> Unit,
    onOpenBorder: (CountryBorder) -> Unit,
) {
    val discussions = rememberObjectDiscussions(country.documentId, "country")
    val overlanding = country.overlandingStatus
    val titles = listOf(
        stringResource(R.string.tab_general), stringResource(R.string.tab_vehicle),
        stringResource(R.string.tab_borders), stringResource(R.string.tab_discussion),
    )
    Column(Modifier.fillMaxSize()) {
        DetailHeader(country.name(lang), overlanding.label(), overlanding.color(), flagEmoji(country.isoA2))
        DetailTabs(titles, tab, onTab)
        TabBody {
            when (tab) {
                0 -> CountryGeneral(country, data, lang, advisoryTemplates, discussions)
                1 -> CountryVehicle(country, lang, discussions)
                2 -> CountryBorders(country, data, lang, onOpenBorder)
                else -> DiscussionTab(discussions)
            }
        }
    }
}

@Composable
private fun CountryGeneral(
    country: Country,
    data: OverlandData,
    lang: String,
    templates: Map<String, String>,
    discussions: ObjectDiscussions,
) {
    val names = remember(data.countries, lang) {
        data.countries.filter { !it.adm0A3.isNullOrEmpty() }
            .associate { it.adm0A3!!.uppercase() to it.name(lang) }
    }
    val links = remember(templates, lang, country, names) {
        TravelAdvisoryLinks.make(templates, lang, country.name(lang)) { names[it.uppercase()] }
    }
    val comment = country.comment(lang)?.takeIf { it.isNotEmpty() }
    val visaComment = country.visaComment(lang)?.takeIf { it.isNotEmpty() }
    val money = country.money(lang)?.takeIf { it.isNotEmpty() }
    val visa = country.visaStatus
    val hasVisa = visaComment != null || country.visaUrl != null || visa != CountryVisa.UNKNOWN
    if (comment == null && !hasVisa && money == null && links.isEmpty()) {
        EmptyTabContent(discussions)
        return
    }
    val context = LocalContext.current
    comment?.let { FieldProse(discussions, "comment", stringResource(R.string.tab_general), it) }

    if (visaComment != null) {
        InfoBox(visa.label(), VisaBackground, visaComment, discussions, topic = "visa", url = country.visaUrl)
    } else if (hasVisa) {
        // No prose: the status on its own, a link when the country points at an e-visa site.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FieldDiscussionButton(discussions, "visa", visa.label())
            val url = country.visaUrl
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = if (url != null) Modifier.clickable { openUrl(context, url) } else Modifier,
            ) {
                Text(visa.label(), fontWeight = FontWeight.Bold)
                if (url != null) Text(stringResource(R.string.website_link), color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    money?.let {
        InfoBox(stringResource(R.string.money), MoneyBackground, it, discussions)
    }

    if (links.isNotEmpty()) {
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.official_website), style = MaterialTheme.typography.titleMedium)
            links.forEach { link ->
                Text(
                    link.sourceName,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable { openUrl(context, link.url) }.padding(vertical = 4.dp),
                )
            }
        }
    }
    HorizontalDivider()
    AddInputButton(discussions)
}

@Composable
private fun CountryVehicle(country: Country, lang: String, discussions: ObjectDiscussions) {
    country.driving?.let { driving ->
        InfoRow(
            null,
            stringResource(if (driving.equals("l", ignoreCase = true)) R.string.driving_side_left else R.string.driving_side_right),
            discussions,
        )
    }

    val carnet: CountryCarnet = country.carnetStatus
    val carnetComment = country.carnetComment(lang)?.takeIf { it.isNotEmpty() }
    if (carnetComment != null) {
        InfoBox(carnet.label(), VisaBackground, carnetComment, discussions, topic = "carnet")
    } else {
        InfoRow(null, carnet.label(), discussions, topic = "carnet", title = carnet.label())
    }

    country.tip(lang)?.takeIf { it.isNotEmpty() }?.let {
        InfoBox(stringResource(R.string.tip_permit), VisaBackground, it, discussions, topic = "vehicleTip")
    }

    val insurance: CountryInsurance = country.insuranceStatus
    val insuranceComment = country.insuranceComment(lang)?.takeIf { it.isNotEmpty() }
    if (insuranceComment != null) {
        InfoBox(
            insurance.label(), VisaBackground, insuranceComment, discussions,
            topic = "vehicleInsurance", url = country.insuranceUrl,
        )
    } else if (insurance != CountryInsurance.UNKNOWN) {
        InfoRow(null, insurance.label(), discussions, topic = "vehicleInsurance", title = insurance.label())
    }

    country.insuranceScheme?.let {
        InfoRow(stringResource(R.string.insurance_scheme), it.label(), discussions, topic = "insuranceScheme")
    }
    country.stayDuration(lang)?.takeIf { it.isNotEmpty() }?.let {
        InfoRow(stringResource(R.string.stay_duration), it, discussions, topic = "stayDuration")
    }
}

@Composable
private fun CountryBorders(
    country: Country,
    data: OverlandData,
    lang: String,
    onOpenBorder: (CountryBorder) -> Unit,
) {
    val related = data.bordersOf(country)
    if (related.isEmpty()) {
        Text(stringResource(R.string.no_land_borders))
        return
    }
    val code = country.adm0A3 ?: country.documentId
    val units = rememberUnitFormatter()
    related.forEach { border ->
        val neighbour = border.oppositeCountryCode(code)?.let { data.country(it) }
        val status = border.openState.forBorder()
        val summary = border.comment(lang)?.takeIf { it.isNotEmpty() }
            ?.let { Markup.plainText(it, units).trim() }?.takeIf { it.isNotEmpty() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().clickable { onOpenBorder(border) }.padding(vertical = 4.dp),
        ) {
            val flag = flagEmoji(neighbour?.isoA2)
            if (flag.isNotEmpty()) Text(flag, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f)) {
                Text(
                    neighbour?.name(lang)?.takeIf { it.isNotEmpty() } ?: stringResource(R.string.unknown),
                    fontWeight = FontWeight.Bold,
                )
                if (summary != null) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            StatusPill(status.label(), status.color())
        }
        HorizontalDivider()
    }
}

// ── Border ──────────────────────────────────────────────────────────────────

@Composable
fun BorderDetail(
    border: CountryBorder,
    data: OverlandData,
    lang: String,
    tab: Int,
    onTab: (Int) -> Unit,
    onOpenPost: (BorderPost) -> Unit,
    onZoomPost: (Coordinate) -> Unit,
) {
    val discussions = rememberObjectDiscussions(border.documentId, "border")
    val status = border.openState.forBorder()
    val title = data.borderTitle(border, lang, stringResource(R.string.border))
    val titles = listOf(
        stringResource(R.string.tab_general), stringResource(R.string.border_posts),
        stringResource(R.string.tab_discussion),
    )
    Column(Modifier.fillMaxSize()) {
        DetailHeader(title, status.label(), status.color())
        DetailTabs(titles, tab, onTab)
        TabBody {
            when (tab) {
                0 -> {
                    val comment = border.comment(lang)?.takeIf { it.isNotEmpty() }
                    if (comment != null) FieldProse(discussions, "comment", stringResource(R.string.tab_general), comment)
                    else EmptyTabContent(discussions)
                }
                1 -> BorderPostsTab(border, data, lang, discussions, onOpenPost, onZoomPost)
                else -> DiscussionTab(discussions)
            }
        }
    }
}

@Composable
private fun BorderPostsTab(
    border: CountryBorder,
    data: OverlandData,
    lang: String,
    discussions: ObjectDiscussions,
    onOpenPost: (BorderPost) -> Unit,
    onZoomPost: (Coordinate) -> Unit,
) {
    val posts = border.borderPostIds.mapNotNull { data.post(it) }
    val units = rememberUnitFormatter()
    if (posts.isEmpty()) {
        Text(stringResource(R.string.no_border_posts_information), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    posts.forEach { post ->
        val status = post.openState
        val comment = post.comment(lang)?.takeIf { it.isNotEmpty() }?.let { Markup.plainText(it, units).trim() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().clickable { onOpenPost(post) },
        ) {
            Column(Modifier.weight(1f)) {
                Text(post.name.ifEmpty { stringResource(R.string.no_name) }, fontWeight = FontWeight.Bold)
                if (!comment.isNullOrEmpty()) {
                    Text(
                        comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val where = post.coordinate()
            if (where != null) {
                IconButton(onClick = { onZoomPost(where) }) {
                    Icon(Icons.Filled.ZoomIn, contentDescription = stringResource(R.string.zoom))
                }
            }
            StatusPill(status.label(), status.color())
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
        HorizontalDivider()
    }
    SuggestedPosts(discussions, onZoomPost)
    AddBorderPostButton(border, data, discussions)
}

// ── Border post ─────────────────────────────────────────────────────────────

@Composable
fun BorderPostDetail(
    post: BorderPost,
    border: CountryBorder?,
    data: OverlandData,
    lang: String,
    tab: Int,
    onTab: (Int) -> Unit,
) {
    val discussions = rememberObjectDiscussions(post.documentId, "border_post")
    val status: BorderOpenState = post.openState
    val reports = crossingReportCount(discussions)
    val crossingTitle = stringResource(R.string.crossing_reports)
    val titles = listOf(
        stringResource(R.string.tab_general),
        if (reports > 0) "$crossingTitle ($reports)" else crossingTitle,
    )
    Column(Modifier.fillMaxSize()) {
        DetailHeader(post.name.ifEmpty { stringResource(R.string.no_name) }, status.label(), status.color())
        DetailTabs(titles, tab, onTab)
        TabBody {
            when (tab) {
                0 -> {
                    val comment = post.comment(lang)?.takeIf { it.isNotEmpty() }
                    if (comment != null) FieldProse(discussions, "comment", stringResource(R.string.tab_general), comment)
                    else EmptyTabContent(discussions)
                }
                else -> CrossingReportsTab(post, border, data, lang, discussions)
            }
        }
    }
}

// ── Zone ────────────────────────────────────────────────────────────────────

@Composable
fun ZoneDetail(zone: Zone, country: Country?, lang: String) {
    val discussions = rememberObjectDiscussions(zone.documentId, "zone")
    val type = zone.zoneType
    Column(Modifier.fillMaxSize()) {
        DetailHeader(zone.name, type.label(), type.color())
        TabBody {
            if (country != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val flag = flagEmoji(country.isoA2)
                    if (flag.isNotEmpty()) Text(flag, style = MaterialTheme.typography.titleLarge)
                    Text(country.name(lang))
                }
            }
            val comment = zone.comment(lang)?.takeIf { it.isNotEmpty() }
            if (comment != null) FieldProse(discussions, "comment", zone.name, comment)
            else EmptyTabContent(discussions)
        }
    }
}

/** A centred placeholder, for a selection whose object isn't in the database. */
@Composable
fun UnavailableSelection(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
