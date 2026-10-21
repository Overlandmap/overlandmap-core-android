package ch.overlandmap.map.data

import android.content.Context
import ch.overlandmap.map.model.AppUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * The signed-in user's Firestore profile (`users/{uid}`) and avatar, as iOS `AppServices`
 * manages them. The client owns `displayName`, `email`, `uuid`, `profileFsId` and `photoURL`.
 */
class AccountRepository(
    context: Context,
    private val auth: AuthRepository,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) {
    private val prefs = context.applicationContext.getSharedPreferences("account", Context.MODE_PRIVATE)

    suspend fun profile(uid: String): AppUser? {
        val doc = db.collection(USERS).document(uid).get().await()
        return doc.data?.let { AppUser.fromFirestore(doc.id, it) }
    }

    /** The profile of the signed-in account, or null when anonymous / not created yet. */
    suspend fun currentProfile(): AppUser? {
        val user = auth.awaitUser()
        return if (user.isAnonymous) null else profile(user.uid)
    }

    /** The name to put on a post: the profile's display name, else the Auth name, else "Anonymous". */
    suspend fun postedName(): String {
        val user = auth.currentUser
        val profile = if (user != null && !user.isAnonymous) runCatching { profile(user.uid) }.getOrNull() else null
        return AccountRules.postedName(profile, user?.displayName)
    }

    /**
     * The id purchases are tied to: stored on the device for an anonymous session, taken from
     * the profile for an account. Null for an account without one (purchasing is refused then).
     */
    suspend fun purchaseUuid(): String? {
        val user = auth.awaitUser()
        if (user.isAnonymous) return anonymousPurchaseUuid()
        return runCatching { profile(user.uid)?.uuid }.getOrNull()
    }

    /**
     * Gives an account that has no `uuid` yet one (accounts made by older builds of this app
     * didn't): iOS refuses to purchase without it. A no-op for anonymous sessions and accounts
     * that have one. Never throws.
     */
    suspend fun ensureUuid() {
        runCatching {
            val user = auth.currentUser ?: return
            if (user.isAnonymous) return
            if (profile(user.uid)?.uuid != null) return
            db.collection(USERS).document(user.uid)
                .set(mapOf("uuid" to AccountRules.uuidV1()), SetOptions.merge()).await()
        }
    }

    private fun anonymousPurchaseUuid(): String =
        prefs.getString(KEY_ANON_UUID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY_ANON_UUID, it).apply()
        }

    private fun consumeAnonymousPurchaseUuid(): String? =
        prefs.getString(KEY_ANON_UUID, null).also { prefs.edit().remove(KEY_ANON_UUID).apply() }

    /**
     * Completes the user document of a freshly registered account (iOS
     * `initializeNewUserAccount`): waits briefly for the backend to create it, then merges the
     * display name and a `uuid` if missing. The uuid is the one purchases were made under while
     * anonymous when nobody else owns it, otherwise a new version-1 uuid.
     */
    suspend fun initializeNewUserAccount(uid: String, displayName: String): AppUser? {
        delay(500)
        var existing: AppUser? = null
        for (attempt in 1..3) {
            existing = profile(uid)
            if (existing != null || attempt == 3) break
            delay(500)
        }
        val fields = HashMap<String, Any>()
        if (existing?.displayName == null) fields["displayName"] = displayName
        if (existing?.uuid == null) {
            val stored = consumeAnonymousPurchaseUuid()
            val taken = stored != null && runCatching { uuidTaken(stored) }.getOrDefault(true)
            fields["uuid"] = if (stored != null && !taken) stored else AccountRules.uuidV1()
        }
        if (fields.isEmpty()) return existing
        db.collection(USERS).document(uid).set(fields, SetOptions.merge()).await()
        return profile(uid)
    }

    private suspend fun uuidTaken(uuid: String): Boolean =
        !db.collection(USERS).whereEqualTo("uuid", uuid).limit(1).get().await().isEmpty

    /**
     * Saves the display name and, optionally, a new avatar (JPEG bytes) or removes the current
     * one. The avatar goes to Storage under `userProfile/`, then `profileFsId` and `photoURL`
     * are written to the user document.
     */
    suspend fun updateProfile(displayName: String, avatarJpeg: ByteArray?, removeAvatar: Boolean = false) {
        val user = auth.awaitUser()
        check(!user.isAnonymous) { "Sign in required." }
        val fields = HashMap<String, Any?>()
        fields["displayName"] = displayName.trim()
        user.email?.let { fields["email"] = it }
        if (removeAvatar) {
            val fsId = profile(user.uid)?.profileFsId
            if (fsId != null) runCatching { storage.getReferenceFromUrl(fsId).delete().await() }
            fields["profileFsId"] = null
            fields["photoURL"] = null
        } else if (avatarJpeg != null) {
            val path = "userProfile/${UUID.randomUUID()}.jpg"
            val ref = storage.reference.child(path)
            ref.putBytes(avatarJpeg, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
            fields["profileFsId"] = "gs://${ref.bucket}/$path"
            fields["photoURL"] = ref.downloadUrl.await().toString()
        }
        db.collection(USERS).document(user.uid).set(fields, SetOptions.merge()).await()
        auth.updateDisplayName(displayName)
    }

    private companion object {
        const val USERS = "users"
        const val KEY_ANON_UUID = "anonymousPurchaseUUID"
    }
}
