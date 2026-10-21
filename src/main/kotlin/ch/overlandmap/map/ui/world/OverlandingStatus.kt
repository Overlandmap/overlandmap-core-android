package ch.overlandmap.map.ui.world

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.R
import ch.overlandmap.map.model.BorderOpenState
import ch.overlandmap.map.model.CountryCarnet
import ch.overlandmap.map.model.CountryInsurance
import ch.overlandmap.map.model.CountryOverlanding
import ch.overlandmap.map.model.CountryVisa
import ch.overlandmap.map.model.InsuranceScheme
import ch.overlandmap.map.model.ZoneType

/*
 * Labels and colours of the Overlanding statuses (iOS CountryStatusDisplay): one source of
 * truth for the header pills, the tabs, the rows and the legend.
 */

fun hexColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

fun BorderOpenState.color(): Color = hexColor(colorHex)

/** A border has no "temporarily closed" state (that is a border post's); it reads as unknown. */
fun BorderOpenState.forBorder(): BorderOpenState =
    if (this == BorderOpenState.TEMPORARILY_CLOSED) BorderOpenState.UNKNOWN else this

fun ZoneType.color(): Color = when (this) {
    ZoneType.CLOSED -> hexColor("#D64545")
    ZoneType.GUIDE_ESCORT_NEEDED -> hexColor("#3478C7")
    ZoneType.PERMIT_NEEDED -> hexColor("#E7A43D")
    ZoneType.SPECIAL -> hexColor("#8E3B3B")
    ZoneType.UNKNOWN -> hexColor("#777777")
}

fun CountryOverlanding.color(): Color = Color(color)

@Composable
fun BorderOpenState.label(): String = stringResource(
    when (this) {
        BorderOpenState.CLOSED -> R.string.border_closed
        BorderOpenState.BILATERAL -> R.string.border_bilateral
        BorderOpenState.OPEN -> R.string.border_open
        BorderOpenState.RESTRICTIONS -> R.string.restricted
        BorderOpenState.TEMPORARILY_CLOSED -> R.string.border_temporarily_closed
        BorderOpenState.UNKNOWN -> R.string.border_unknown
    }
)

@Composable
fun ZoneType.label(): String = stringResource(
    when (this) {
        ZoneType.CLOSED -> R.string.zone_closed
        ZoneType.GUIDE_ESCORT_NEEDED -> R.string.zone_guide_escort
        ZoneType.PERMIT_NEEDED -> R.string.zone_permit
        ZoneType.SPECIAL -> R.string.zone_special
        ZoneType.UNKNOWN -> R.string.unknown
    }
)

@Composable
fun CountryOverlanding.label(): String = stringResource(
    when (this) {
        CountryOverlanding.FORBIDDEN -> R.string.overlanding_forbidden
        CountryOverlanding.DANGEROUS -> R.string.overlanding_unsafe
        CountryOverlanding.RESTRICTED -> R.string.restricted
        CountryOverlanding.OPEN -> R.string.overlanding_open
        CountryOverlanding.UNKNOWN -> R.string.overlanding_unknown
    }
)

@Composable
fun CountryCarnet.label(): String = stringResource(
    when (this) {
        CountryCarnet.ACCESS_FORBIDDEN -> R.string.access_forbidden
        CountryCarnet.NOT_REQUIRED -> R.string.carnet_not_required
        CountryCarnet.REQUIRED_IN_SOME_SITUATIONS -> R.string.carnet_required_in_some_situations
        CountryCarnet.MANDATORY -> R.string.carnet_mandatory
        CountryCarnet.UNKNOWN -> R.string.unknown
    }
)

@Composable
fun CountryVisa.label(): String = stringResource(
    when (this) {
        CountryVisa.NOT_REQUIRED -> R.string.visa_not_required
        CountryVisa.BORDER -> R.string.visa_at_border
        CountryVisa.E_VISA -> R.string.visa_e_visa_available
        CountryVisa.EMBASSY -> R.string.visa_embassy
        CountryVisa.RESTRICTED -> R.string.visa_restricted
        CountryVisa.UNKNOWN -> R.string.visa_unknown
    }
)

@Composable
fun CountryInsurance.label(): String = stringResource(
    when (this) {
        CountryInsurance.NOT_REQUIRED -> R.string.insurance_not_required
        CountryInsurance.AT_BORDER -> R.string.insurance_at_border
        CountryInsurance.ONLINE -> R.string.insurance_online
        CountryInsurance.OTHER -> R.string.insurance_other
        CountryInsurance.UNKNOWN -> R.string.unknown
    }
)

/** The scheme's own name: proper nouns, not translated. */
fun InsuranceScheme.label(): String = when (this) {
    InsuranceScheme.GREEN_CARD -> "Green Card"
    InsuranceScheme.BROWN_CARD -> "Brown Card"
    InsuranceScheme.YELLOW_CARD -> "Yellow Card"
    InsuranceScheme.SOAT -> "SOAT"
    InsuranceScheme.MERCOSUR -> "Mercosur"
}

/** The coloured capsule carrying a status label. */
@Composable
fun StatusPill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** The flag emoji of an ISO alpha-2 code (regional indicator symbols); empty when the code is unusable. */
fun flagEmoji(alpha2: String?): String {
    val code = alpha2?.trim()?.uppercase() ?: return ""
    if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
    return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
}
