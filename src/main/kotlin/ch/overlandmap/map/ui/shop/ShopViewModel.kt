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

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            state.value = state.value.copy(loading = true, offline = false)
            try {
                val packs = shop.trackPacks()
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
