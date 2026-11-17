package ch.overlandmap.map.data.routes

import ch.overlandmap.map.data.AuthRepository
import ch.overlandmap.map.model.FS
import ch.overlandmap.map.model.localized
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * The editable content of a routing-graph segment (Firestore `segment/{id}`), as opposed to the
 * geometry and routing attributes that ship in the graph and the route tiles.
 */
class RouteSegmentDetails(
    val comment: String?,
    val commentTranslations: Map<String, String>?,
) {
    /** The comment in [lang] when translated, else the original; null when blank. */
    fun comment(lang: String): String? =
        localized(comment, commentTranslations, lang)?.takeIf { it.isNotBlank() }
}

/** Reads `segment/{id}` (iOS `FirestoreClient.routeSegmentDetails`). */
class RouteSegmentRepository(
    private val auth: AuthRepository,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    /** The segment document, or null when it does not exist or cannot be read. */
    suspend fun details(id: String): RouteSegmentDetails? = try {
        auth.awaitUser()
        val data = db.collection("segment").document(id).get().await().data
        data?.let {
            RouteSegmentDetails(
                comment = FS.str(it["comment"]),
                commentTranslations = FS.stringMap(it["comment_translations"]),
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
