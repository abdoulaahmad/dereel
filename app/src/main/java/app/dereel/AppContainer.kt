package app.dereel

import android.app.Application
import android.util.Log
import app.dereel.browser.BrowserDataManager
import app.dereel.core.coordinator.ProtectionCoordinator
import app.dereel.core.grant.GrantManager
import app.dereel.core.pacing.PacingTracker
import app.dereel.core.usage.UsageTracker
import app.dereel.core.util.SerialWriter
import app.dereel.data.RoomGrantStore
import app.dereel.data.RoomPacingStore
import app.dereel.data.RoomUsageStore
import app.dereel.data.SettingsRepository
import app.dereel.data.db.DereelDatabase
import app.dereel.platform.AndroidClock
import app.dereel.status.StatusMonitor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Manual dependency container. One instance per process. The accessibility service and the
 * browser share it, so both use the same policy, usage and grant objects (DATA-02).
 */
class AppContainer(private val app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock = AndroidClock(app)

    private val database = DereelDatabase.build(app)

    // Logs record only the exception type, never content (SEC-06).
    private val writer = SerialWriter(appScope) { e -> Log.w(TAG, "Storage write failed: ${e.javaClass.simpleName}") }

    val settings = SettingsRepository(database.settingsDao(), appScope)
    val usage = UsageTracker(RoomUsageStore(database.usageDao()), clock, appScope, writer)
    val grants = GrantManager(RoomGrantStore(database.grantDao()), clock, writer)
    val pacing = PacingTracker(clock, RoomPacingStore(database.pacingDao()), writer)
    val coordinator = ProtectionCoordinator(settings.configs, grants, usage, clock, appScope, settings.preferences, pacing)
    val status = StatusMonitor(app, settings, clock, appScope)
    val browserData = BrowserDataManager()

    private val ready = CompletableDeferred<Unit>()

    init {
        appScope.launch {
            settings.awaitLoaded()
            usage.initialize()
            usage.applyRetention()
            grants.load()
            pacing.load()
            coordinator.start()
            coordinator.markReady()
            status.start()
            ready.complete(Unit)
        }
    }

    suspend fun awaitReady() = ready.await()

    /** FR-34: clears usage history. Today's allowance restarts from zero. */
    suspend fun clearUsageHistory() {
        usage.clearHistory()
        coordinator.reevaluateAll()
    }

    /** FR-35, SEC-07: removes Dereel-owned settings, grants, history and browser sessions. */
    suspend fun resetEverything() {
        usage.stopAll()
        grants.clearAll()
        pacing.clearAll()
        usage.clearHistory()
        settings.resetAll()
        withContext(Dispatchers.Main) { browserData.clearAll() }
        writer.flush()
        coordinator.reevaluateAll()
        status.refresh()
    }

    companion object {
        private const val TAG = "Dereel"
    }
}
