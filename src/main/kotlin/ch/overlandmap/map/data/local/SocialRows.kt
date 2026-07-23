package ch.overlandmap.map.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/*
 * Social tables from the Flutter schema, created for parity so the backend's
 * user-generated content (contributions, check-ins, votes, climate,
 * discussions) has a home.
 */

/**
 * Records when the social data (check-ins, votes, comments, contributed
 * waypoints) of a track pack was last synced from Firestore. Used to skip
 * re-fetching if less than 24 hours have elapsed.
 */
@Entity(tableName = "social_sync")
data class SocialSyncRow(
    @PrimaryKey val trackPackId: String,
    val lastSyncedAt: Long,
)

@Entity(tableName = "contributed_waypoint")
data class ContributedWaypointRow(
    @PrimaryKey val documentId: String,
    val active: Int?,
    val trackPackId: String?,
    val geohash: String?,
    val type: String?,
    val itinDocumentId: String?,
    val lastUpdate: Long?,
    val userId: String?,
    val json: String?,
)

@Entity(tableName = "check_in")
data class CheckInRow(
    @PrimaryKey val documentId: String,
    val objectId: String?,
    val trackPackId: String?,
    val itinDocumentId: String?,
    val collection: String?,
    val reason: String?,
    val content: String?,
    val createdAt: Long?,
    val userId: String?,
    val userName: String?,
    val json: String?,
)

@Entity(tableName = "vote")
data class VoteRow(
    @PrimaryKey val documentId: String,
    val objectId: String?,
    val trackPackId: String?,
    val content: String?,
    val createdAt: Long?,
    val userId: String?,
    val userName: String?,
    val upVote: Int?,
)

@Entity(tableName = "climate")
data class ClimateRow(
    @PrimaryKey val documentId: String,
    val geohash: String?,
    val month: Int?,
    val updatedAt: Long?,
    val json: String?,
)

@Entity(tableName = "discussion")
data class DiscussionRow(
    @PrimaryKey val documentId: String,
    val objectId: String?,
    val topic: String?,
    val geohash: String?,
    val createdAt: Long?,
    val userId: String?,
    val json: String?,
)
