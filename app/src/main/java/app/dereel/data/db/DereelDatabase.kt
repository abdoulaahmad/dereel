package app.dereel.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * App-private local storage for settings, grants and usage (DATA-01).
 *
 * The schema is exported to app/schemas. A version change must add a Migration and a case in
 * DatabaseMigrationTest. Destructive fallback is deliberately not enabled, because it would
 * silently delete settings and history.
 */
@Database(
    entities = [
        PlatformConfigEntity::class,
        AppPreferencesEntity::class,
        UsageSegmentEntity::class,
        TemporaryGrantEntity::class,
        PacingStateEntity::class,
    ],
    version = DereelDatabase.VERSION,
    exportSchema = true,
)
abstract class DereelDatabase : RoomDatabase() {
    abstract fun settingsDao(): SettingsDao
    abstract fun usageDao(): UsageDao
    abstract fun grantDao(): GrantDao
    abstract fun pacingDao(): PacingDao

    companion object {
        const val NAME = "dereel.db"
        const val VERSION = 2

        /**
         * Schema 2 (scope change S-02): interval timer per platform, sleep schedule, protection
         * alerts and the live timer preference, plus interval-timer progress. Existing rows keep
         * their values and get the documented defaults for the new columns.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE platform_config ADD COLUMN intervalEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE platform_config ADD COLUMN intervalWatchMinutes INTEGER NOT NULL DEFAULT 10")
                db.execSQL("ALTER TABLE platform_config ADD COLUMN intervalBreakMinutes INTEGER NOT NULL DEFAULT 30")
                db.execSQL("ALTER TABLE app_preferences ADD COLUMN sleepEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_preferences ADD COLUMN sleepStartMinute INTEGER NOT NULL DEFAULT 1320")
                db.execSQL("ALTER TABLE app_preferences ADD COLUMN sleepEndMinute INTEGER NOT NULL DEFAULT 420")
                db.execSQL("ALTER TABLE app_preferences ADD COLUMN protectionAlerts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_preferences ADD COLUMN liveTimer INTEGER NOT NULL DEFAULT 1")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pacing_state (platformId TEXT NOT NULL, usedMs INTEGER NOT NULL, " +
                        "breakUntilWallMs INTEGER NOT NULL, lastViewedWallMs INTEGER NOT NULL, PRIMARY KEY(platformId))",
                )
            }
        }

        /** Migrations in order, one per schema version step. */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun build(context: Context): DereelDatabase =
            Room.databaseBuilder(context.applicationContext, DereelDatabase::class.java, NAME)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}
