package app.dereel.core.coordinator

import app.dereel.core.grant.GrantManager
import app.dereel.core.model.AppPreferences
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.model.PlatformConfig
import app.dereel.core.pacing.PacingTracker
import app.dereel.core.policy.AllowReason
import app.dereel.core.policy.DenyReason
import app.dereel.core.policy.PolicyDecision
import app.dereel.core.policy.PolicyEngine
import app.dereel.core.policy.PolicyInput
import app.dereel.core.policy.SurfaceKind
import app.dereel.core.policy.UnavailableReason
import app.dereel.core.shared.SharedItemMatch
import app.dereel.core.time.DayBuckets
import app.dereel.core.time.DeviceClock
import app.dereel.core.usage.SessionOutcome
import app.dereel.core.usage.UsageTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Identifies one enforcement method for one platform. */
data class MethodKey(val platform: Platform, val method: OpeningMethod)

/** What an adapter currently sees on screen for its method. */
data class SurfaceObservation(
    val surface: SurfaceKind,
    val sharedItem: SharedItemMatch? = null,
)

/**
 * The single entry point both adapter types use for policy and usage (DATA-02).
 *
 * Adapters report what is visible. The coordinator evaluates the shared policy, starts or stops
 * usage measurement, and re-evaluates without a new adapter event when:
 * - an allowance runs out during viewing (FR-27),
 * - a temporary grant expires (FR-29),
 * - settings change (POL-06),
 * - local midnight resets an exhausted allowance (FR-28),
 * - an interval-timer window or break ends, or the sleep schedule starts or ends (S-02).
 *
 * Adapters observe [decisions] to react to those re-evaluations.
 */
