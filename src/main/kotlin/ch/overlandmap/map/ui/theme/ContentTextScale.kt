package ch.overlandmap.map.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified

/**
 * Multiplier applied to the font size of *content* text — the text of displayed
 * objects (itineraries, steps, waypoints, sidebars, countries, comments,
 * check-ins): their names, descriptions, road conditions, highlights, … — set
 * from the user's text-size setting ([ch.overlandmap.map.data.FontSize]). Fixed
 * UI chrome (buttons, tab and field labels, app-bar titles) keeps the default
 * 1f and is not scaled.
 *
 * Provided once at the app root (see [OverlandTheme]). Read it through
 * [contentTextStyle], or apply [scaledBy] to a style directly (e.g. from a
 * non-composable helper).
 */
val LocalContentTextScale = staticCompositionLocalOf { 1f }

/** [this] with its font size and line height multiplied by [scale]. */
fun TextStyle.scaledBy(scale: Float): TextStyle {
    if (scale == 1f) return this
    return copy(
        fontSize = if (fontSize.isSpecified) fontSize * scale else fontSize,
        lineHeight = if (lineHeight.isSpecified) lineHeight * scale else lineHeight,
    )
}

/** [style] scaled by the current [LocalContentTextScale], for content text. */
@Composable
fun contentTextStyle(style: TextStyle): TextStyle = style.scaledBy(LocalContentTextScale.current)
