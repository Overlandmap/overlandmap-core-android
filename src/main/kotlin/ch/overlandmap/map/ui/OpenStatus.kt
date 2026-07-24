package ch.overlandmap.map.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import ch.overlandmap.map.R
import ch.overlandmap.map.model.OpenKind

/** The label shown for an [OpenKind]; empty for [OpenKind.OTHER] (no label). */
@Composable
fun openKindLabel(kind: OpenKind): String = when (kind) {
    OpenKind.OPEN -> stringResource(R.string.open_kind_open)
    OpenKind.CLOSED -> stringResource(R.string.open_kind_closed)
    OpenKind.TEMP_CLOSED -> stringResource(R.string.open_kind_temp_closed)
    OpenKind.PERMIT -> stringResource(R.string.open_kind_permit)
    OpenKind.RESTRICTED -> stringResource(R.string.open_kind_restricted)
    OpenKind.TOLL -> stringResource(R.string.open_kind_toll)
    OpenKind.OTHER -> ""
}

/**
 * The access-status line for a step or waypoint: the [kind] label in bold,
 * followed by ": <details>" in normal weight when [details] is non-blank
 * (e.g. "Permit needed: GBAO permit needed"). Returns null when there is
 * nothing to show — no kind, or [OpenKind.OTHER] with no details.
 */
@Composable
fun openStatusText(kind: OpenKind?, details: String?): AnnotatedString? {
    if (kind == null) return null
    val label = openKindLabel(kind)
    val detail = details?.trim().orEmpty()
    if (label.isEmpty() && detail.isEmpty()) return null
    return buildAnnotatedString {
        if (label.isNotEmpty()) {
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label) }
        }
        if (detail.isNotEmpty()) {
            if (label.isNotEmpty()) append(": ")
            append(detail)
        }
    }
}
