package ch.overlandmap.map.data.local

import androidx.room.Dao
import androidx.room.Query

/**
 * Access to the social tables (check-ins, votes, discussions). Currently used
 * only by debug tools; add further queries here as features land.
 */
@Dao
interface SocialDao {

    @Query("SELECT * FROM check_in ORDER BY createdAt DESC LIMIT 500")
    suspend fun latestCheckIns(): List<CheckInRow>

    @Query("DELETE FROM check_in")
    suspend fun deleteAllCheckIns()
}
