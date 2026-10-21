package ch.overlandmap.map.data

import ch.overlandmap.map.model.AppUser
import java.util.Random

/** Pure account rules shared with iOS (`SettingsAuthSheet`, `AppServices`). */
object AccountRules {

    /** iOS accepts an email when it contains an `@`; the server does the real validation. */
    fun isValidEmail(email: String): Boolean = email.trim().let { it.contains('@') && it.length >= 3 }

    /** Firebase's own minimum. */
    const val MIN_PASSWORD_LENGTH = 6

    fun isValidPassword(password: String): Boolean = password.length >= MIN_PASSWORD_LENGTH

    /** Registering also needs a name; signing in doesn't. */
    fun canSubmit(email: String, password: String, name: String, signUp: Boolean): Boolean =
        isValidEmail(email) && isValidPassword(password) && (!signUp || name.isNotBlank())

    /**
     * The account header's title, as iOS: the profile's name, else the email's local part,
     * else a placeholder the UI localises ([ANONYMOUS], [USER]).
     */
    fun displayTitle(profile: AppUser?, authName: String?, authEmail: String?, anonymous: Boolean): String {
        profile?.displayName?.let { return it }
        authName?.takeIf { it.isNotBlank() }?.let { return it }
        val email = profile?.email ?: authEmail
        email?.substringBefore('@')?.takeIf { it.isNotBlank() }?.let { return it }
        return if (anonymous) ANONYMOUS else USER
    }

    const val ANONYMOUS = "anonymous"
    const val USER = "user"

    /** The user name written on posts: the profile's display name, else "Anonymous". */
    fun postedName(profile: AppUser?, authName: String?): String =
        profile?.displayName ?: authName?.takeIf { it.isNotBlank() } ?: "Anonymous"

    /**
     * A version-1 style UUID (time based, as `uuidV1()` on iOS): the backend ties store
     * purchases to this value, and iOS-created accounts carry one of this shape.
     */
    fun uuidV1(nowMillis: Long = System.currentTimeMillis(), random: Random = Random()): String {
        // 100 ns intervals since 1582-10-15.
        val timestamp = (nowMillis + 12_219_292_800_000L) * 10_000L
        val timeLow = timestamp and 0xffff_ffffL
        val timeMid = (timestamp shr 32) and 0xffffL
        val timeHigh = ((timestamp shr 48) and 0x0fffL) or 0x1000L
        val clockSeq = (random.nextInt(0x4000)).toLong() or 0x8000L
        val node = ByteArray(6).also(random::nextBytes)
        return "%08x-%04x-%04x-%04x-%s".format(
            timeLow, timeMid, timeHigh, clockSeq,
            node.joinToString("") { "%02x".format(it) },
        )
    }

    private val UUID_V1 = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-1[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

    fun isUuidV1(value: String): Boolean = UUID_V1.matches(value)
}
