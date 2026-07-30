package ch.overlandmap.map.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.Surface
import ch.overlandmap.map.AppMode
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.data.PackAssetKind
import ch.overlandmap.map.data.PackDownloadProgress
import ch.overlandmap.map.data.PlanetMapState
import ch.overlandmap.map.R
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * First-launch gate: the user must accept the Privacy Policy and Terms of
 * Service before entering the app. Also surfaces the offline world-map download
 * (its size and progress). Both actions accept the policies — one goes on to the
 * tutorial; the other jumps to a sample itinerary. In multi-pack mode that means
 * the Shop tab (via [onAcceptSample]); in single-track-pack mode the sample
 * itinerary is downloaded here (modal progress) before [onAcceptSample] opens
 * the pack's viewer.
 */
@Composable
fun OnboardingScreen(
    onViewPrivacy: () -> Unit,
    onViewTerms: () -> Unit,
    onAcceptTutorial: () -> Unit,
    onAcceptSample: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as OverlandApp
    val scope = rememberCoroutineScope()
    var accepted by remember { mutableStateOf(false) }
    // Single-pack sample download, shown as a modal progress bar over onboarding.
    var downloadingSample by remember { mutableStateOf(false) }
    var sampleProgress by remember { mutableStateOf<PackDownloadProgress?>(null) }

    // The user must accept to move on: swallow the system back gesture.
    BackHandler(enabled = true) {}

    val planetState by app.planetMapManager.state.collectAsState()
    val sizeBytes by produceState<Long?>(initialValue = null) {
        value = app.planetMapManager.assetSizeBytes()
    }

    fun finishAndFetchPlanet() {
        scope.launch {
            app.userPreferences.setOnboardingShown(true)
            // Make sure the world map is being fetched (idempotent).
            app.planetMapManager.ensurePlanet()
        }
    }

    fun onTutorial() {
        finishAndFetchPlanet()
        onAcceptTutorial()
    }

    fun onSample() {
        if (!AppMode.singleTrackPack) {
            // Multi-pack: accept and let the caller switch to the Shop tab.
            finishAndFetchPlanet()
            onAcceptSample()
            return
        }
        // Single-pack: start the sample download now and hold a modal progress
        // bar until it finishes, then hand off to the pack viewer.
        downloadingSample = true
        scope.launch {
            app.userPreferences.setOnboardingShown(true)
            app.planetMapManager.ensurePlanet()
            val packId = startSampleDownload(app)
            if (packId != null) {
                app.packDownloadManager.progress
                    .map { it[packId] }
                    .onEach { sampleProgress = it }
                    .first { it != null && (it.done || it.error != null) }
            }
            downloadingSample = false
            onAcceptSample()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = null,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.onboarding_welcome_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        LegalLinkRow(Icons.Filled.PrivacyTip, stringResource(R.string.onboarding_view_privacy), onViewPrivacy)
        LegalLinkRow(Icons.Filled.Gavel, stringResource(R.string.onboarding_view_terms), onViewTerms)

        Spacer(Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { accepted = !accepted }
                .padding(vertical = 4.dp),
        ) {
            Checkbox(checked = accepted, onCheckedChange = { accepted = it })
            Text(
                stringResource(R.string.onboarding_accept_checkbox),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 4.dp),
            )
        }

        Spacer(Modifier.height(24.dp))
        WorldMapStatus(planetState, sizeBytes)
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = { onTutorial() },
            enabled = accepted && !downloadingSample,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.onboarding_accept_tutorial))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { onSample() },
            enabled = accepted && !downloadingSample,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                stringResource(
                    if (AppMode.singleTrackPack) R.string.onboarding_accept_sample_single
                    else R.string.onboarding_accept_sample_multi
                )
            )
        }
    }

    if (downloadingSample) {
        SampleDownloadDialog(sampleProgress)
    }
}

/** Modal progress while the single-pack sample itinerary downloads. */
@Composable
private fun SampleDownloadDialog(progress: PackDownloadProgress?) {
    Dialog(onDismissRequest = {}) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.onboarding_downloading_sample),
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                // A known fraction shows determinate progress; the null (install)
                // phase shows an indeterminate bar.
                val p = progress
                if (p != null && p.fraction != null && !p.done) {
                    val f = p.fraction
                    LinearProgressIndicator(progress = { f }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                progress?.message?.takeIf { it.isNotEmpty() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/**
 * Resolves the configured single-track pack and enqueues its free sample
 * itinerary download. Returns the pack's document ID only when a download was
 * actually started (so its progress can be watched); null otherwise — the pack
 * couldn't be resolved or has no sample — so the caller moves on without waiting.
 */
private suspend fun startSampleDownload(app: OverlandApp): String? {
    val name = AppMode.trackPackName ?: return null
    val pack = app.libraryRepository.trackPackByName(name)
        ?: runCatching { app.shopRepository.trackPackByName(name) }.getOrNull()
        ?: return null
    AppMode.trackPackId = pack.documentId
    val assetId = pack.freeItineraryZip?.takeIf { it.isNotEmpty() } ?: return null
    val asset = runCatching { app.shopRepository.asset(assetId) }.getOrNull() ?: return null
    app.packDownloadManager.start(pack.documentId, mapOf(PackAssetKind.FREE_ITINERARY to asset))
    return pack.documentId
}

@Composable
private fun LegalLinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(label, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WorldMapStatus(state: PlanetMapState, sizeBytes: Long?) {
    val sizeText = if (sizeBytes != null && sizeBytes > 0) formatBytes(sizeBytes) else "…"
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Public, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.onboarding_world_map_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.onboarding_world_map_desc, sizeText),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        when (state) {
            is PlanetMapState.Ready -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                Text(stringResource(R.string.onboarding_world_map_ready), style = MaterialTheme.typography.bodyMedium)
            }
            is PlanetMapState.Downloading -> {
                Text(
                    stringResource(R.string.onboarding_world_map_downloading),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                val fraction = state.fraction
                if (fraction != null) {
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            PlanetMapState.Missing -> Text(
                stringResource(R.string.onboarding_world_map_pending),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Human-readable byte size, base-1000 to match the assets' declared MB. */
private fun formatBytes(bytes: Long): String {
    val mb = bytes / 1_000_000.0
    return if (mb >= 1000) "%.1f GB".format(mb / 1000) else "%.0f MB".format(mb)
}
