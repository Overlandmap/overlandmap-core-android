package ch.overlandmap.map.ui.help

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Signpost
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.ui.graphics.vector.ImageVector

/** Maps a help YAML icon name (MDI/FontAwesome-style) to a Material icon. */
fun helpIcon(name: String): ImageVector = when (name) {
    "account_group" -> Icons.Filled.Groups
    "book_open_page_variant_outline" -> Icons.Filled.MenuBook
    "calculator" -> Icons.Filled.Calculate
    "chat_alert" -> Icons.Filled.Forum
    "cloud_download" -> Icons.Filled.CloudDownload
    "cloud_offline" -> Icons.Filled.CloudOff
    "earth" -> Icons.Filled.Public
    "file_document" -> Icons.Filled.Description
    "globe" -> Icons.Filled.Language
    "home" -> Icons.Filled.Home
    "map", "map_marked_alt" -> Icons.Filled.Map
    "map_marker" -> Icons.Filled.Place
    "map_signs" -> Icons.Filled.Signpost
    "passport_biometric" -> Icons.Filled.Badge
    "route" -> Icons.Filled.Route
    "settings" -> Icons.Filled.Settings
    "share" -> Icons.Filled.Share
    "shopping_cart" -> Icons.Filled.ShoppingCart
    "thumb_up" -> Icons.Filled.ThumbUp
    "translate" -> Icons.Filled.Translate
    "user_circle_o" -> Icons.Filled.AccountCircle
    "warning_outline" -> Icons.Filled.WarningAmber
    "weather_partly_cloudy" -> Icons.Filled.Cloud
    else -> Icons.Filled.Info
}
