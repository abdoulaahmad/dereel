package app.dereel.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.dereel.AppContainer
import app.dereel.browser.BrowserDataManager
import app.dereel.core.grant.GrantIssued
import app.dereel.core.model.AllowanceSetting
import app.dereel.core.model.AppPreferences
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.model.PlatformConfig
import android.content.Context
import app.dereel.core.model.SleepSchedule
import app.dereel.core.nativeapp.NativeRules
import app.dereel.core.pacing.IntervalStatus
import app.dereel.core.status.CheckItem
import app.dereel.core.status.ProtectionCheck
import app.dereel.nativeapp.DereelAccessibilityService
import app.dereel.status.ProtectionAlerts
import app.dereel.core.status.EnvironmentSnapshot
import app.dereel.core.status.MethodHealth
import app.dereel.core.status.OverallStatus
import app.dereel.core.status.StatusEvaluator
import app.dereel.core.usage.DayUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PlatformUi(
    val config: PlatformConfig,
    /** Empty until the first live status check completes. Status is never assumed (UX-08). */
    val health: List<MethodHealth>,
    val appInstalled: Boolean,
    val usedTodayMs: Long,
    val grantRemainingMs: Long,
    /** Where the interval timer stands for this platform (S-02). */
    val interval: IntervalStatus = IntervalStatus.Off,
) {
    val platform: Platform get() = config.platform

    /** A daily allowance or an interval timer lets some short-video time through. */
    val hasTimeLimit: Boolean get() = config.allowance.limitMs != null || config.interval.enabled
}

data class MainState(
    val loaded: Boolean,
    val preferences: AppPreferences,
    val environment: EnvironmentSnapshot?,
    val platforms: List<PlatformUi>,
    val overall: OverallStatus,
    val week: List<DayUsage>,
    val today: Long,
    /** When the sleep schedule ends, if it is in force right now (S-02). */
    val sleepUntilWallMs: Long? = null,
) {
    fun platform(platform: Platform): PlatformUi = platforms.first { it.platform == platform }
    val selected: List<PlatformUi> get() = platforms.filter { it.config.selected }

    companion object {
        val Loading = MainState(false, AppPreferences(), null, emptyList(), OverallStatus.NOTHING_SELECTED, emptyList(), 0)
    }
}

/** Initial rules chosen during setup (UX-06). */
data class PlatformDraft(
    val blockShortVideos: Boolean = true,
    val allowSharedVideos: Boolean = true,
    val allowance: AllowanceSetting = AllowanceSetting(),
    val openingMethod: OpeningMethod? = null,
)

class MainViewModel(private val container: AppContainer) : ViewModel() {
    // Refreshes countdowns and live usage once a second, only while the UI is subscribed.
    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(1_000)
        }
    }

    val state: StateFlow<MainState> = combine(
        container.settings.configs,
        container.settings.preferences,
        container.status.environment,
        container.grants.active,
        combine(container.usage.revision, ticker) { revision, _ -> revision },
    ) { configs, preferences, environment, _, _ ->
        build(configs, preferences, environment)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MainState.Loading)

    private fun build(
        configs: Map<Platform, PlatformConfig>,
        preferences: AppPreferences?,
        environment: EnvironmentSnapshot?,
    ): MainState {
        if (configs.isEmpty() || preferences == null) return MainState.Loading
        val platforms = Platform.entries.map { platform ->
            val config = configs.getValue(platform)
            val grant = container.grants.remainingMs(platform)
            PlatformUi(
                config = config,
                health = environment?.let { StatusEvaluator.platformHealth(config, it, grant) }.orEmpty(),
                appInstalled = environment?.installedApps?.contains(platform) == true,
                usedTodayMs = container.usage.usedTodayMs(platform),
                grantRemainingMs = grant,
                interval = if (config.selected) container.pacing.status(platform, config.interval) else IntervalStatus.Off,
            )
        }
        val today = container.clock.read().day
        val selectedHealth = platforms.filter { it.config.selected }.flatMap { it.health }
        return MainState(
            loaded = true,
            preferences = preferences,
            environment = environment,
            platforms = platforms,
            overall = if (environment == null) OverallStatus.NOTHING_SELECTED else StatusEvaluator.overall(selectedHealth),
            week = container.usage.summary(today - 6, today).reversed(),
            today = today,
            sleepUntilWallMs = container.clock.read().let { now -> preferences.sleep.endsInMs(now)?.let { now.wallMs + it } },
        )
    }

    fun refreshStatus() = container.status.refresh()

    fun completeOnboarding(drafts: Map<Platform, PlatformDraft>) = write {
        for ((platform, draft) in drafts) {
            container.settings.updatePlatform(platform) {
                it.copy(
                    selected = true,
                    blockShortVideos = draft.blockShortVideos,
                    allowSharedVideos = draft.allowSharedVideos,
                    allowance = draft.allowance,
                    openingMethod = draft.openingMethod ?: it.openingMethod,
                )
            }
        }
        container.settings.updatePreferences { it.copy(onboardingComplete = true) }
    }

    fun updatePlatform(platform: Platform, transform: (PlatformConfig) -> PlatformConfig) = write {
        container.settings.updatePlatform(platform, transform)
    }

    fun addPlatform(platform: Platform) = updatePlatform(platform) { it.copy(selected = true) }

    /** FR-05: stops enforcement and measurement. History is kept until explicitly cleared. */
    fun removePlatform(platform: Platform) = write {
        container.grants.end(platform)
        container.settings.updatePlatform(platform) { it.copy(selected = false) }
    }

    fun setTemporaryAccessEnabled(enabled: Boolean) = write {
        container.settings.updatePreferences { it.copy(temporaryAccessEnabled = enabled) }
        if (!enabled) Platform.entries.forEach { container.grants.end(it) }
    }

    fun setProtectionAlerts(enabled: Boolean) = write {
        container.settings.updatePreferences { it.copy(protectionAlerts = enabled) }
    }

    fun setLiveTimer(enabled: Boolean) = write {
        container.settings.updatePreferences { it.copy(liveTimer = enabled) }
    }

    fun updateSleep(transform: (SleepSchedule) -> SleepSchedule) = write {
        container.settings.updatePreferences { it.copy(sleep = transform(it.sleep)) }
    }

    /** S-02 "Check protection": a fresh look at the real state, not the last one shown (FR-36). */
    suspend fun runProtectionCheck(context: Context): List<CheckItem> = withContext(Dispatchers.Default) {
        val env = container.status.checkNow()
        ProtectionCheck.run(
            configs = container.settings.configs.value,
            preferences = container.settings.preferences.value ?: AppPreferences(),
            env = env,
            probe = if (env.accessibilityEnabled) DereelAccessibilityService.probe() else null,
            expectedPackages = NativeRules.all.map { it.packageName }.toSet(),
            notificationsAllowed = ProtectionAlerts.canPost(context),
        )
    }

    fun requestTemporaryAccess(platform: Platform): GrantIssued = container.grants.request(platform)

    fun endTemporaryAccess(platform: Platform) {
        container.grants.end(platform)
    }

    fun clearHistory() = write { container.clearUsageHistory() }

    suspend fun clearBrowserData(platform: Platform): BrowserDataManager.ClearResult =
        withContext(Dispatchers.Main) { container.browserData.clearPlatform(platform) }

    fun resetEverything(onDone: () -> Unit) {
        container.appScope.launch {
            container.resetEverything()
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    /** Writes run in the app scope so they finish even if this screen goes away. */
    private fun write(block: suspend () -> Unit) {
        container.appScope.launch { block() }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { MainViewModel(container) }
        }
    }
}
