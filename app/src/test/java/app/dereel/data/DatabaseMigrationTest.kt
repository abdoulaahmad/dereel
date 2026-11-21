package app.dereel.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dereel.core.model.AllowanceSetting
import app.dereel.core.model.BrowserControl
import app.dereel.core.model.IntervalSetting
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.model.SleepSchedule
import app.dereel.data.db.DereelDatabase
import app.dereel.data.db.PacingStateEntity
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * DATA-01: upgrading keeps existing settings, history and grants. Each test builds a database
 * exactly as an older schema created it (from the exported schema file), fills it, and opens it
 * with the current app. Room then checks the migrated tables against the current schema, so a
 * wrong column type or default fails here.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DatabaseMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    private fun createFromExportedSchema(version: Int): SQLiteDatabase {
        val schema = JSONObject(File("schemas/app.dereel.data.db.DereelDatabase/$version.json").readText()).getJSONObject("database")
        val file = context.getDatabasePath(DB_NAME).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            val table = entity.getString("tableName")
            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                db.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
        }
        val setup = schema.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
        db.version = version
        return db
    }

    @Test
    fun schema1DataSurvivesTheUpgradeAndGetsDocumentedDefaults() = runBlocking {
        createFromExportedSchema(1).use { db ->
            db.execSQL(
                "INSERT INTO platform_config (platformId, selected, openingMethod, blockShortVideos, allowSharedVideos, " +
                    "allowanceEnabled, allowanceMinutes, browserControls, revision) " +
                    "VALUES ('instagram', 1, 'browser', 1, 0, 1, 15, 'ig_hide_explore', 7)",
            )
            db.execSQL("INSERT INTO app_preferences (id, onboardingComplete, temporaryAccessEnabled, nativeEverConnected) VALUES (1, 1, 1, 1)")
            db.execSQL(
                "INSERT INTO usage_segment (id, sessionId, platformId, method, startWallMs, endWallMs, elapsedMs, day, outcome, state) " +
                    "VALUES ('s1', 'x1', 'instagram', 'browser', 1000, 61000, 60000, 20000, 'ALLOWANCE', 'CLOSED')",
            )
            db.execSQL(
                "INSERT INTO temporary_grant (id, platformId, issuedWallMs, expiresWallMs, issuedMonotonicMs, bootId, state) " +
                    "VALUES ('g1', 'instagram', 1000, 301000, 50, 'boot', 'ACTIVE')",
            )
        }

        val room = Room.databaseBuilder(context, DereelDatabase::class.java, DB_NAME)
            .addMigrations(*DereelDatabase.MIGRATIONS)
            .build()
        try {
            val config = checkNotNull(room.settingsDao().config("instagram")?.toModel())
            assertEquals(Platform.INSTAGRAM, config.platform)
            assertTrue(config.selected)
            assertEquals(OpeningMethod.IN_DEREEL, config.openingMethod)
            assertFalse(config.allowSharedVideos)
            assertEquals(AllowanceSetting(enabled = true, minutes = 15), config.allowance)
            assertEquals(setOf(BrowserControl.INSTAGRAM_HIDE_EXPLORE), config.browserControls)
            assertEquals(7L, config.revision)
            // New in schema 2: off, with the default lengths ready to use.
            assertEquals(IntervalSetting(enabled = false, watchMinutes = 10, breakMinutes = 30), config.interval)

            val preferences = checkNotNull(room.settingsDao().preferences()?.toModel())
            assertTrue(preferences.onboardingComplete)
            assertTrue(preferences.temporaryAccessEnabled)
            assertTrue(preferences.nativeProtectionEverConnected)
            assertEquals(SleepSchedule(enabled = false, startMinute = 22 * 60, endMinute = 7 * 60), preferences.sleep)
            assertFalse(preferences.protectionAlerts)
            assertTrue(preferences.liveTimer)

            val segment = room.usageDao().fromDay(0).single()
            assertEquals(60_000L, segment.elapsedMs)
            assertEquals("g1", room.grantDao().active().single().id)

            // The new table exists and is usable.
            assertTrue(room.pacingDao().all().isEmpty())
            room.pacingDao().upsert(PacingStateEntity("instagram", 30_000, 0, 5_000))
            assertEquals(30_000L, room.pacingDao().all().single().usedMs)
        } finally {
            room.close()
        }
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
