package ch.overlandmap.map.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** How a Play purchase flow ended, for the screen that started it. */
sealed interface PurchaseOutcome {
    /** The user backed out of the Play dialog; no error to show. */
    object Cancelled : PurchaseOutcome
    data class Failed(val message: String) : PurchaseOutcome
    /**
     * Play reported the purchase and the token was accepted by the
     * `validateGoogleToken` function. The purchase only counts once its
     * document shows up in `users/{uid}/purchases` with `purchased == true`.
     */
    data class Validated(val productId: String) : PurchaseOutcome
}

/**
 * Google Play purchases. The client never writes purchases to Firestore:
 * after Play reports a purchase, the token is sent to the `validateGoogleToken`
 * cloud function, which verifies it with Google and stores the purchase in
 * `users/{uid}/purchases`. The app then sees it through the purchases snapshot
 * listener (ShopRepository).
 */
class BillingManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(),
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) : PurchasesUpdatedListener {

    /** Product ID → localized price, for the shop UI. */
    val prices = MutableStateFlow<Map<String, String>>(emptyMap())

    /** How purchase flows end; screens showing a progress wheel collect this. */
    val outcomes = MutableSharedFlow<PurchaseOutcome>(extraBufferCapacity = 8)

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        .build()

    private var productDetails: Map<String, ProductDetails> = emptyMap()

    /**
     * Connects to Play (once) and loads the details of the given product IDs.
     * Callable repeatedly from any screen: the single shared client is
     * connected lazily and reused, never re-`startConnection`ed while already
     * live — doing so tears the connection down and makes queries come back
     * empty.
     */
    fun loadProducts(productIds: List<String>) {
        Log.d(TAG, "loadProducts: requested ${productIds.size} id(s): $productIds")
        if (productIds.isEmpty()) {
            Log.w(
                TAG,
                "loadProducts: no product IDs to query — the pack(s) carry no baseProductId, " +
                    "so no price can resolve and the button stays \"Purchase not available\".",
            )
            return
        }
        scope.launch {
            if (ensureConnected()) {
                queryProducts(productIds)
            } else {
                Log.w(TAG, "loadProducts: not connected to Play, skipping query for $productIds")
            }
        }
    }

    /**
     * Suspends until the client is connected, returning whether it is. Only
     * the first caller starts the connection; concurrent callers await the
     * same attempt.
     */
    private suspend fun ensureConnected(): Boolean {
        if (client.isReady) {
            Log.d(TAG, "ensureConnected: client already ready")
            return true
        }
        return suspendCoroutine { cont ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                    Log.d(
                        TAG,
                        "billing setup finished: code=${result.responseCode} " +
                            "\"${result.debugMessage}\" ok=$ok",
                    )
                    cont.resume(ok)
                }

                override fun onBillingServiceDisconnected() {
                    Log.w(TAG, "billing service disconnected")
                }
            })
        }
    }

    private suspend fun queryProducts(productIds: List<String>) {
        val products = productIds.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        val (result, list) = suspendCancellableCoroutine { cont ->
            client.queryProductDetailsAsync(params) { result, list ->
                cont.resume(result to list)
            }
        }

        // Debug: exactly what Play returned, so "Purchase not available" can be
        // traced to a bad response code, a missing product, or an offer with no
        // one-time price. Visible in logcat under the "Billing" tag.
        Log.d(
            TAG,
            "queryProducts: code=${result.responseCode} \"${result.debugMessage}\" " +
                "returned ${list.size} of ${productIds.size} product(s)",
        )
        list.forEach { d ->
            Log.d(
                TAG,
                "  product=${d.productId} type=${d.productType} " +
                    "price=${d.oneTimePurchaseOfferDetails?.formattedPrice ?: "<none>"}",
            )
        }
        val missing = productIds - list.map { it.productId }.toSet()
        if (missing.isNotEmpty()) {
            Log.w(
                TAG,
                "queryProducts: Play returned no details for $missing. Check that these " +
                    "product IDs exist as active in-app products in Play Console and that " +
                    "this build is installed from a track signed with the app-signing key " +
                    "(prices don't resolve for locally-signed/sideloaded builds).",
            )
        }

        if (list.isEmpty()) return
        productDetails = productDetails + list.associateBy { it.productId }
        // Only products with a one-time purchase offer get a price entry: a
        // missing entry is the UI's signal that the product can't be bought.
        // Merge, never replace — a single-pack query must not wipe prices the
        // shop list already resolved.
        prices.value = prices.value + list.mapNotNull { d ->
            d.oneTimePurchaseOfferDetails?.formattedPrice?.let { d.productId to it }
        }.toMap()
    }

    /** Starts the Play purchase flow. The user must be signed in. */
    fun buy(activity: Activity, productId: String): Boolean {
        val details = productDetails[productId] ?: run {
            Log.w(TAG, "buy: no cached details for $productId — its price never resolved")
            return false
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build()
                )
            )
            .build()
        val code = client.launchBillingFlow(activity, params).responseCode
        Log.d(TAG, "buy: launchBillingFlow code=$code for $productId")
        return code == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when {
            result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null ->
                purchases
                    .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                    .forEach { purchase -> scope.launch { validateAndAcknowledge(purchase) } }
            result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED ->
                outcomes.tryEmit(PurchaseOutcome.Cancelled)
            else -> outcomes.tryEmit(
                PurchaseOutcome.Failed(
                    result.debugMessage.ifEmpty { "Purchase failed (${result.responseCode})" }
                )
            )
        }
    }

    private suspend fun validateAndAcknowledge(purchase: Purchase) {
        val productId = purchase.products.firstOrNull() ?: return
        try {
            functions.getHttpsCallable("validateGoogleToken").call(
                mapOf(
                    "token" to purchase.purchaseToken,
                    "productId" to productId,
                    "userId" to auth.currentUser?.uid,
                    "appVersion" to appVersion(),
                )
            ).await()
            acknowledge(purchase)
            outcomes.tryEmit(PurchaseOutcome.Validated(productId))
        } catch (e: Exception) {
            Log.w(TAG, "Purchase validation failed", e)
            outcomes.tryEmit(PurchaseOutcome.Failed(e.localizedMessage ?: "Validation failed"))
        }
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "android"
    } catch (_: Exception) {
        "android"
    }

    private suspend fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        suspendCancellableCoroutine { cont ->
            client.acknowledgePurchase(params) { cont.resume(Unit) }
        }
    }

    private companion object {
        const val TAG = "Billing"
    }
}
