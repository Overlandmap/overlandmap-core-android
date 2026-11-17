package ch.overlandmap.map.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import ch.overlandmap.map.planning.PlannedTrip

/**
 * A trip planned on the Overlanding map (iOS `trip` table, migration `v6_trip`). The columns are
 * what lists need; [json] holds the whole [PlannedTrip] in the web `PlannedRoute` shape (segments,
 * flags, colour), so a trip can be moved between the applications. Dates are seconds since the
 * epoch, as REAL columns like on iOS.
 */
@Entity(tableName = "trip")
data class TripRow(
    @PrimaryKey val id: String,
    val name: String,
    val color: String,
    val visible: Boolean,
    val distanceMeters: Double?,
    val createdAt: Double,
    val updatedAt: Double,
    val json: String,
)

fun PlannedTrip.toRow(createdAt: Double, updatedAt: Double) = TripRow(
    id = id,
    name = name,
    color = displayColor,
    visible = isVisible,
    distanceMeters = distanceMeters,
    createdAt = createdAt,
    updatedAt = updatedAt,
    json = toJson(),
)

/** Null when the stored JSON can no longer be read. */
fun TripRow.toModel(): PlannedTrip? = PlannedTrip.fromJson(json)
