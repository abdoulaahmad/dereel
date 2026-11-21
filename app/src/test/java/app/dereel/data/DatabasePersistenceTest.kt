package app.dereel.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.dereel.core.coordinator.ProtectionCoordinator
import app.dereel.core.coordinator.SurfaceObservation
import app.dereel.core.grant.GrantManager
import app.dereel.core.grant.GrantState
import app.dereel.core.model.AllowanceSetting
import app.dereel.core.model.BrowserControl
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.policy.SurfaceKind
import app.dereel.core.time.DeviceClock
import app.dereel.core.usage.SegmentState
import app.dereel.core.usage.UsageTracker
import app.dereel.core.util.SerialWriter
import app.dereel.data.db.DereelDatabase
import app.dereel.data.db.PlatformConfigEntity
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** NFR-10, AT-12, AT-22: settings and measured usage survive restarts through the real Room schema. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DatabasePersistenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val scopes = mutableListOf<CoroutineScope>()

    private class FakeClock : DeviceClock {
        var elapsed = 0L
        override fun monotonicMs() = 1_000_000 + elapsed
        override fun wallMs() = 1_795_000_000_000L + elapsed
        override fun zone(): ZoneId = ZoneOffset.UTC
        override fun bootId() = "boot"
    }

    private fun open(): DereelDatabase = Room.databaseBuilder(context, DereelDatabase::class.java, DB_NAME)
        .addMigrations(*DereelDatabase.MIGRATIONS)
        .build()

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun settingsAndPreferencesSurviveRestart() = runBlocking {
        val first = open()
        val repo = SettingsRepository(first.settingsDao(), newScope())
        repo.updatePlatform(Platform.INSTAGRAM) {
            it.copy(
                selected = true,
                openingMethod = OpeningMethod.IN_DEREEL,
                allowSharedVideos = false,
                allowance = AllowanceSetting().enable(15).disable(),
                browserControls = setOf(BrowserControl.INSTAGRAM_HIDE_EXPLORE),
            )
        }
        repo.updatePreferences { it.copy(onboardingComplete = true, temporaryAccessEnabled = true) }
        first.close()

        val second = open()
        val reopened = SettingsRepository(second.settingsDao(), newScope())
        reopened.awaitLoaded()
        val config = reopened.configs.first { it.isNotEmpty() }.getValue(Platform.INSTAGRAM)
        assertTrue(config.selected)
        assertEquals(OpeningMethod.IN_DEREEL, config.openingMethod)
        assertEquals(false, config.allowSharedVideos)
        assertEquals("disabled allowance keeps its limit (POL-07)", AllowanceSetting(enabled = false, minutes = 15), config.allowance)
        assertEquals(setOf(BrowserControl.INSTAGRAM_HIDE_EXPLORE), config.browserControls)
        assertEquals(1L, config.revision)
        val prefs = reopened.preferences.first { it != null }!!
        assertTrue(prefs.onboardingComplete && prefs.temporaryAccessEnabled)
        assertEquals("unconfigured platforms use defaults", false, reopened.configs.value.getValue(Platform.YOUTUBE).selected)
        second.close()
    }

    @Test
    fun invalidStoredValuesFallBackSafely() = runBlocking {
        val db = open()
        db.settingsDao().upsertConfig(
            PlatformConfigEntity("instagram", true, "teleport", true, true, true, 999, "ig_hide_explore,yt_hide_home_feed,bogus", 3),
        )
        val config = db.settingsDao().config("instagram")!!.toModel()!!
        assertNull(config.openingMethod)
        assertEquals(AllowanceSetting(), config.allowance)
        assertEquals(setOf(BrowserControl.INSTAGRAM_HIDE_EXPLORE), config.browserControls)
        db.close()
    }

    @Test
    fun usageRecoversFromRealDatabaseAfterProcessDeath() = runBlocking {
        val clock = FakeClock()
        val firstDb = open()
        val firstScope = newScope()
        val tracker = UsageTracker(RoomUsageStore(firstDb.usageDao()), clock, firstScope, SerialWriter(firstScope), checkpointIntervalMs = 60_000)
        tracker.start(Platform.YOUTUBE, OpeningMethod.NATIVE_APP, app.dereel.core.usage.SessionOutcome.BLOCKING_OFF)
        clock.elapsed += 7_000
        tracker.checkpoint()
        tracker.flush()
        clock.elapsed += 3_000 // measured but never checkpointed: lost on process death
        firstScope.cancel()
        firstDb.close()

        clock.elapsed += 120_000 // Dereel not running
        val secondDb = open()
        val secondScope = newScope()
        val recovered = UsageTracker(RoomUsageStore(secondDb.usageDao()), clock, secondScope, SerialWriter(secondScope))
        recovered.initialize()
        assertEquals(7_000, recovered.usedTodayMs(Platform.YOUTUBE))
        val rows = secondDb.usageDao().fromDay(0)
        assertEquals(SegmentState.RECOVERED.name, rows.single().state)
        secondDb.close()
    }

    @Test
    fun grantsPersistAndSharedBalanceIsEnforcedAcrossRestart() = runBlocking {
        val clock = FakeClock()
        val db = open()
        val scope = newScope()
        val writer = SerialWriter(scope)
        val grants = GrantManager(RoomGrantStore(db.grantDao()), clock, writer)
        val issued = grants.request(Platform.INSTAGRAM)
        writer.flush()
        db.close()

        val reopened = open()
        val scope2 = newScope()
        val writer2 = SerialWriter(scope2)
        val grants2 = GrantManager(RoomGrantStore(reopened.grantDao()), clock, writer2)
        grants2.load()
        assertEquals(issued.grant.id, grants2.activeGrant(Platform.INSTAGRAM)?.id)

        val repo = SettingsRepository(reopened.settingsDao(), scope2)
        repo.updatePlatform(Platform.INSTAGRAM) { it.copy(selected = true) }
        repo.awaitLoaded()
        repo.configs.first { it[Platform.INSTAGRAM]?.selected == true }
        val usage = UsageTracker(RoomUsageStore(reopened.usageDao()), clock, scope2, writer2)
        usage.initialize()
        val coordinator = ProtectionCoordinator(repo.configs, grants2, usage, clock, scope2)
        coordinator.markReady()
        val decision = coordinator.report(Platform.INSTAGRAM, OpeningMethod.NATIVE_APP, SurfaceObservation(SurfaceKind.SHORT_VIDEO))
        assertTrue("restored grant still applies", decision is app.dereel.core.policy.PolicyDecision.Allow)

        clock.elapsed += 6 * 60_000
        assertEquals(setOf(Platform.INSTAGRAM), grants2.expireDue())
        writer2.flush()
        assertTrue("expired grant is no longer stored as active", reopened.grantDao().active().isEmpty())
        assertEquals(GrantState.EXPIRED, RoomGrantStore(reopened.grantDao()).loadActive().firstOrNull()?.state ?: GrantState.EXPIRED)
        reopened.close()
    }

    companion object {
        private const val DB_NAME = "dereel-test.db"
    }
}
