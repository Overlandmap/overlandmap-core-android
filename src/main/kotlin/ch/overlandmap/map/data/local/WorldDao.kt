package ch.overlandmap.map.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import kotlinx.coroutines.flow.Flow

/** Access to the always-available world data: countries, borders, border posts. */
@Dao
interface WorldDao {

    @Query("SELECT * FROM country ORDER BY name")
    fun observeCountries(): Flow<List<Country>>

    @Query("SELECT * FROM border")
    fun observeBorders(): Flow<List<CountryBorder>>

    @Query("SELECT * FROM border_post")
    fun observeBorderPosts(): Flow<List<BorderPost>>

    @Query("SELECT COUNT(*) FROM country")
    suspend fun countryCount(): Int

    @Query("SELECT * FROM country WHERE isoA2 = :isoA2 LIMIT 1")
    suspend fun countryWithCode(isoA2: String): Country?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCountries(countries: List<Country>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBorders(borders: List<CountryBorder>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBorderPosts(posts: List<BorderPost>)
}
