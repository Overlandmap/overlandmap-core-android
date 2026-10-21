package ch.overlandmap.map.ui.shop

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.model.TrackPack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ShopState(
    val loading: Boolean = true,
    val offline: Boolean = false,
    val packs: List<TrackPack> = emptyList(),
)

class ShopViewModel(private val app: OverlandApp) : ViewModel() {

    private val shop = app.shopRepository

    val state = MutableStateFlow(ShopState())

    /** Pack highlighted in the list and drawn in red on the map. */
    val selectedPackId = MutableStateFlow<String?>(null)

    fun selectPack(packId: String?) {
        selectedPackId.value = packId
    }

    /** Pack IDs the user owns, from the validated purchases in Firestore. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val ownedPackIds: StateFlow<Set<String>> = app.authRepository.userFlow
        .flatMapLatest { shop.purchasesFlow() }
        .map { purchases ->
            purchases.filter { it.isActive }
                .flatMap { listOfNotNull(it.documentId, it.trackPackId) }
                .toSet()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val prices = app.billingManager.prices

    /** Local packs by id → whether a newer version exists online (the shop row's Open/Update state). */
    val localPacks: StateFlow<Map<String, Boolean>> = app.libraryRepository.observeTrackPacks()
        .map { packs -> packs.associate { it.documentId to it.needsUpdate } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Running downloads by pack id, for the row's progress. */
    val downloads = app.packDownloadManager.progress

    init {
        // Load the catalogue once the entitlements are known (not before: that would list the
        // published packs only and flash the full list in afterwards), and again whenever signing
        // in or out changes whether unpublished packs are visible.
        viewModelScope.launch {
            app.entitlements
                .filterNotNull()
                .map { it.canSeeUnpublished }
                .distinctUntilChanged()
                .collect { refresh() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true, offline = false)
            try {
                val includeUnpublished = app.awaitEntitlements().canSeeUnpublished
                val packs = shop.catalogue(includeUnpublished)
                state.value = ShopState(loading = false, packs = packs)
                app.billingManager.loadProducts(packs.mapNotNull { it.productId })
            } catch (e: Exception) {
                Log.e("ShopViewModel", "Exception while refreshing shop", e)
                // Firestore unreachable: the shop is online-only.
                state.value = state.value.copy(loading = false, offline = true)
            }
        }
    }
}
