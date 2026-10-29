package ch.overlandmap.map.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import ch.overlandmap.map.model.CheckIn
import ch.overlandmap.map.model.ContributedWaypoint
import ch.overlandmap.map.model.Discussion
import ch.overlandmap.map.model.Extra
import ch.overlandmap.map.model.Vote
import ch.overlandmap.map.model.without

/*
 * Social tables, column for column as in the iOS schema (Schema.swift). Each row keeps the
 * queryable columns and a `json` blob with the rest of the document; the models rebuild
 * themselves from the same Firestore-shaped map (`fromFirestore`), so what is cached is
 * read exactly as what was fetched.
 */

@Entity(
    tableName = "contributed_waypoint",
    indices = [
        Index("trackPackId", "active", name = "idx_contributed_trackPack"),
        Index("itinDocumentId", "active", name = "idx_contributed_itin"),
        Index("geohash", name = "idx_contributed_geohash"),
    ],
)
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

private val CONTRIBUTED_COLUMNS = arrayOf(
    "active", "trackPackId", "geohash", "type", "itinDocumentId", "lastUpdate", "userId",
)

fun ContributedWaypoint.toRow() = ContributedWaypointRow(
    documentId = documentId,
    active = if (isActive) 1 else 0,
    trackPackId = trackPackId,
    geohash = geohash,
    type = type,
    itinDocumentId = itinDocumentId,
    lastUpdate = lastUpdate,
    userId = userId,
    json = Extra.toJson(stored().without(*CONTRIBUTED_COLUMNS)),
)

fun ContributedWaypointRow.toModel(): ContributedWaypoint = ContributedWaypoint.fromFirestore(
    documentId,
    Extra.fromJson(json) + columns(
        "active" to active?.let { it != 0 }, "trackPackId" to trackPackId, "geohash" to geohash,
        "type" to type, "itinDocumentId" to itinDocumentId, "lastUpdate" to lastUpdate,
        "userId" to userId,
    ),
)

@Entity(
    tableName = "check_in",
    indices = [
        Index("objectId", name = "idx_checkin_object"),
        Index("trackPackId", name = "idx_checkin_trackPack"),
    ],
)
data class CheckInRow(
    @PrimaryKey val documentId: String,
    val objectId: String?,
    val trackPackId: String?,
    val itinDocumentId: String?,
    val reason: String?,
    val createdAt: Long?,
    val userId: String?,
    val json: String?,
)

private val CHECK_IN_COLUMNS = arrayOf(
    "objectId", "trackPackId", "itinDocumentId", "reason", "createdAt", "userId",
)

fun CheckIn.toRow() = CheckInRow(
    documentId = documentId,
    objectId = objectId.takeIf { it.isNotEmpty() },
    trackPackId = trackPackId,
    itinDocumentId = itinDocumentId,
    reason = reason.raw,
    createdAt = createdAt,
    userId = userId,
    json = Extra.toJson(stored().without(*CHECK_IN_COLUMNS)),
)

/** Geohash-linked check-ins retain their association in the JSON fields. */
fun CheckInRow.toModel(): CheckIn? = CheckIn.fromFirestore(
    documentId,
    Extra.fromJson(json) + columns(
        "objectId" to objectId, "trackPackId" to trackPackId, "itinDocumentId" to itinDocumentId,
        "reason" to reason, "createdAt" to createdAt, "userId" to userId,
    ),
)

@Entity(
    tableName = "vote",
    indices = [
        Index("objectId", name = "idx_vote_object"),
        Index("trackPackId", name = "idx_vote_trackPack"),
    ],
)
data class VoteRow(
    @PrimaryKey val documentId: String,
    val objectId: String?,
    val trackPackId: String?,
    val content: String?,
    val createdAt: Long?,
    val userId: String?,
    val upVote: Int?,
    val json: String?,
)

private val VOTE_COLUMNS = arrayOf(
    "objectId", "trackPackId", "content", "createdAt", "userId", "upVote",
)

fun Vote.toRow() = VoteRow(
    documentId = documentId,
    objectId = objectId,
    trackPackId = trackPackId,
    content = content,
    createdAt = createdAt,
    userId = userId,
    upVote = if (isUpVote) 1 else 0,
    json = Extra.toJson(stored().without(*VOTE_COLUMNS)),
)

fun VoteRow.toModel(): Vote = Vote.fromFirestore(
    documentId,
    Extra.fromJson(json) + columns(
        "objectId" to objectId, "trackPackId" to trackPackId, "content" to content,
        "createdAt" to createdAt, "userId" to userId, "upVote" to upVote,
    ),
)

@Entity(
    tableName = "discussion",
    indices = [Index("objectId", name = "idx_discussion_object")],
)
data class DiscussionRow(
    @PrimaryKey val documentId: String,
    val objectId: String?,
    val topic: String?,
    val geohash: String?,
    val createdAt: Long?,
    val userId: String?,
    val json: String?,
)

private val DISCUSSION_COLUMNS = arrayOf("objectId", "geohash", "createdAt", "userId")

fun Discussion.toRow() = DiscussionRow(
    documentId = documentId,
    objectId = objectId,
    topic = topic.raw,
    geohash = geohash,
    createdAt = createdAt,
    userId = userId,
    json = Extra.toJson(stored().without(*DISCUSSION_COLUMNS)),
)

fun DiscussionRow.toModel(): Discussion = Discussion.fromFirestore(
    documentId,
    Extra.fromJson(json) + columns(
        "objectId" to objectId, "geohash" to geohash, "createdAt" to createdAt,
        "userId" to userId,
    ),
)

@Entity(
    tableName = "climate",
    indices = [Index("geohash", name = "idx_climate_geohash")],
)
data class ClimateRow(
    @PrimaryKey val documentId: String,
    val geohash: String?,
    val month: Int?,
    val updatedAt: Long?,
    val json: String?,
)

/**
 * Per-pack, per-kind sync timestamps (epoch millis): when comments, check-ins, votes, …
 * of a pack were last fetched. Replaces the single 24-hour stamp of the old `social_sync`.
 */
@Entity(tableName = "sync_state", primaryKeys = ["trackPackId", "kind"])
data class SyncStateRow(
    val trackPackId: String,
    val kind: String,
    val lastSync: Long?,
)
