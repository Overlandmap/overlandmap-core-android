package ch.overlandmap.map.data

import ch.overlandmap.map.data.local.FtsDoc
import ch.overlandmap.map.data.local.FtsIndex
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
    private val fts: FtsIndex,
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
        reindex(countries, borders, posts)
    }

    /** Rebuilds the world objects' search-index entries from a fresh snapshot. */
    private suspend fun reindex(
        countries: List<Country>,
        borders: List<CountryBorder>,
        posts: List<BorderPost>,
    ) {
        fts.deleteType(FtsIndex.TYPE_COUNTRY)
        fts.deleteType(FtsIndex.TYPE_BORDER)
        fts.deleteType(FtsIndex.TYPE_BORDER_POST)
        fts.index(
            buildList {
                countries.forEach { c ->
                    add(FtsDoc(FtsIndex.TYPE_COUNTRY, c.documentId, c::name) { c.comment(it).orEmpty() })
                }
                borders.forEach { b ->
                    add(FtsDoc(FtsIndex.TYPE_BORDER, b.documentId, { b.name }) { b.comment(it).orEmpty() })
                }
                posts.forEach { p ->
                    add(FtsDoc(FtsIndex.TYPE_BORDER_POST, p.documentId, { p.name }) { p.comment(it).orEmpty() })
                }
            }
        )
    }
}
