package ch.overlandmap.map.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.AppConfig
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.isDebugBuild
import ch.overlandmap.map.data.AccountRules
import ch.overlandmap.map.data.FontSize
import ch.overlandmap.map.units.GpsFormat
import ch.overlandmap.map.ui.rememberUnitFormatter
import ch.overlandmap.map.data.UserPreferences
import ch.overlandmap.map.ui.markup.MarkupText
import ch.overlandmap.map.ui.overlandApp
import kotlinx.coroutines.launch
import coil.compose.AsyncImage
import java.text.DateFormat
import java.util.Date

/**
 * Settings tab root: the signed-in user on top (photo, name, sign in/out),
 * then a list of submenus opened as their own screens (language, units).
 * There is always a Firebase user — an anonymous one is created at startup —
 * but only a real account counts as "signed in" here.
 */
@Composable
fun SettingsScreen(
    onOpenSignIn: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenUnits: () -> Unit,
    onOpenTextSize: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenTerms: () -> Unit,
    onOpenDebug: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel { SettingsViewModel(overlandApp()) },
) {
    var gpsPanel by remember { mutableStateOf<Int?>(null) }
    if (gpsPanel != null) {
        BluetoothGPSScreen(onBack = { gpsPanel = null }, simulationOnly = gpsPanel == 1)
        androidx.activity.compose.BackHandler { gpsPanel = null }
        return
    }
    val user by viewModel.user.collectAsState()
    val profile by viewModel.profile.collectAsState()
    val entitlements by viewModel.entitlements.collectAsState()
    val emailVerified by viewModel.emailVerified.collectAsState()
    val isAdmin = entitlements?.hasAdmin == true
    var showSignOutDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // The tools stay in debuggable builds, and appear for admins in release builds (as on iOS).
    val showDebug = isDebugBuild(context) || isAdmin
    LaunchedEffect(Unit) { viewModel.refreshAccount() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            val photoUrl = profile?.photoUrl ?: user?.photoUrl?.toString()
            if (photoUrl != null) {
                AsyncImage(
                    model = photoUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(CircleShape),
                )
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Icon(
                        Icons.Filled.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                val current = user
                // The profile's name, else the Auth name, else the email's local part (as iOS).
                val title = AccountRules.displayTitle(profile, current?.displayName, current?.email, current == null)
                Text(
                    when (title) {
                        AccountRules.ANONYMOUS -> stringResource(R.string.anonymous_session)
                        AccountRules.USER -> stringResource(R.string.user_logged_in)
                        else -> title
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                val subtitle = profile?.email ?: current?.email
                if (current != null && subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (user == null) {
                Button(onClick = onOpenSignIn) { Text(stringResource(R.string.sign_in)) }
            } else {
                Column {
                    if (isAdmin) {
                        Text(
                            stringResource(R.string.admin).uppercase(),
                            color = Color.Red,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    if (!emailVerified && user?.email != null) {
                        Text(
                            stringResource(R.string.email_not_verified),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                    OutlinedButton(onClick = { showSignOutDialog = true }) {
                        Text(stringResource(R.string.sign_out))
                    }
                    // A failed sign-out is reported, not swallowed.
                    MessageText(viewModel.message.collectAsState().value)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        SubmenuRow(
            icon = Icons.Filled.Info,
            label = stringResource(R.string.settings_about),
            onClick = onOpenAbout,
        )
        HorizontalDivider()
        SubmenuRow(
            icon = Icons.Filled.Person,
            label = stringResource(R.string.profile),
            onClick = onOpenProfile,
        )
        HorizontalDivider()
        SubmenuRow(
            icon = Icons.Filled.Language,
            label = stringResource(R.string.settings_language),
            onClick = onOpenLanguage,
        )
        HorizontalDivider()
        SubmenuRow(
            icon = Icons.Filled.Straighten,
            label = stringResource(R.string.settings_units),
            onClick = onOpenUnits,
        )
        HorizontalDivider()
        SubmenuRow(
            icon = Icons.Filled.FormatSize,
            label = stringResource(R.string.settings_text_size),
            onClick = onOpenTextSize,
        )
        HorizontalDivider()
        SubmenuRow(Icons.Filled.Map, stringResource(R.string.bluetooth_gps)) { gpsPanel = 0 }
        HorizontalDivider()
        if (isAdmin) {
            SubmenuRow(Icons.Filled.Map, stringResource(R.string.bluetooth_gps_simulation)) { gpsPanel = 1 }
            HorizontalDivider()
        }
        SubmenuRow(
            icon = Icons.Filled.Download,
            label = stringResource(R.string.downloads),
            onClick = onOpenDownloads,
        )
        HorizontalDivider()
        SubmenuRow(
            icon = Icons.AutoMirrored.Filled.HelpOutline,
            label = stringResource(R.string.help),
            onClick = onOpenHelp,
        )
        HorizontalDivider()
        SubmenuRow(
            icon = Icons.Filled.PrivacyTip,
            label = stringResource(R.string.settings_privacy_policy),
            onClick = onOpenPrivacy,
        )
        HorizontalDivider()
        SubmenuRow(
            icon = Icons.Filled.Gavel,
            label = stringResource(R.string.settings_terms_of_service),
            onClick = onOpenTerms,
        )
        HorizontalDivider()
        SubmenuRow(
            icon = Icons.Filled.Star,
            label = stringResource(R.string.rate_us),
            onClick = { openStoreListing(context) },
        )
        HorizontalDivider()
        // Debug tools: debuggable builds, and admins.
        if (showDebug) {
            SubmenuRow(
                icon = Icons.Filled.BugReport,
                label = stringResource(R.string.debug),
                onClick = onOpenDebug,
            )
            HorizontalDivider()
        }
    }

    if (showSignOutDialog) {
        AlertDialog(
            onDismissRequest = { showSignOutDialog = false },
            title = { Text(stringResource(R.string.sign_out)) },
            text = { Text(stringResource(R.string.sign_out_deletes_packs)) },
            confirmButton = {
                TextButton(onClick = {
                    showSignOutDialog = false
                    viewModel.signOut()
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showSignOutDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** Opens the app's Play Store page (the Play app when installed, else the website). */
private fun openStoreListing(context: Context) {
    val id = context.packageName
    val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (_: android.content.ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$id"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
private fun SubmenuRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, modifier = Modifier.weight(1f).padding(horizontal = 16.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Scaffold shared by the settings sub-screens: back arrow, title, content. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsSubScreen(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            content()
        }
    }
}

/**
 * Language submenu, two lists: the UI language and the map-label language
 * (the same languages plus "native", each place in its local language).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel(overlandApp()) },
) {
    val mapLanguage by viewModel.mapLanguage.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_language)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(stringResource(R.string.ui_language))
            val currentLanguage = AppCompatDelegate.getApplicationLocales().toLanguageTags()
                .ifEmpty { java.util.Locale.getDefault().language }
            AppConfig.SUPPORTED_LANGUAGES.forEach { (code, label) ->
                LanguageRow(
                    flag = languageFlag(code),
                    label = languageLabel(code, label),
                    selected = code == currentLanguage,
                    onClick = { viewModel.setLanguage(code) },
                )
            }

            SectionHeader(stringResource(R.string.map_language))
            LanguageRow(
                flag = languageFlag(UserPreferences.MAP_LANGUAGE_NATIVE),
                label = stringResource(R.string.map_language_native),
                selected = mapLanguage == UserPreferences.MAP_LANGUAGE_NATIVE,
                onClick = { viewModel.setMapLanguage(UserPreferences.MAP_LANGUAGE_NATIVE) },
            )
            AppConfig.SUPPORTED_LANGUAGES.forEach { (code, label) ->
                LanguageRow(
                    flag = languageFlag(code),
                    label = label,
                    selected = code == mapLanguage,
                    onClick = { viewModel.setMapLanguage(code) },
                )
            }
        }
    }
}

/** Appends "(AI-translated)" in the local language for machine-translated locales. */
private fun languageLabel(code: String, base: String): String = when (code) {
    "en", "fr" -> base
    "de" -> "$base (KI-übersetzt)"
    "it" -> "$base (tradotto da IA)"
    "es" -> "$base (traducido por IA)"
    "pt" -> "$base (traduzido por IA)"
    "nl" -> "$base (AI-vertaald)"
    "ru" -> "$base (ИИ-перевод)"
    else -> base
}

@Composable
private fun LanguageRow(flag: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 16.dp),
    ) {
        Text(flag, modifier = Modifier.padding(end = 12.dp))
        Text(label, modifier = Modifier.weight(1f))
        if (selected) {
            Text("✓", color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Flag emoji of the language's main country; a globe for "native". */
private fun languageFlag(code: String): String = when (code) {
    "en" -> "🇬🇧"
    "fr" -> "🇫🇷"
    "de" -> "🇩🇪"
    "it" -> "🇮🇹"
    "es" -> "🇪🇸"
    "pt" -> "🇵🇹"
    "nl" -> "🇳🇱"
    "ru" -> "🇷🇺"
    else -> "🌐"
}

/** App logo, name, "Version <name>-<code>", and a mailto "Contact Us" button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        ) {
            Spacer(modifier = Modifier.height(32.dp))
            Image(
                painter = painterResource(R.drawable.app_logo),
                contentDescription = null,
                modifier = Modifier.size(96.dp),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(appLabel(context), style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                stringResource(R.string.about_version_template, appVersionName(context), appVersionCode(context)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = { openContactUsEmail(context) },
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            ) {
                Text(stringResource(R.string.contact_us))
            }
        }
    }
}

private fun appLabel(context: Context): String =
    context.applicationInfo.loadLabel(context.packageManager).toString()

private fun appVersionName(context: Context): String =
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

@Suppress("DEPRECATION")
private fun appVersionCode(context: Context): Long {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
}

private fun openContactUsEmail(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:contact@ride2ladakh.com")
    }
    context.startActivity(intent)
}

/** Preferred format for units: distance, height, GPS coordinates, temperature. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnitsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel(overlandApp()) },
) {
    val useMiles by viewModel.useMiles.collectAsState()
    val useFeet by viewModel.useFeet.collectAsState()
    val gpsFormat by viewModel.gpsFormat.collectAsState()
    val useFahrenheit by viewModel.useFahrenheit.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_units)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
        // ── Distance ────────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.units_distance))
        RadioRow(
            emoji = "\uD83C\uDF0D",
            label = stringResource(R.string.units_metric),
            selected = !useMiles,
            onClick = { viewModel.setUseMiles(false) },
        )
        RadioRow(
            emoji = "\uD83C\uDDFA\uD83C\uDDF8",
            label = stringResource(R.string.units_imperial),
            selected = useMiles,
            onClick = { viewModel.setUseMiles(true) },
        )

        // ── Height ──────────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.units_height))
        RadioRow(
            emoji = "\uD83C\uDF0D",
            label = stringResource(R.string.units_metric),
            selected = !useFeet,
            onClick = { viewModel.setUseFeet(false) },
        )
        RadioRow(
            emoji = "\uD83C\uDDFA\uD83C\uDDF8",
            label = stringResource(R.string.units_imperial),
            selected = useFeet,
            onClick = { viewModel.setUseFeet(true) },
        )

        // ── GPS coordinates ─────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.gps_format))
        RadioRow(
            label = stringResource(R.string.gps_dd),
            selected = gpsFormat == GpsFormat.DD,
            onClick = { viewModel.setGpsFormat(GpsFormat.DD) },
        )
        RadioRow(
            label = stringResource(R.string.gps_ddmm),
            selected = gpsFormat == GpsFormat.DDMM,
            onClick = { viewModel.setGpsFormat(GpsFormat.DDMM) },
        )
        RadioRow(
            label = stringResource(R.string.gps_ddmmss),
            selected = gpsFormat == GpsFormat.DDMMSS,
            onClick = { viewModel.setGpsFormat(GpsFormat.DDMMSS) },
        )

        // ── Temperature ─────────────────────────────────────────────────────
        SectionHeader(stringResource(R.string.units_temperature))
        RadioRow(
            emoji = "\uD83C\uDF0D",
            label = stringResource(R.string.units_celsius),
            selected = !useFahrenheit,
            onClick = { viewModel.setUseFahrenheit(false) },
        )
        RadioRow(
            emoji = "\uD83C\uDDFA\uD83C\uDDF8",
            label = stringResource(R.string.units_fahrenheit),
            selected = useFahrenheit,
            onClick = { viewModel.setUseFahrenheit(true) },
        )

        // ── Sample ──────────────────────────────────────────────────────────
        HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
        Column(modifier = Modifier.padding(start = 16.dp, top = 12.dp)) {
            Text(
                stringResource(R.string.units_sample),
                style = MaterialTheme.typography.titleSmall,
            )
            // Same sample values as the iOS screen, rendered by the shared formatter.
            // (Everest: latitude 27.9871, longitude 86.9259.)
            val units = rememberUnitFormatter()
            val distSample = "${units.distanceMeters(100.0)}, ${units.distanceKm(30.0)}"
            val eleSample = units.meters(8848.0)
            val gpsSample = units.coordinate(27.9871, 86.9259)
            val tempSample = units.temperature(20.0)
            Text(distSample, style = MaterialTheme.typography.bodyMedium)
            Text(eleSample, style = MaterialTheme.typography.bodyMedium)
            Text(gpsSample, style = MaterialTheme.typography.bodyMedium)
            Text(tempSample, style = MaterialTheme.typography.bodyMedium)
        }
        }
    }
}

/**
 * Text-size submenu: pick the size of displayed-object content (itineraries,
 * steps, waypoints, articles, countries, comments, check-ins — names,
 * descriptions, road conditions, highlights, …). Fixed UI such as buttons and
 * labels keeps its size. A live sample below the choices shows the effect,
 * including the proportionally larger titles in formatted text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextSizeScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel(overlandApp()) },
) {
    val fontSize by viewModel.fontSize.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_text_size)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            RadioRow(
                label = stringResource(R.string.text_size_small),
                selected = fontSize == FontSize.SMALL,
                onClick = { viewModel.setFontSize(FontSize.SMALL) },
            )
            RadioRow(
                label = stringResource(R.string.text_size_medium),
                selected = fontSize == FontSize.MEDIUM,
                onClick = { viewModel.setFontSize(FontSize.MEDIUM) },
            )
            RadioRow(
                label = stringResource(R.string.text_size_large),
                selected = fontSize == FontSize.LARGE,
                onClick = { viewModel.setFontSize(FontSize.LARGE) },
            )
            RadioRow(
                label = stringResource(R.string.text_size_very_large),
                selected = fontSize == FontSize.VERY_LARGE,
                onClick = { viewModel.setFontSize(FontSize.VERY_LARGE) },
            )

            // Live sample: scales with the current selection (the choice is saved
            // immediately, so the whole app — and this preview — updates at once).
            HorizontalDivider(modifier = Modifier.padding(top = 16.dp))
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.units_sample),
                    style = MaterialTheme.typography.titleSmall,
                )
                MarkupText(
                    text = stringResource(R.string.text_size_sample),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * Debug tools submenu (only reachable from a debuggable build). Currently a
 * single toggle for the itinerary map's zoom-level overlay; add further debug
 * switches here.
 */
@Composable
fun DebugScreen(
    onBack: () -> Unit,
    onOpenLatestCheckIns: () -> Unit = {},
) {
    val app = LocalContext.current.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    val showZoom by app.userPreferences.debugShowZoom.collectAsState(
        initial = app.userPreferences.debugShowZoomNow(),
    )
    val showZoomButtons by app.userPreferences.debugShowZoomButtons.collectAsState(initial = false)
    val showPhotoSource by app.userPreferences.debugShowPhotoSource.collectAsState(
        initial = app.userPreferences.debugShowPhotoSourceNow(),
    )
    SettingsSubScreen(title = stringResource(R.string.debug), onBack = onBack) {
        SwitchRow(
            label = stringResource(R.string.debug_show_zoom),
            checked = showZoom,
            onChange = { scope.launch { app.userPreferences.setDebugShowZoom(it) } },
        )
        HorizontalDivider()
        SwitchRow(
            label = stringResource(R.string.debug_show_zoom_buttons),
            checked = showZoomButtons,
            onChange = { scope.launch { app.userPreferences.setDebugShowZoomButtons(it) } },
        )
        HorizontalDivider()
        SwitchRow(
            label = stringResource(R.string.debug_show_photo_source),
            checked = showPhotoSource,
            onChange = { scope.launch { app.userPreferences.setDebugShowPhotoSource(it) } },
        )
        HorizontalDivider()
        SubmenuRow(
            icon = Icons.Filled.BugReport,
            label = stringResource(R.string.debug_latest_check_ins),
            onClick = onOpenLatestCheckIns,
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun RadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    emoji: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        // Remove the default 48dp min touch target to pack rows tightly.
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.size(32.dp).padding(6.dp),
        )
        if (emoji != null) {
            Text(emoji, modifier = Modifier.padding(end = 8.dp))
        }
        Text(label)
    }
}
