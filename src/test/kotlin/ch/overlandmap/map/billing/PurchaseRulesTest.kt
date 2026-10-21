package ch.overlandmap.map.billing

import ch.overlandmap.map.model.Entitlements
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.UserPurchase
import ch.overlandmap.map.data.local.toModel
import ch.overlandmap.map.data.local.toRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PurchaseRulesTest {

    @Test
    fun gateRequiresARealVerifiedAccount() {
        assertEquals(PurchaseRules.Gate.SIGN_IN_FIRST, PurchaseRules.gate(false, false, false))
        assertEquals(PurchaseRules.Gate.SIGN_IN_FIRST, PurchaseRules.gate(true, true, false))
        assertEquals(PurchaseRules.Gate.VERIFY_EMAIL, PurchaseRules.gate(true, false, false))
        assertEquals(PurchaseRules.Gate.OK, PurchaseRules.gate(true, false, true))
    }

    @Test
    fun backendErrorPayloadIsAFailureNotAValidPurchase() {
        assertEquals("Not purchased", PurchaseRules.validationError(mapOf("error" to "Not purchased")))
        assertEquals("Purchase validation failed", PurchaseRules.validationError(mapOf("error" to "")))
        assertEquals("Purchase validation failed", PurchaseRules.validationError(mapOf("error" to 42)))
        assertNull(PurchaseRules.validationError(mapOf("success" to true)))
        assertNull(PurchaseRules.validationError(null))
        assertNull(PurchaseRules.validationError("ok"))
    }

    @Test
    fun adminsAndBetaTestersSeeUnpublishedPacks() {
        fun p(id: String) = UserPurchase(id, null, null, true, null, null, null)
        assertFalse(Entitlements().canSeeUnpublished)
        assertFalse(Entitlements(listOf(p("packA"), p("pro"))).canSeeUnpublished)
        assertTrue(Entitlements(listOf(p("beta"))).canSeeUnpublished)
        assertTrue(Entitlements(listOf(p("ADMIN"))).canSeeUnpublished)
    }

    @Test
    fun betaAndFreeOnlyFlagsAreReadAndStored() {
        val pack = TrackPack.fromFirestore("p", mapOf("name" to "P", "beta" to true, "isFreeItinOnly" to true))
        assertTrue(pack.isBeta)
        assertTrue(pack.isFreeItineraryOnly)
        val back = pack.toRow().toModel()
        assertTrue(back.isBeta)
        assertTrue(back.isFreeItineraryOnly)
        assertFalse(TrackPack.fromFirestore("p", mapOf("name" to "P")).isBeta)

        val it = Itinerary.fromFirestore("i", mapOf("name" to "I", "beta" to true))
        assertTrue(it.isBeta)
        assertTrue(it.toRow().toModel().isBeta)
    }
}
