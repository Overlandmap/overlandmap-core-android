package ch.overlandmap.map.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import ch.overlandmap.map.model.BorderPost
import ch.overlandmap.map.model.Comment
import ch.overlandmap.map.model.Country
import ch.overlandmap.map.model.CountryBorder
import ch.overlandmap.map.model.Itinerary
import ch.overlandmap.map.model.ItineraryStep
import ch.overlandmap.map.model.PackAsset
import ch.overlandmap.map.model.Sidebar
import ch.overlandmap.map.model.Track
import ch.overlandmap.map.model.TrackPack
import ch.overlandmap.map.model.Waypoint

/**
 * The offline-first SQLite database: downloaded track packs plus the cached
 * world data (countries, borders, border posts).
 */
@Database(
    entities = [
        TrackPack::class, Itinerary::class, ItineraryStep::class,
        Track::class, Waypoint::class, Sidebar::class, Comment::class,
        Country::class, CountryBorder::class, BorderPost::class, PackAsset::class,
    ],
    version = 9,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
    abstract fun worldDao(): WorldDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v6: the pack update check (zip asset reference, needs-update flag). */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE track_pack ADD COLUMN trackPackZip TEXT")
                db.execSQL("ALTER TABLE track_pack ADD COLUMN needsUpdate INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v7: per-pack asset catalogue for the Downloads screen. */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pack_asset (" +
                        "trackPackId TEXT NOT NULL, kind TEXT NOT NULL, assetId TEXT NOT NULL, " +
                        "name TEXT NOT NULL, fileSizeBytes INTEGER NOT NULL, " +
                        "PRIMARY KEY(trackPackId, kind))"
                )
            }
        }

        /** v8: the sidebar title-photo caption. */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sidebar ADD COLUMN titlePhotoCaption TEXT")
            }
        }

        /** v9: per-step point-of-interest flags (fuel, hotel, police checkpoint). */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE itinerary_step ADD COLUMN hasFuel INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE itinerary_step ADD COLUMN hasHotel INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "ALTER TABLE itinerary_step ADD COLUMN isPoliceCheckpoint " +
                        "INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(context.applicationContext, AppDatabase::class.java, "overlandmap.db")
                    .addMigrations(MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
