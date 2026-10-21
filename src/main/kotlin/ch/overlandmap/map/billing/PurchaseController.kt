package ch.overlandmap.map.billing

import android.app.Activity
import ch.overlandmap.map.OverlandApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** What went wrong with a purchase, for the screen to word in the user's language. */
sealed interface PurchaseError {
    /** The pack's price never resolved in Play (no product, or not installed from a Play track). */
    object ProductUnavailable : PurchaseError

    /** The account's email address isn't verified yet. */
    object VerifyEmail : PurchaseError

    /** The purchase was paid and validated but its document didn't show up in time. */
    object NotSettled : PurchaseError

    data class Message(val text: String) : PurchaseError
}

/**
 * The state machine behind a "Purchase" button, shared by the shop's pack detail and the local
 * pack screen: the gate (account, verified email), the Play flow, the validation round trip and the
 * wait for the purchase document. [owned] flips true when `users/{uid}/purchases` lists the pack.
 */
class PurchaseController(
    private val app: OverlandApp,
    private val scope: CoroutineScope,
    private val owned: Flow<Boolean>,
) {
    /** True from the tap until the purchase settled, was cancelled or failed; drives the spinner. */
    val purchasing = MutableStateFlow(false)

    val error = MutableStateFlow<PurchaseError?>(null)

    /** Play is waiting for the payment; the purchase may still complete later. */
    val pending = MutableStateFlow(false)

    private var isOwned = false

    init {
        scope.launch {
            app.billingManager.outcomes.collect { outcome ->
                when (outcome) {
                    is PurchaseOutcome.Cancelled -> purchasing.value = false
                    is PurchaseOutcome.Failed -> {
                        purchasing.value = false
                        error.value = PurchaseError.Message(outcome.message)
                    }
                    is PurchaseOutcome.Pending -> {
                        purchasing.value = false
                        pending.value = true
                    }
                    // Keep spinning until the purchase document arrives (below), but not forever.
                    is PurchaseOutcome.Validated -> scope.launch {
                        delay(PurchaseRules.SETTLE_TIMEOUT_MS)
                        if (purchasing.value && !isOwned) {
                            purchasing.value = false
                            error.value = PurchaseError.NotSettled
                        }
                    }
                }
            }
        }
        scope.launch {
            owned.collect {
                isOwned = it
                if (it) {
                    purchasing.value = false
                    pending.value = false
                }
            }
        }
    }

    /** Starts the Play flow for [productId] when the gate lets it; the result arrives in the flows. */
    fun buy(activity: Activity, productId: String?) {
        error.value = null
        pending.value = false
        if (productId == null) {
            error.value = PurchaseError.ProductUnavailable
            return
        }
        scope.launch {
            val auth = app.authRepository
            // Pick up an address verified since the last look, as iOS does before every purchase.
            runCatching { auth.reloadUser() }
            val user = auth.currentUser
            when (PurchaseRules.gate(user != null, user?.isAnonymous != false, user?.isEmailVerified == true)) {
                PurchaseRules.Gate.SIGN_IN_FIRST -> return@launch
                PurchaseRules.Gate.VERIFY_EMAIL -> {
                    error.value = PurchaseError.VerifyEmail
                    return@launch
                }
                PurchaseRules.Gate.OK -> Unit
            }
            // The details of the product have to be loaded before it can be bought.
            if (app.billingManager.buy(activity, productId)) {
                purchasing.value = true
            } else {
                error.value = PurchaseError.ProductUnavailable
            }
        }
    }
}
