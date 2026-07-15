package ch.overlandmap.map.ui.home

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.billing.PurchaseOutcome
import ch.overlandmap.map.data.PackAssetKind
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.TrackPack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LocalPackState(
    val loading: Boolean = true,
    val pack: TrackPack? = null,
    val itineraries: List<Itinerary> = emptyList(),
    val sidebars: List<Sidebar> = emptyList(),
)

/** Outcome of the menu's "check for update", shown as a snackbar. */
enum class UpdateCheck { AVAILABLE, UP_TO_DATE, FAILED }

/** A downloaded pack, read entirely from Room; comments cached from Firestore. */
class LocalPackViewModel(
    private val app: OverlandApp,
    private val packId: String,
) : ViewModel() {

    private val library = app.libraryRepository
    private val shop = app.shopRepository

    val state = MutableStateFlow(LocalPackState())

    /** True when a real account is signed in (the anonymous session doesn't count). */
    val signedIn: StateFlow<Boolean> = app.authRepository.userFlow
        .map { it != null && !it.isAnonymous }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            app.authRepository.currentUser?.isAnonymous == false,
        )

    /** True once this pack is purchased (real-time from `users/{uid}/purchases`). */
    @OptIn(ExperimentalCoroutinesApi::class)
    val owned: StateFlow<Boolean> = app.authRepository.userFlow
        .flatMapLatest { shop.purchasesFlow() }
        .map { purchases -> purchases.any { it.covers(packId) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Product ID → localized Play price, for the "purchase all itineraries" button. */
    val prices = app.billingManager.prices

    /** True while a purchase is being processed; the button shows a spinner. */
    val purchasing = MutableStateFlow(false)

    /** Error of the last purchase attempt, shown under the button. */
    val purchaseError = MutableStateFlow<String?>(null)

    /** The `itineraryId` slug of the itinerary picked on the map or in the list. */
    val selectedItineraryId = MutableStateFlow<String?>(null)

    /** One-shot result of the last "check for update"; consumer clears it. */
    val updateCheck = MutableStateFlow<UpdateCheck?>(null)

    fun selectItinerary(itineraryId: String?) {
        selectedItineraryId.value = itineraryId
    }

    val comments: StateFlow<List<Comment>> = library.observeComments(packId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** This pack's re-download after an update, for the progress overlay. */
    val downloadProgress = app.packDownloadManager.progress
        .map { it[packId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        load()
        // Stop the purchase spinner when Play cancels/fails, and once the
        // validated purchase lands in Firestore (owned flips true).
        viewModelScope.launch {
            app.billingManager.outcomes.collect { outcome ->
                when (outcome) {
                    is PurchaseOutcome.Cancelled -> purchasing.value = false
                    is PurchaseOutcome.Failed -> {
                        purchasing.value = false
                        purchaseError.value = outcome.message
                    }
                    is PurchaseOutcome.Validated -> Unit
                }
            }
        }
        viewModelScope.launch {
            owned.collect { if (it) purchasing.value = false }
        }
        // Refresh the comment cache; offline the cache keeps its content.
        viewModelScope.launch {
            runCatching { library.refreshComments(packId) }
        }
        // An update's re-download finished: show the fresh content.
        viewModelScope.launch {
            downloadProgress.collect { if (it?.done == true) load() }
        }
    }

    private fun load() {
        viewModelScope.launch {
            val pack = library.trackPack(packId)
            state.value = LocalPackState(
                loading = false,
                pack = pack,
                itineraries = library.itinerariesOf(packId).sortedForGrid(),
                sidebars = library.sidebars(packId),
            )
            // A free sample offers to buy the full pack, so resolve its price.
            if (pack?.isFreeSample == true) {
                pack.productId?.let { app.billingManager.loadProducts(listOf(it)) }
            }
        }
    }

    /** Starts the Play purchase of the full pack; requires a signed-in user. */
    fun buy(activity: Activity) {
        val productId = state.value.pack?.productId ?: return
        purchaseError.value = null
        if (app.billingManager.buy(activity, productId)) {
            purchasing.value = true
        } else {
            purchaseError.value = "Product not available"
        }
    }

    /** Downloads the full (purchased) pack, replacing the local sample. */
    fun downloadPack() {
        val pack = state.value.pack ?: return
        app.packDownloadManager.startFullPack(packId, pack.name)
    }

    /** Deletes the pack's database rows and downloaded photos. */
    fun deletePack(onDeleted: () -> Unit) {
        viewModelScope.launch {
            library.deletePack(packId)
            onDeleted()
        }
    }

    /**
     * Compares the online zip asset's version with the local pack's and
     * flags the pack when a newer one exists (persisted, so the library
     * grid shows its update badge too).
     */
    fun checkForUpdate() {
        val pack = state.value.pack ?: return
        viewModelScope.launch {
            updateCheck.value = try {
                val online = app.shopRepository.trackPack(packId)
                val assetId = online?.trackPackZip
                val onlineVersion = assetId?.let { app.shopRepository.asset(it) }?.version
                    ?: online?.version
                if (onlineVersion != null && onlineVersion > (pack.version ?: 0)) {
                    library.setNeedsUpdate(packId, true)
                    load()
                    UpdateCheck.AVAILABLE
                } else {
                    UpdateCheck.UP_TO_DATE
                }
            } catch (e: Exception) {
                UpdateCheck.FAILED
            }
        }
    }

    /**
     * Updates the pack: deletes the local copy, then downloads it again the
     * usual way — the purchased zip through `downloadTrackPackUrl`, or the
     * free sample's zip asset when that is all the user had. The download
     * source is resolved before anything is deleted: a sample whose asset
     * cannot be found (the local row lacks the reference; the online pack
     * document carries it) aborts instead of losing the pack.
     */
    fun update() {
        val pack = state.value.pack ?: return
        viewModelScope.launch {
            if (pack.isFreeSample) {
                val assetId = runCatching { app.shopRepository.trackPack(packId) }
                    .getOrNull()?.freeItineraryZip
                    ?: pack.freeItineraryZip?.takeIf { it.isNotEmpty() }
                val asset = assetId?.let { runCatching { app.shopRepository.asset(it) }.getOrNull() }
                if (asset == null) {
                    updateCheck.value = UpdateCheck.FAILED
                    return@launch
                }
                library.deletePack(packId)
                app.packDownloadManager.start(packId, mapOf(PackAssetKind.FREE_ITINERARY to asset))
            } else {
                library.deletePack(packId)
                app.packDownloadManager.startFullPack(packId, pack.name)
            }
        }
    }
}

/**
 * Grid order: free itineraries first, then by the numeric suffix of the
 * `itineraryId` slug (K1, K2, … K25 rather than K1, K10, K11, …).
 */
private fun List<Itinerary>.sortedForGrid(): List<Itinerary> = sortedWith(
    compareByDescending<Itinerary> { it.isFree }
        .thenBy { it.itineraryId.takeLastWhile(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }
        .thenBy { it.itineraryId },
)
