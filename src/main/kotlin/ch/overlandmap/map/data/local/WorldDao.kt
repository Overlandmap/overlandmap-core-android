package ch.overlandmap.map.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.GlobalSetting
import ch.overlandmap.map.model.Zone
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

    @Query("SELECT * FROM country WHERE iso_a2 = :isoA2 LIMIT 1")
    suspend fun _countryWithCode(isoA2: String): CountryRow?
    suspend fun countryWithCode(isoA2: String): Country? = _countryWithCode(isoA2)?.toModel()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertCountries(countries: List<CountryRow>): Unit
    suspend fun insertCountries(countries: List<Country>): Unit =
        _insertCountries(countries.map { it.toRow() })

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertBorders(borders: List<CountryBorderRow>): Unit
    suspend fun insertBorders(borders: List<CountryBorder>): Unit =
        _insertBorders(borders.map { it.toRow() })

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertBorderPosts(posts: List<BorderPostRow>): Unit
    suspend fun insertBorderPosts(posts: List<BorderPost>): Unit =
        _insertBorderPosts(posts.map { it.toRow() })

    @Query("SELECT * FROM zone")
    fun _observeZones(): Flow<List<ZoneRow>>
    fun observeZones(): Flow<List<Zone>> = _observeZones().map { rows -> rows.map { it.toModel() } }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertZones(zones: List<ZoneRow>): Unit
    suspend fun insertZones(zones: List<Zone>): Unit = _insertZones(zones.map { it.toRow() })

    @Query("SELECT * FROM setting WHERE documentId = :documentId LIMIT 1")
    suspend fun _setting(documentId: String): SettingRow?
    suspend fun setting(documentId: String): GlobalSetting? = _setting(documentId)?.toModel()

    @Query("SELECT * FROM setting WHERE documentId = :documentId LIMIT 1")
    fun _observeSetting(documentId: String): Flow<SettingRow?>
    fun observeSetting(documentId: String): Flow<GlobalSetting?> =
        _observeSetting(documentId).map { it?.toModel() }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insertSettings(settings: List<SettingRow>): Unit
    suspend fun insertSettings(settings: List<GlobalSetting>): Unit =
        _insertSettings(settings.map { it.toRow() })

    // ── Lookups for the Overlanding screens and links ───────────────────────

    @Query("SELECT * FROM country ORDER BY name")
    suspend fun _allCountries(): List<CountryRow>
    suspend fun allCountries(): List<Country> = _allCountries().map { it.toModel() }

    @Query("SELECT * FROM border")
    suspend fun _allBorders(): List<CountryBorderRow>
    suspend fun allBorders(): List<CountryBorder> = _allBorders().map { it.toModel() }

    @Query("SELECT * FROM border_post")
    suspend fun _allBorderPosts(): List<BorderPostRow>
    suspend fun allBorderPosts(): List<BorderPost> = _allBorderPosts().map { it.toModel() }

    @Query("SELECT * FROM zone")
    suspend fun _allZones(): List<ZoneRow>
    suspend fun allZones(): List<Zone> = _allZones().map { it.toModel() }

    /** A country by its ISO alpha-3 code, which is also its document id (case-insensitive). */
    @Query("SELECT * FROM country WHERE documentId = :code COLLATE NOCASE LIMIT 1")
    suspend fun _country(code: String): CountryRow?
    suspend fun country(code: String): Country? = _country(code)?.toModel()

    @Query("SELECT * FROM border WHERE documentId = :id LIMIT 1")
    suspend fun _border(id: String): CountryBorderRow?
    suspend fun border(id: String): CountryBorder? = _border(id)?.toModel()

    @Query("SELECT * FROM border_post WHERE documentId = :id LIMIT 1")
    suspend fun _borderPost(id: String): BorderPostRow?
    suspend fun borderPost(id: String): BorderPost? = _borderPost(id)?.toModel()

    @Query("SELECT * FROM zone WHERE documentId = :id LIMIT 1")
    suspend fun _zone(id: String): ZoneRow?
    suspend fun zone(id: String): Zone? = _zone(id)?.toModel()

    // ── Wholesale replacement by the `countries` asset ──────────────────────

    @Query("DELETE FROM country")
    suspend fun deleteCountries(): Unit

    @Query("DELETE FROM border")
    suspend fun deleteBorders(): Unit

    @Query("DELETE FROM border_post")
    suspend fun deleteBorderPosts(): Unit

    @Query("DELETE FROM zone")
    suspend fun deleteZones(): Unit

    /** Replaces the whole Overlanding dataset in one transaction (the remote set is authoritative). */
    @androidx.room.Transaction
    suspend fun replaceOverlandData(
        countries: List<Country>,
        borders: List<CountryBorder>,
        posts: List<BorderPost>,
        zones: List<Zone>,
    ) {
        deleteZones()
        deleteBorderPosts()
        deleteBorders()
        deleteCountries()
        insertCountries(countries)
        insertBorders(borders)
        insertBorderPosts(posts)
        insertZones(zones)
    }
}
