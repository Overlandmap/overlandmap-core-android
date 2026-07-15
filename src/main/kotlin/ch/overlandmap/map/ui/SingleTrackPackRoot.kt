package ch.overlandmap.map.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ch.overlandmap.map.AppMode
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.ui.home.LocalPackScreen
import ch.overlandmap.map.ui.shop.PackDetailScreen

private sealed interface PackResolution {
    object Loading : PackResolution
    object NotFound : PackResolution
    data class Found(val trackPackId: String) : PackResolution
}

/**
 * The root screen of a single-track-pack app (no bottom tabs). Resolves the
 * configured [trackPackName] to a document ID once — from the local DB first
 * (so a downloaded pack works offline), then Firestore — and caches it in
 * [AppMode.trackPackId]. If the pack is in the local library it shows its
 * viewer; otherwise it shows the shop detail (buy / sample download). Settings
 * are reached from the top-bar action on either screen.
 */
@Composable
fun SingleTrackPackRoot(
    trackPackName: String,
    onOpenItinerary: (documentId: String, stepId: Int?) -> Unit,
    onOpenShopPack: (packId: String) -> Unit,
    onOpenSignIn: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as OverlandApp

    val resolution by produceState<PackResolution>(
        initialValue = AppMode.trackPackId?.let { PackResolution.Found(it) } ?: PackResolution.Loading,
        trackPackName,
    ) {
        if (value is PackResolution.Found) return@produceState
        val id = app.libraryRepository.trackPackByName(trackPackName)?.documentId
            ?: runCatching { app.shopRepository.trackPackByName(trackPackName)?.documentId }.getOrNull()
        AppMode.trackPackId = id
        value = if (id != null) PackResolution.Found(id) else PackResolution.NotFound
    }

    // Whether the resolved pack is in the local library decides viewer vs shop.
    val packs by produceState<List<TrackPack>?>(initialValue = null, app) {
        app.libraryRepository.observeTrackPacks().collect { value = it }
    }

    when (val r = resolution) {
        PackResolution.Loading -> Centered { CircularProgressIndicator() }
        PackResolution.NotFound -> Centered {
            Text(
                "\"$trackPackName\" not found",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp),
            )
        }
        is PackResolution.Found -> {
            val local = packs?.any { it.documentId == r.trackPackId }
            when (local) {
                null -> Centered { CircularProgressIndicator() }
                true -> LocalPackScreen(
                    packId = r.trackPackId,
                    onBack = {},
                    onOpenItinerary = onOpenItinerary,
                    onOpenShopPack = onOpenShopPack,
                    onOpenSettings = onOpenSettings,
                    onOpenSignIn = onOpenSignIn,
                )
                false -> PackDetailScreen(
                    packId = r.trackPackId,
                    onBack = {},
                    onOpenSignIn = onOpenSignIn,
                    onOpenSettings = onOpenSettings,
                )
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
