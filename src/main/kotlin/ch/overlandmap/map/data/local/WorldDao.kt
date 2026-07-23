package ch.overlandmap.map.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Access to the always-available world data: countries, borders, border posts.
 * Room implements the `_`-prefixed row methods; the public methods map rows to
 * and from the domain models (see WorldRows).
 */
@Dao
interface WorldDao {

    @Query("SELECT * FROM country ORDER BY name")
    fun _observeCountries(): Flow<List<CountryRow>>
    fun observeCountries(): Flow<List<Country>> =
        _observeCountries().map { rows -> rows.map { it.toModel() } }

    @Query("SELECT * FROM border")
    fun _observeBorders(): Flow<List<CountryBorderRow>>
    fun observeBorders(): Flow<List<CountryBorder>> =
        _observeBorders().map { rows -> rows.map { it.toModel() } }

    @Query("SELECT * FROM border_post")
    fun _observeBorderPosts(): Flow<List<BorderPostRow>>
    fun observeBorderPosts(): Flow<List<BorderPost>> =
        _observeBorderPosts().map { rows -> rows.map { it.toModel() } }

    @Query("SELECT COUNT(*) FROM country")
    suspend fun countryCount(): Int

    @Query("SELECT * FROM country WHERE isoA2 = :isoA2 LIMIT 1")
    suspend fun _countryWithCode(isoA2: String): CountryRow?
    suspend fun countryWithCode(isoA2: String): Country? = _countryWithCode(isoA2)?.toModel()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertCountries(countries: List<CountryRow>)
    suspend fun insertCountries(countries: List<Country>) =
        _insertCountries(countries.map { it.toRow() })

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertBorders(borders: List<CountryBorderRow>)
    suspend fun insertBorders(borders: List<CountryBorder>) =
        _insertBorders(borders.map { it.toRow() })

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertBorderPosts(posts: List<BorderPostRow>)
    suspend fun insertBorderPosts(posts: List<BorderPost>) =
        _insertBorderPosts(posts.map { it.toRow() })
}
