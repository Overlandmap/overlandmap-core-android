package ch.overlandmap.map.data

import ch.overlandmap.map.model.AppUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class AccountRulesTest {

    @Test
    fun emailAndPasswordValidationMatchIos() {
        assertTrue(AccountRules.isValidEmail("a@b"))
        assertFalse(AccountRules.isValidEmail("nope"))
        assertFalse(AccountRules.isValidEmail("  "))
        assertTrue(AccountRules.isValidPassword("123456"))
        assertFalse(AccountRules.isValidPassword("12345"))
    }

    @Test
    fun signUpNeedsAName() {
        assertTrue(AccountRules.canSubmit("a@b.c", "secret1", "", signUp = false))
        assertFalse(AccountRules.canSubmit("a@b.c", "secret1", "  ", signUp = true))
        assertTrue(AccountRules.canSubmit("a@b.c", "secret1", "Ann", signUp = true))
        assertFalse(AccountRules.canSubmit("a@b.c", "short", "Ann", signUp = true))
    }

    @Test
    fun headerTitleFallsBackLikeIos() {
        val profile = AppUser("u", displayName = "Ann", email = "ann@x.org")
        assertEquals("Ann", AccountRules.displayTitle(profile, "Auth", "auth@x.org", false))
        assertEquals("Auth", AccountRules.displayTitle(null, "Auth", "auth@x.org", false))
        assertEquals("auth", AccountRules.displayTitle(null, null, "auth@x.org", false))
        assertEquals("bob", AccountRules.displayTitle(AppUser("u", email = "bob@x.org"), null, null, false))
        assertEquals(AccountRules.ANONYMOUS, AccountRules.displayTitle(null, null, null, true))
        assertEquals(AccountRules.USER, AccountRules.displayTitle(null, null, null, false))
    }

    @Test
    fun postedNameDefaultsToAnonymous() {
        assertEquals("Anonymous", AccountRules.postedName(null, null))
        assertEquals("Anonymous", AccountRules.postedName(null, "  "))
        assertEquals("Auth", AccountRules.postedName(null, "Auth"))
        assertEquals("Ann", AccountRules.postedName(AppUser("u", displayName = "Ann"), "Auth"))
    }

    @Test
    fun uuidV1HasTheRightShapeAndIsTimeBased() {
        val id = AccountRules.uuidV1(1_700_000_000_000L, Random(1))
        assertTrue(id, AccountRules.isUuidV1(id))
        // 2023-11-14T22:13:20Z in 100 ns units since 1582-10-15 is 0x1ee82fc_3f6d7e00; the
        // time_low field is the low 32 bits.
        val low = ((1_700_000_000_000L + 12_219_292_800_000L) * 10_000L) and 0xffff_ffffL
        assertTrue(id.startsWith("%08x-".format(low)))
        assertTrue(AccountRules.isUuidV1(AccountRules.uuidV1()))
        assertFalse(AccountRules.isUuidV1(java.util.UUID.randomUUID().toString()))
    }

    @Test
    fun appUserReadsTheFieldsIosWrites() {
        val u = AppUser.fromFirestore(
            "u1",
            mapOf(
                "displayName" to "Ann", "email" to "a@b.c", "uuid" to "x", "profileFsId" to "gs://b/userProfile/1.jpg",
                "photoURL" to "https://img", "isAdmin" to true,
            ),
        )
        assertEquals("https://img", u.photoUrl)
        assertEquals("gs://b/userProfile/1.jpg", u.profileFsId)
        assertEquals("x", u.uuid)
        assertTrue(u.isAdmin)
        val empty = AppUser.fromFirestore("u2", mapOf("displayName" to " ", "photoURL" to ""))
        assertNull(empty.displayName)
        assertNull(empty.photoUrl)
        assertFalse(empty.isAdmin)
    }
}