class ProtectionCoordinator(
    private val configs: StateFlow<Map<Platform, PlatformConfig>>,
    private val grants: GrantManager,
    private val usage: UsageTracker,
    private val clock: DeviceClock,
    private val scope: CoroutineScope,
    private val preferences: StateFlow<AppPreferences?> = MutableStateFlow(AppPreferences()),
    private val pacing: PacingTracker = PacingTracker(clock),
) {
    private val lock = Any()

    /** Insertion order is report order. The latest reporter for a platform owns its usage session. */
    private val observations = LinkedHashMap<MethodKey, SurfaceObservation>()
    private val unavailableMethods = HashSet<MethodKey>()
    private var ready = false
    private var timer: Job? = null

    private val _decisions = MutableStateFlow<Map<MethodKey, PolicyDecision>>(emptyMap())
    val decisions: StateFlow<Map<MethodKey, PolicyDecision>> = _decisions.asStateFlow()

    /** Starts watching settings and grants. Call once. */
    fun start() {
        scope.launch { configs.collect { reevaluateAll() } }
        scope.launch { grants.active.collect { reevaluateAll() } }
        scope.launch { preferences.collect { reevaluateAll() } }
    }

    /** Settings, usage and grants have loaded. Until then, short-video decisions are STARTING. */
    fun markReady() {
        synchronized(lock) { ready = true }
        reevaluateAll()
    }

    /** Evaluates without side effects, for example before a browser navigation commits. */
    fun evaluate(platform: Platform, method: OpeningMethod, observation: SurfaceObservation): PolicyDecision =
        synchronized(lock) { evaluateLocked(MethodKey(platform, method), observation) }

    /** Records what is visible for a method, applies usage accounting and returns the decision. */
    fun report(platform: Platform, method: OpeningMethod, observation: SurfaceObservation): PolicyDecision =
        synchronized(lock) {
            val key = MethodKey(platform, method)
            observations.remove(key)
            observations[key] = observation
            val decision = applyLocked(key, observation)
            scheduleLocked()
            decision
        }

    /** Nothing from this method is visible any more: app left, screen off, browser closed. */
    fun clear(platform: Platform, method: OpeningMethod) = synchronized(lock) {
        val key = MethodKey(platform, method)
        observations.remove(key)
        usage.stop(platform, method)
        _decisions.update { it - key }
        updatePacingLocked(platform)
        scheduleLocked()
    }

    fun clearMethod(method: OpeningMethod) = synchronized(lock) {
        Platform.entries.forEach { clear(it, method) }
    }

    /** Marks an enforcement method unavailable, for example when an adapter cannot act. */
    fun setMethodAvailable(platform: Platform, method: OpeningMethod, available: Boolean) {
        synchronized(lock) {
            val key = MethodKey(platform, method)
            if (available) unavailableMethods.remove(key) else unavailableMethods.add(key)
        }
        reevaluateAll()
    }

    fun reevaluateAll() = synchronized(lock) {
        grants.expireDue()
        for ((key, observation) in observations.entries.toList()) applyLocked(key, observation)
        scheduleLocked()
    }

    // --- internals -------------------------------------------------------------------------

    private fun evaluateLocked(key: MethodKey, observation: SurfaceObservation): PolicyDecision {
        val config = configs.value[key.platform] ?: PlatformConfig.defaultFor(key.platform)
        if (!config.selected) return PolicyDecision.NotManaged
        if (observation.surface == SurfaceKind.OTHER) return PolicyDecision.NotShortVideo
        if (!ready) return PolicyDecision.Unavailable(UnavailableReason.STARTING)
        return PolicyEngine.evaluate(
            PolicyInput(
                config = config,
                method = key.method,
                surface = observation.surface,
                methodAvailable = key !in unavailableMethods,
                grantRemainingMs = grants.remainingMs(key.platform),
                usedTodayMs = usage.usedTodayMs(key.platform),
                sharedItem = observation.sharedItem,
                sleepEndsInMs = sleep().endsInMs(clock.read()),
                interval = pacing.status(key.platform, config.interval),
            ),
        )
    }

    private fun sleep() = (preferences.value ?: AppPreferences()).sleep

    /** Interval time runs while any method shows counted viewing with blocking on. */
    private fun updatePacingLocked(platform: Platform) {
        val viewing = _decisions.value.any { (key, decision) ->
            key.platform == platform && key in observations &&
                decision is PolicyDecision.Allow && decision.reason != AllowReason.BLOCKING_OFF
        }
        pacing.setViewing(platform, viewing)
    }

    private fun applyLocked(key: MethodKey, observation: SurfaceObservation): PolicyDecision {
        val decision = evaluateLocked(key, observation)
        if (decision is PolicyDecision.Allow) {
            usage.start(key.platform, key.method, SessionOutcome.from(decision.reason))
        } else {
            usage.stop(key.platform, key.method)
        }
        _decisions.update { it + (key to decision) }
        updatePacingLocked(key.platform)
        return decision
    }

    private fun scheduleLocked() {
        timer?.cancel()
        timer = null
        val now = clock.read()
        var next: Long? = null
        var watching = false
        for ((key, decision) in _decisions.value) {
            if (key !in observations) continue
            val wait: Long? = when (decision) {
                is PolicyDecision.Allow -> {
                    watching = true
                    decision.runsOutInMs
                }
                is PolicyDecision.Deny -> when (decision.reason) {
                    DenyReason.ALLOWANCE_EXHAUSTED -> DayBuckets.msUntilNextMidnight(now)
                    DenyReason.SLEEP_SCHEDULE, DenyReason.INTERVAL_BREAK -> decision.retryInMs
                    DenyReason.BLOCKED -> null
                }
                else -> null
            }
            if (wait != null) next = minOf(next ?: wait, wait)
        }
        // While something is being watched, the sleep schedule may be about to start.
        if (watching) sleep().msUntilNextChange(now)?.let { next = minOf(next ?: it, it) }
        val delayMs = next ?: return
        timer = scope.launch {
            delay(delayMs.coerceIn(MIN_TIMER_MS, MAX_TIMER_MS))
            reevaluateAll()
        }
    }

    companion object {
        private const val MIN_TIMER_MS = 10L

        /** Long waits re-check hourly so wall-clock changes cannot leave a stale decision. */
        private const val MAX_TIMER_MS = 60 * 60_000L
    }
}
