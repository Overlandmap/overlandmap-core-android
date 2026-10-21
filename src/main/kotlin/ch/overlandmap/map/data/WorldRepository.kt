package ch.overlandmap.map.data

import ch.overlandmap.map.data.local.WorldDao
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.Zone
import kotlinx.coroutines.flow.Flow

/**
 * The Overlanding dataset (countries, borders, border posts, zones) as stored in Room. It is
 * filled by the `countries` general asset (see [OverlandDataManager]), not read from Firestore.
 */
class WorldRepository(private val dao: WorldDao) {

    fun observeCountries(): Flow<List<Country>> = dao.observeCountries()

    fun observeBorders(): Flow<List<CountryBorder>> = dao.observeBorders()

    fun observeBorderPosts(): Flow<List<BorderPost>> = dao.observeBorderPosts()

    fun observeZones(): Flow<List<Zone>> = dao.observeZones()

    suspend fun country(alpha3: String): Country? = dao.country(alpha3)

    suspend fun countryWithCode(isoA2: String): Country? = dao.countryWithCode(isoA2)

    suspend fun border(id: String): CountryBorder? = dao.border(id)

    suspend fun borderPost(id: String): BorderPost? = dao.borderPost(id)

    suspend fun zone(id: String): Zone? = dao.zone(id)

    suspend fun isEmpty() = dao.countryCount() == 0
}
