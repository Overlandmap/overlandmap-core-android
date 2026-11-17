@file:Suppress("DEPRECATION")

package ch.overlandmap.map.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The offline-first SQLite database: downloaded track packs plus the cached
 * world and social data. Every table keeps only the columns its queries need
 * and stores the rest of each object in a `json` blob (see LibraryRows /
 * WorldRows), so models can gain fields without a schema migration.
 */
@Database(
    entities = [
        TrackPackRow::class, ItineraryRow::class, ItineraryStepRow::class,
        TrackRow::class, WaypointRow::class, SidebarRow::class, CommentRow::class,
        PackAssetRow::class, CountryRow::class, CountryBorderRow::class, BorderPostRow::class,
        ContributedWaypointRow::class, CheckInRow::class, VoteRow::class,
        ClimateRow::class, DiscussionRow::class, SyncStateRow::class, ZoneRow::class,
        SettingRow::class, SchemaMetaRow::class, DownloadItemRow::class, TripRow::class,
    ],
    version = 16,
    // The schema JSON (core/schemas) is what the golden-schema test compares with iOS.
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun worldDao(): WorldDao
    abstract fun nearbyDao(): NearbyDao
    abstract fun socialDao(): SocialDao
    abstract fun tripDao(): TripDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** The current schema version this build of the app expects. */
        const val CURRENT_VERSION = 16

        /**
         * The minimum on-disk version we can migrate from without data loss.
         * Databases older than this require a destructive reset (user consent).
         * Version 16 only added the `trip` table ([MIGRATION_15_16]), so 15 is still compatible.
         */
        const val MIN_COMPATIBLE_VERSION = 15

        /**
         * Adds the `trip` table for the trips planned on the Overlanding map. Additive, like iOS's
         * `v6_trip`: the downloaded packs and the cached world data are kept.
         */
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `trip` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, " +
                        "`color` TEXT NOT NULL, `visible` INTEGER NOT NULL, `distanceMeters` REAL, " +
                        "`createdAt` REAL NOT NULL, `updatedAt` REAL NOT NULL, `json` TEXT NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        /** Database file name, shared with [onDiskVersion]. */
        private const val DB_NAME = "overlandmap.db"

        /**
         * Reads the SQLite `user_version` pragma from the database file
         * without opening it through Room. Returns 0 if the file does not
         * exist or cannot be read.
         */
        fun onDiskVersion(context: Context): Int {
            val dbFile = context.getDatabasePath(DB_NAME)
            if (!dbFile.exists()) return 0
            return try {
                val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    dbFile.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
                )
                val version = db.version
                db.close()
                version
            } catch (_: Exception) {
                0
            }
        }

        /**
         * True when a database file exists but is older than
         * [MIN_COMPATIBLE_VERSION] — i.e. it cannot be opened without a
         * destructive wipe. A missing file (version 0) or a compatible one
         * returns false. Checked before any Room access so the UI can warn the
         * user and erase with consent instead of crashing on open.
         */
        fun needsDestructiveReset(context: Context): Boolean =
            onDiskVersion(context) in 1 until MIN_COMPATIBLE_VERSION

        /** Deletes the database file(s) so Room recreates from scratch. */
        fun deleteDatabase(context: Context) {
            instance?.close()
            instance = null
            context.deleteDatabase(DB_NAME)
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(context.applicationContext, AppDatabase::class.java, "overlandmap.db")
                    // Version 15 -> 16 only adds a table and is migrated in place.
                    .addMigrations(MIGRATION_15_16)
                    // The column+json schema is a clean break from the old
                    // per-field columns; there is no other upgrade path, so a
                    // stale database (any older version) is dropped and
                    // re-downloaded.
                    // `dropAllTables = true` is required in Room 2.7+: the
                    // deprecated no-arg form doesn't reliably fire on upgrades
                    // (e.g. an OPPO device carrying a v12 db crashed with
                    // "migration from 12 to 13 was required but not found").
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    // The full-text index spans every type and language, so it
                    // lives in raw FTS4 tables Room doesn't model; create them
                    // with the database (and defensively on every open).
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            FtsIndex.createTables(db)
                            // Stamp the schema generation, as iOS does in `schema_meta`.
                            db.execSQL(
                                "INSERT OR REPLACE INTO schema_meta (name, value) VALUES ('version', ?)",
                                arrayOf(CURRENT_VERSION.toString()),
                            )
                        }

                        override fun onOpen(db: SupportSQLiteDatabase) =
                            FtsIndex.createTables(db)
                    })
                    .build()
                    .also { instance = it }
            }
    }
}
