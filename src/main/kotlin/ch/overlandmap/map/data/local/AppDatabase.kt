@file:Suppress("DEPRECATION")

package ch.overlandmap.map.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
        ClimateRow::class, DiscussionRow::class, SocialSyncRow::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun worldDao(): WorldDao
    abstract fun socialDao(): SocialDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** The current schema version this build of the app expects. */
        const val CURRENT_VERSION = 13

        /**
         * The minimum on-disk version we can migrate from without data loss.
         * Databases older than this require a destructive reset (user consent).
         */
        const val MIN_COMPATIBLE_VERSION = 13

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
                    // The column+json schema is a clean break from the old
                    // per-field columns; there is no upgrade path, so a stale
                    // database is discarded and re-downloaded.
                    .fallbackToDestructiveMigration()
                    .fallbackToDestructiveMigrationOnDowngrade()
                    // The full-text index spans every type and language, so it
                    // lives in raw FTS4 tables Room doesn't model; create them
                    // with the database (and defensively on every open).
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) =
                            FtsIndex.createTables(db)

                        override fun onOpen(db: SupportSQLiteDatabase) =
                            FtsIndex.createTables(db)
                    })
                    .build()
                    .also { instance = it }
            }
    }
}
