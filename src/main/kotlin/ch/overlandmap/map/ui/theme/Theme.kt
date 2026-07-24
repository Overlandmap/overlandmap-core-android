package ch.overlandmap.map.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import ch.overlandmap.map.OverlandApp

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6B4F),
    secondary = Color(0xFF4E6354),
    tertiary = Color(0xFF3B6470),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF95D5B0),
    secondary = Color(0xFFB5CCBA),
    tertiary = Color(0xFFA3CDDB),
)

@Composable
fun OverlandTheme(content: @Composable () -> Unit) {
    // The user's text-size setting scales content text app-wide via
    // LocalContentTextScale; seed it synchronously so the first frame is right.
    val app = LocalContext.current.applicationContext as OverlandApp
    val fontSize by app.userPreferences.fontSize
        .collectAsState(initial = app.userPreferences.fontSizeNow())
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
    ) {
        CompositionLocalProvider(
            LocalContentTextScale provides fontSize.scale,
            content = content,
        )
    }
}
