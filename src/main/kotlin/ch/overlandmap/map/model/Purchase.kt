package ch.overlandmap.map.model

/**
 * A validated purchase, read from `users/{uid}/purchases`. The document ID is
 * the purchased track pack's ID — or [PRO_ID] for the pro entitlement, which
 * additionally carries a validity end date. Documents are only ever written
 * by the `validateGoogleToken` cloud function after it verified the Play
 * purchase token with Google — the client has no write access.
 */
data class UserPurchase(
    val documentId: String,
    val productId: String?,
    val trackPackId: String?,
    /** Only a purchase with `purchased == true` counts as owned. */
    val purchased: Boolean,
    val purchasedAt: Long?,
    /** End of validity (epoch millis); pro is active while it is in the future. */
    val validityEnd: Long?,
    val purchasePrice: String?,
) {
    val isPro: Boolean get() = documentId == PRO_ID

    /** A purchase with this id marks the user as an admin (matched case-insensitively, as on iOS). */
    val isAdmin: Boolean get() = documentId.equals(ADMIN_ID, ignoreCase = true)

    /** A purchase with this id marks the user as a beta tester (matched case-insensitively). */
    val isBeta: Boolean get() = documentId.equals(BETA_ID, ignoreCase = true)

    /** True for an owned track pack or a pro entitlement still running. */
    val isActive: Boolean
        get() = purchased && (!isPro || (validityEnd ?: 0L) > System.currentTimeMillis())

    fun covers(packId: String): Boolean =
        isActive && (documentId == packId || trackPackId == packId)

    companion object {
        const val PRO_ID = "pro"
        const val ADMIN_ID = "admin"
        const val BETA_ID = "beta"

        fun fromFirestore(documentId: String, data: Map<String, Any?>) = UserPurchase(
            documentId = documentId,
            productId = FS.str(data["productId"]),
            trackPackId = FS.str(data["trackPackId"]),
            purchased = FS.bool(data["purchased"]),
            purchasedAt = FS.millis(data["purchaseDate"])
                ?: FS.millis(data["createdAt"])
                ?: FS.millis(data["date"]),
            validityEnd = FS.millis(data["validityEnd"]),
            purchasePrice = FS.str(data["purchasePrice"]),
        )
    }
}

/**
 * What the signed-in user is entitled to, derived from their [UserPurchase] documents
 * (iOS `Purchase` / `services.purchases`). Admin and beta are special document ids.
 */
data class Entitlements(val purchases: List<UserPurchase> = emptyList()) {
    /** An active pro subscription (purchased and `validityEnd` in the future). */
    fun hasPro(now: Long = System.currentTimeMillis()): Boolean =
        purchases.any { it.isPro && it.purchased && (it.validityEnd ?: 0L) > now }

    val hasAdmin: Boolean get() = purchases.any { it.isAdmin }

    /** Admins and beta testers see the packs that are not published yet (iOS `fetchCatalog(includeUnpublished:)`). */
    val canSeeUnpublished: Boolean get() = hasAdmin || hasBeta
    val hasBeta: Boolean get() = purchases.any { it.isBeta }

    /** Whether the user owns [packId] through an active purchase of it. */
    fun owns(packId: String): Boolean = purchases.any { it.covers(packId) }
}
