package ch.overlandmap.map.model

/**
 * The user's profile document (`users/{uid}`, the document ID being the FirebaseAuth uid).
 * Same fields iOS reads and writes: the client owns `displayName`, `email`, `uuid`,
 * `profileFsId` and `photoURL`; the backend adds `isAdmin`. Anonymous sessions have no document.
 */
data class AppUser(
    val documentId: String,
    val displayName: String? = null,
    val email: String? = null,
    /**
     * The id that ties a user to purchases made through the stores. iOS refuses to purchase
     * when it is missing, so every account created here must get one (see [AccountRules.uuidV1]).
     */
    val uuid: String? = null,
    /** `gs://bucket/userProfile/<id>.jpg`, the Storage object of the avatar. */
    val profileFsId: String? = null,
    /** Download URL of the avatar. */
    val photoUrl: String? = null,
    val isAdmin: Boolean = false,
) {
    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>) = AppUser(
            documentId = documentId,
            displayName = FS.str(data["displayName"])?.takeIf { it.isNotBlank() },
            email = FS.str(data["email"]),
            uuid = FS.str(data["uuid"])?.takeIf { it.isNotBlank() },
            profileFsId = FS.str(data["profileFsId"])?.takeIf { it.isNotBlank() },
            photoUrl = FS.str(data["photoURL"])?.takeIf { it.isNotBlank() },
            isAdmin = FS.bool(data["isAdmin"]),
        )
    }
}
