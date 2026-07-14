package ch.overlandmap.map.data

import ch.overlandmap.map.data.local.WorldDao
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * The free world data (countries, borders, border posts), cached in Room so
 * the overland map works offline and without an account.
 */
class WorldRepository(
    private val dao: WorldDao,
    private val auth: AuthRepository,
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    fun observeCountries() = dao.observeCountries()

    fun observeBorders() = dao.observeBorders()

    fun observeBorderPosts() = dao.observeBorderPosts()

    suspend fun countryWithCode(isoA2: String) = dao.countryWithCode(isoA2)

    suspend fun isEmpty() = dao.countryCount() == 0

    /** Refreshes the cache from Firestore. Call when online; failures keep the cache. */
    suspend fun refresh() {
        auth.awaitUser()
        val countries = db.collection("country").get().await()
            .documents.map { Country.fromFirestore(it.id, it.data ?: emptyMap()) }
        val borders = db.collection("border").get().await()
            .documents.map { CountryBorder.fromFirestore(it.id, it.data ?: emptyMap()) }
        val posts = db.collection("border_post").get().await()
            .documents.map { BorderPost.fromFirestore(it.id, it.data ?: emptyMap()) }
        dao.insertCountries(countries)
        dao.insertBorders(borders)
        dao.insertBorderPosts(posts)
    }
}
