package ch.overlandmap.map.ui.settings

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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.overlandmap.map.AppConfig
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.R
import ch.overlandmap.map.isDebugBuild
import ch.overlandmap.map.data.UserPreferences
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
    onOpenProfile: () -> Unit,
    onOpenLanguage: () -> Unit,
    onOpenUnits: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenDebug: () -> Unit = {},
    viewModel: SettingsViewModel = viewModel { SettingsViewModel(overlandApp()) },
) {
    val user by viewModel.user.collectAsState()
    var showSignOutDialog by remember { mutableStateOf(false) }
    val debugBuild = isDebugBuild(LocalContext.current)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            val photoUrl = user?.photoUrl
            if (photoUrl != null) {
                AsyncImage(
                    model = photoUrl.toString(),
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
                if (current == null) {
                    Text(
                        stringResource(R.string.not_signed_in),
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    Text(
                        current.displayName?.takeIf { it.isNotBlank() } ?: current.email ?: "",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!current.displayName.isNullOrBlank() && current.email != null) {
                        Text(
                            current.email ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Box(modifier = Modifier.padding(horizontal = 16.dp)) {
            if (user == null) {
                Button(onClick = onOpenSignIn) { Text(stringResource(R.string.sign_in)) }
            } else {
                OutlinedButton(onClick = { showSignOutDialog = true }) {
                    Text(stringResource(R.string.sign_out))
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
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
            icon = Icons.Filled.Download,
            label = stringResource(R.string.downloads),
            onClick = onOpenDownloads,
        )
        HorizontalDivider()
        // Debug tools — only in debuggable builds; absent from release.
        if (debugBuild) {
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
private fun SettingsSubScreen(
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
 * Standard sign-in / sign-up screen: email + password (with account
 * creation) or Google Sign-In. Pops back as soon as a real account is
 * signed in.
 */
@Composable
fun SignInScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel(overlandApp()) },
) {
    val user by viewModel.user.collectAsState()
    val error by viewModel.error.collectAsState()
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(user) {
        if (user != null) onBack()
    }

    SettingsSubScreen(title = stringResource(R.string.sign_in), onBack = onBack) {
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.email)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        val fieldsFilled = email.isNotBlank() && password.isNotBlank()
        Button(
            onClick = { viewModel.signInWithEmail(email, password, createAccount = false) },
            enabled = fieldsFilled,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text(stringResource(R.string.sign_in))
        }
        TextButton(
            onClick = { viewModel.signInWithEmail(email, password, createAccount = true) },
            enabled = fieldsFilled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.create_account))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.or_divider),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            HorizontalDivider(modifier = Modifier.weight(1f))
        }

        OutlinedButton(
            onClick = { viewModel.signInWithGoogle(context) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.sign_in_google))
        }

        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

/**
 * Language submenu, two lists: the UI language and the map-label language
 * (the same languages plus "native", each place in its local language).
 */
@Composable
fun LanguageScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel(overlandApp()) },
) {
    val mapLanguage by viewModel.mapLanguage.collectAsState()

    SettingsSubScreen(title = stringResource(R.string.settings_language), onBack = onBack) {
        Text(
            stringResource(R.string.ui_language),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        val currentLanguage = AppCompatDelegate.getApplicationLocales().toLanguageTags()
            .ifEmpty { java.util.Locale.getDefault().language }
        AppConfig.SUPPORTED_LANGUAGES.forEach { (code, label) ->
            LanguageRow(
                flag = languageFlag(code),
                label = label,
                selected = code == currentLanguage,
                onClick = { viewModel.setLanguage(code) },
            )
        }

        Text(
            stringResource(R.string.map_language),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
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

@Composable
private fun LanguageRow(flag: String, label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Text(flag, modifier = Modifier.padding(end = 16.dp))
        Text(label, modifier = Modifier.weight(1f))
        if (selected) {
            Text("✓", color = MaterialTheme.colorScheme.primary)
        }
    }
    HorizontalDivider()
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

/**
 * Profile submenu: the Firestore user document (live) with display name and
 * email, and the list of validated purchases from `users/{uid}/purchases` —
 * track packs by ID and the pro entitlement with its validity end.
 */
@Composable
fun ProfileScreen(
    onBack: () -> Unit,
    onOpenSignIn: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel(overlandApp()) },
) {
    val profile by viewModel.profile.collectAsState()
    val purchases by viewModel.purchases.collectAsState()

    SettingsSubScreen(title = stringResource(R.string.profile), onBack = onBack) {
        val current = profile
        if (current == null) {
            Text(stringResource(R.string.not_signed_in))
            Button(onClick = onOpenSignIn, modifier = Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.sign_in))
            }
            return@SettingsSubScreen
        }

        current.displayName?.let {
            Text(it, style = MaterialTheme.typography.titleMedium)
        }
        current.email?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Text(
            stringResource(R.string.purchases),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
        )
        if (purchases.isEmpty()) {
            Text(
                stringResource(R.string.no_purchases),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val dateFormat = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
        purchases.forEach { purchase ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            ) {
                Icon(
                    if (purchase.isPro) Icons.Filled.WorkspacePremium else Icons.Filled.Map,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(
                        if (purchase.isPro) stringResource(R.string.pro_entitlement)
                        else purchase.documentId,
                    )
                    val detail = when {
                        purchase.isPro && purchase.validityEnd != null ->
                            if (purchase.isActive) {
                                stringResource(
                                    R.string.valid_until,
                                    dateFormat.format(Date(purchase.validityEnd)),
                                )
                            } else {
                                stringResource(R.string.expired)
                            }
                        else -> purchase.purchasedAt?.let { dateFormat.format(Date(it)) }
                    }
                    detail?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (purchase.isPro && !purchase.isActive) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
                if (purchase.isActive) {
                    Text("✓", color = MaterialTheme.colorScheme.primary)
                }
            }
            HorizontalDivider()
        }
    }
}

/** Units submenu: miles and feet switches. */
@Composable
fun UnitsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel { SettingsViewModel(overlandApp()) },
) {
    val useMiles by viewModel.useMiles.collectAsState()
    val useFeet by viewModel.useFeet.collectAsState()

    SettingsSubScreen(title = stringResource(R.string.settings_units), onBack = onBack) {
        SwitchRow(
            label = stringResource(R.string.units_use_miles),
            checked = useMiles,
            onChange = viewModel::setUseMiles,
        )
        SwitchRow(
            label = stringResource(R.string.units_use_feet),
            checked = useFeet,
            onChange = viewModel::setUseFeet,
        )
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
    SettingsSubScreen(title = stringResource(R.string.debug), onBack = onBack) {
        SwitchRow(
            label = stringResource(R.string.debug_show_zoom),
            checked = showZoom,
            onChange = { scope.launch { app.userPreferences.setDebugShowZoom(it) } },
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
