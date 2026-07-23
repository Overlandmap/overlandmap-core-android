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
        ClimateRow::class, DiscussionRow::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun worldDao(): WorldDao
    abstract fun socialDao(): SocialDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

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
