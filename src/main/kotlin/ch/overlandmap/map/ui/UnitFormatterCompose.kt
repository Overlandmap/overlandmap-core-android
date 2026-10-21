package ch.overlandmap.map.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.units.UnitFormatter
import java.util.Locale

/**
 * A [UnitFormatter] bound to the user's current unit preferences. Recomposes the
 * caller when any axis (distance, height, temperature, GPS format) changes, so every
 * screen formats through the single shared implementation.
 */
@Composable
fun rememberUnitFormatter(): UnitFormatter {
    val app = LocalContext.current.applicationContext as OverlandApp
    val format by app.userPreferences.unitFormat.collectAsState(
        initial = app.userPreferences.unitFormatNow(),
    )
    val locale = Locale.getDefault()
    return remember(format, locale) { UnitFormatter(format, locale) }
}
