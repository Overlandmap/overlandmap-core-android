package ch.overlandmap.map.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Key/value rows describing the database file itself; `version` is the schema generation. */
@Entity(tableName = "schema_meta")
data class SchemaMetaRow(
    @PrimaryKey val name: String,
    val value: String,
)

/**
 * A queued or finished download, persisted so the queue can be reconciled after the app is
 * killed and relaunched (iOS `download_item`). [status] is one of the `DownloadStatus` names.
 */
@Entity(
    tableName = "download_item",
    indices = [
        Index("trackPackId", name = "idx_download_trackPack"),
        Index("status", name = "idx_download_status"),
    ],
)
data class DownloadItemRow(
    @PrimaryKey val id: String,
    val trackPackId: String?,
    val kind: String,
    val url: String?,
    val assetId: String?,
    val expectedBytes: Long?,
    val receivedBytes: Long?,
    val status: String,
    val error: String?,
    val localPath: String?,
    val sessionTaskId: Int?,
    val updatedAt: Long?,
)
