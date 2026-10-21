package ch.overlandmap.map.data

import android.util.Log
import ch.overlandmap.map.data.local.WorldDao
import ch.overlandmap.map.model.GlobalSetting
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * App-wide configuration documents from Firestore's `global` collection (iOS
 * `GlobalSettingsRepository`), today just the travel-advisory URL templates behind the country
 * screen's "Official website" section. They are cached in the `setting` table so the section
 * renders offline; the launch-time refresh never throws.
 */
class GlobalSettingsRepository(
    private val dao: WorldDao,
    private val auth: AuthRepository,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    /** The advisory templates, keyed `<alpha3>_<language>`; empty until first fetched. */
    fun advisoryUrlTemplates(): Flow<Map<String, String>> =
        dao.observeSetting(GlobalSetting.ADVISORY_URL_TEMPLATE_ID).map { it?.advisoryUrlTemplates ?: emptyMap() }

    /** Fetches one document and caches it. */
    suspend fun refresh(documentId: String): GlobalSetting? {
        auth.awaitUser()
        val doc = db.collection("global").document(documentId).get(Source.SERVER).await()
        val data = doc.data ?: return null
        return GlobalSetting.fromFirestore(documentId, data).also { dao.insertSettings(listOf(it)) }
    }

    /** Launch-time refresh: an offline start must not fail, the cached copy is still usable. */
    suspend fun refreshAdvisoryUrlTemplates() {
        try {
            refresh(GlobalSetting.ADVISORY_URL_TEMPLATE_ID)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d("GlobalSettings", "Advisory template refresh failed: ${e.message}")
        }
    }
}
