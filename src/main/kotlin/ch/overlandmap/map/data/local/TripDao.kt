package ch.overlandmap.map.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import ch.overlandmap.map.planning.PlannedTrip

/** Persistence of the trips planned on the Overlanding map (iOS `TripStore`). */
@Dao
interface TripDao {

    @Query("SELECT * FROM trip ORDER BY createdAt, id")
    suspend fun _allTrips(): List<TripRow>

    /** All trips, oldest first. */
    suspend fun allTrips(): List<PlannedTrip> = _allTrips().mapNotNull { it.toModel() }

    @Query("SELECT createdAt FROM trip WHERE id = :id")
    suspend fun _createdAt(id: String): Double?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun _insert(row: TripRow)

    /** Inserts or updates a trip. The creation date of an existing trip is kept. */
    @Transaction
    suspend fun saveTrip(trip: PlannedTrip) {
        val now = System.currentTimeMillis() / 1000.0
        _insert(trip.toRow(createdAt = _createdAt(trip.id) ?: now, updatedAt = now))
    }

    @Query("DELETE FROM trip WHERE id = :id")
    suspend fun deleteTrip(id: String)
}
