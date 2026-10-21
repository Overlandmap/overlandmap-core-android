package ch.overlandmap.map.billing

/** Pure purchase rules, ported from iOS `PurchaseFlow` and checked in unit tests. */
object PurchaseRules {

    /** Why a purchase may not start yet. */
    enum class Gate {
        OK,

        /** Only a real account can buy here (the anonymous session has no purchases listener). */
        SIGN_IN_FIRST,

        /** An email/password account must have verified its address first. */
        VERIFY_EMAIL,
    }

    fun gate(hasUser: Boolean, anonymous: Boolean, emailVerified: Boolean): Gate = when {
        !hasUser || anonymous -> Gate.SIGN_IN_FIRST
        !emailVerified -> Gate.VERIFY_EMAIL
        else -> Gate.OK
    }

    /**
     * The backend answers a callable with `{error: "..."}` instead of failing the call when it
     * refuses something. Returns that message, or null when the response is not an error. Without
     * this a refused purchase would be acknowledged and reported as valid.
     */
    fun validationError(response: Any?): String? {
        val map = response as? Map<*, *> ?: return null
        val error = map["error"] ?: return null
        return (error as? String)?.takeIf { it.isNotBlank() } ?: "Purchase validation failed"
    }

    /**
     * After the token is validated the purchase only counts once its document reaches
     * `users/{uid}/purchases`; if it doesn't within this time the button stops spinning and says so.
     */
    const val SETTLE_TIMEOUT_MS = 60_000L
}
