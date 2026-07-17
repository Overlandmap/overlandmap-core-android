package ch.overlandmap.map.ui.shop

import android.app.Activity
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.overlandmap.map.OverlandApp
import ch.overlandmap.map.billing.PurchaseOutcome
import ch.overlandmap.map.data.PackAssetKind
import ch.overlandmap.map.data.PackDownloadProgress
import ch.overlandmap.map.model.Asset
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.TrackPack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class PackDetailState(
    val loading: Boolean = true,
    val pack: TrackPack? = null,
    val itineraries: List<Itinerary> = emptyList(),
    val comments: List<Comment> = emptyList(),
    /** The pack's downloadable assets; kinds absent from the pack are missing. */
    val assets: Map<PackAssetKind, Asset> = emptyMap(),
    /** The full purchased-pack zip, shown as the mandatory item when owned. */
    val fullPackAsset: Asset? = null,
    /** Itinerary IDs already present in the local library. */
    val downloadedItineraryIds: Set<String> = emptySet(),
    /** True when the pack itself is in the local library. */
    val isLocal: Boolean = false,
    val error: String? = null,
)

class PackDetailViewModel(private val app: OverlandApp, private val packId: String) : ViewModel() {

    private val shop = app.shopRepository
    private val library = app.libraryRepository

    val state = MutableStateFlow(PackDetailState())

    /** The `itineraryId` slug of the itinerary picked on the map or in the list. */
    val selectedItineraryId = MutableStateFlow<String?>(null)

    fun selectItinerary(itineraryId: String?) {
        selectedItineraryId.value = itineraryId
    }

    /** True when a real account is signed in (the anonymous session doesn't count). */
    val signedIn: StateFlow<Boolean> = app.authRepository.userFlow
        .map { it != null && !it.isAnonymous }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            app.authRepository.currentUser?.isAnonymous == false,
        )

    /**
     * True once `users/{uid}/purchases` holds a document for this pack with
     * `purchased == true` (real-time: the backend adding, updating or
     * removing a purchase flips the buy/download button immediately).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val owned: StateFlow<Boolean> = app.authRepository.userFlow
        .flatMapLatest { shop.purchasesFlow() }
        .map { purchases -> purchases.any { it.covers(packId) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val prices = app.billingManager.prices

    /**
     * True from the tap on the buy button until the purchase is either
     * settled (the validated purchase arrived from Firestore), cancelled or
     * failed; the button shows a progress wheel meanwhile.
     */
    val purchasing = MutableStateFlow(false)

    /** Error of the last purchase attempt, shown under the button. */
    val purchaseError = MutableStateFlow<String?>(null)

    /**
     * This pack's background download, null when none is running. Declared
     * before init, whose collector runs immediately on the main dispatcher.
     */
    val downloadProgress: StateFlow<PackDownloadProgress?> =
        app.packDownloadManager.progress
            .map { it[packId] }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        load()
        // A finished background download put new itineraries in the library.
        viewModelScope.launch {
            downloadProgress.collect {
                if (it?.done == true) {
                    state.value = state.value.copy(
                        downloadedItineraryIds = downloadedIds(),
                        isLocal = library.trackPack(packId) != null,
                    )
                }
            }
        }
        // The progress wheel spins until the flow settles: Play cancelled or
        // failed stops it right away; after successful validation it keeps
        // spinning until the purchase document lands in Firestore (below).
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
    }

    fun load() {
        viewModelScope.launch {
            try {
                val pack = shop.trackPack(packId)
                val itineraries = pack?.let { shop.itineraries(it.documentId) } ?: emptyList()
                // Comments and assets are secondary; their failure must not
                // blank the screen (assets only disable the download dialog).
                val comments = runCatching { shop.comments(packId) }.getOrDefault(emptyList())
                val assets = pack?.let {
                    runCatching { fetchAssets(it) }.getOrDefault(emptyMap())
                } ?: emptyMap()
                val fullPackAsset = pack?.trackPackZip?.let {
                    runCatching { shop.asset(it) }.getOrNull()
                }
                state.value = PackDetailState(
                    loading = false,
                    pack = pack,
                    itineraries = itineraries,
                    comments = comments,
                    assets = assets,
                    fullPackAsset = fullPackAsset,
                    downloadedItineraryIds = downloadedIds(),
                    isLocal = library.trackPack(packId) != null,
                )
                Log.d("Billing", "PackDetail ${pack?.documentId}: productId=${pack?.productId}")
                val productId = pack?.productId
                if (productId != null) {
                    app.billingManager.loadProducts(listOf(productId))
                } else {
                    Log.w(
                        "Billing",
                        "PackDetail: pack '$packId' has no productId (baseProductId); the " +
                            "purchase button will stay \"Purchase not available\".",
                    )
                }
            } catch (e: Exception) {
                state.value = PackDetailState(loading = false, error = e.localizedMessage)
            }
        }
    }

    /** Resolves the pack's asset references into Asset documents. */
    private suspend fun fetchAssets(pack: TrackPack): Map<PackAssetKind, Asset> =
        listOf(
            PackAssetKind.FREE_ITINERARY to pack.freeItineraryZip,
            PackAssetKind.OFFLINE_MAP to pack.pmtilesMap,
            PackAssetKind.HILLSHADE to pack.hillshade,
            PackAssetKind.CONTOUR to pack.contour,
        ).mapNotNull { (kind, assetId) ->
            assetId?.let { shop.asset(it) }?.let { kind to it }
        }.toMap()

    /** Hands the chosen assets to the app-scoped background downloader. */
    fun startAssetDownload(kinds: Set<PackAssetKind>) {
        val all = state.value.assets
        viewModelScope.launch {
            // Record every asset (selected or not) so the Downloads screen can
            // list them all — the un-selected ones show as "not downloaded".
            library.savePackAssets(packId, all)
            app.packDownloadManager.start(packId, all.filterKeys { it in kinds })
        }
    }

    /** Starts the Play purchase flow; requires a signed-in user. */
    fun buy(activity: Activity) {
        val productId = state.value.pack?.productId ?: return
        purchaseError.value = null
        if (app.billingManager.buy(activity, productId)) {
            purchasing.value = true
        } else {
            purchaseError.value = "Product not available"
        }
    }

    /**
     * Downloads the whole purchased pack in the background; the worker
     * resolves the zip's temporary URL through `downloadTrackPackUrl` and
     * surfaces the function's error message in the progress overlay.
     */
    fun downloadPack() {
        val pack = state.value.pack ?: return
        app.packDownloadManager.startFullPack(packId, pack.name)
    }

    /** Downloads the full purchased pack together with the chosen offline maps. */
    fun downloadFullPack(kinds: Set<PackAssetKind>) {
        val pack = state.value.pack ?: return
        val maps = state.value.assets.filterKeys { it in kinds && it != PackAssetKind.FREE_ITINERARY }
        viewModelScope.launch {
            library.savePackAssets(packId, state.value.assets)
            app.packDownloadManager.startFullPack(packId, pack.name, maps)
        }
    }

    private suspend fun downloadedIds(): Set<String> =
        library.itinerariesOf(packId)
            // Buyable teasers are in the library table but aren't downloaded
            // content; counting them would mark the sample as downloaded.
            .filterNot { it.isBuyable }
            .map { it.documentId }
            .toSet()
}
