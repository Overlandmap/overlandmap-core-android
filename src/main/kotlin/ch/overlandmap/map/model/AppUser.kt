package ch.overlandmap.map.model

/**
 * The user's profile document (`users/{uid}`, the document ID being the
 * FirebaseAuth uid). Written only by the backend's stored functions; the app
 * reads it in real time. Anonymous sessions have no document.
 */
data class AppUser(
    val documentId: String,
    val displayName: String? = null,
    val email: String? = null,
    val profileUrl: String? = null,
    val emailVerified: Boolean = false,
) {
    companion object {
        fun fromFirestore(documentId: String, data: Map<String, Any?>) = AppUser(
            documentId = documentId,
            displayName = FS.str(data["displayName"]),
            email = FS.str(data["email"]),
            profileUrl = FS.str(data["profileUrl"]),
            emailVerified = FS.bool(data["emailVerified"]),
        )
    }
}
