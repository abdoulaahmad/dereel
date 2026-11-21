package app.dereel.core.policy

import app.dereel.core.model.Capabilities
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.PlatformConfig
import app.dereel.core.pacing.IntervalStatus
import app.dereel.core.shared.SharedItemMatch

/** What an adapter positively recognized on screen. */
enum class SurfaceKind { SHORT_VIDEO, OTHER }

enum class AllowReason {
    BLOCKING_OFF,
    TEMPORARY_ACCESS,
    SHARED_ITEM,
    ALLOWANCE,

    /** Inside an interval-timer watch window, with no daily allowance set (S-02). */
    INTERVAL,
}

enum class DenyReason {
    BLOCKED,
    ALLOWANCE_EXHAUSTED,

    /** The sleep schedule is in force (S-02). */
    SLEEP_SCHEDULE,

    /** The interval timer's break is running (S-02). */
    INTERVAL_BREAK,
}

enum class UnavailableReason {
    /** The enforcement method for this platform cannot act (POL-01). */
    METHOD_UNAVAILABLE,

    /** Settings and usage have not loaded yet after process start. */
    STARTING,
}

sealed interface PolicyDecision {
    /** The platform is not selected in Dereel. No enforcement and no measurement (FR-05). */
    data object NotManaged : PolicyDecision

    /** Messaging, search, profiles and regular videos. Never counted (POL-05). */
    data object NotShortVideo : PolicyDecision

    /** Short-video viewing is permitted. Visible time counts toward usage. */
    data class Allow(
        val reason: AllowReason,
        val remainingAllowanceMs: Long? = null,
        val grantRemainingMs: Long? = null,
        /** Time left in the interval timer's watch window, when the timer is on. */
        val remainingIntervalMs: Long? = null,
    ) : PolicyDecision {
        /** The soonest moment this permission can run out by itself, if any. */
        val runsOutInMs: Long?
            get() = when (reason) {
                AllowReason.BLOCKING_OFF -> null
                AllowReason.TEMPORARY_ACCESS -> grantRemainingMs
                else -> listOfNotNull(remainingAllowanceMs, remainingIntervalMs).minOrNull()
            }
    }

    /** [retryInMs] is set when the denial ends by itself: a break or the sleep schedule. */
    data class Deny(val reason: DenyReason, val retryInMs: Long? = null) : PolicyDecision

    /** Reported as unavailable, never as successful blocking (POL-01). */
    data class Unavailable(val reason: UnavailableReason) : PolicyDecision
}

val PolicyDecision.countsUsage: Boolean get() = this is PolicyDecision.Allow

data class PolicyInput(
    val config: PlatformConfig,
    val method: OpeningMethod,
    val surface: SurfaceKind,
    val methodAvailable: Boolean,
    /** Remaining time on an active temporary grant for this platform, or 0. */
    val grantRemainingMs: Long,
    /** Short-video time already attributed to today for this platform, across both methods. */
    val usedTodayMs: Long,
    /** The exception the adapter recognized for this exact item, if any. */
    val sharedItem: SharedItemMatch?,
    /** Time until the sleep schedule ends, or null when it is not in force. */
    val sleepEndsInMs: Long? = null,
    val interval: IntervalStatus = IntervalStatus.Off,
)

/**
 * Shared policy decisions for every adapter (POL-01 to POL-07, DATA-02).
 *
 * The rule order follows POL-01. The platform must be selected and short-video blocking enabled.
 * After that the order is: unavailable method, temporary access, sleep schedule, exhausted
 * allowance, interval break, shared item, remaining allowance or interval window, then default
 * blocking.
 *
 * The sleep schedule and the interval timer were added by scope change S-02. They only ever
 * restrict: neither can allow viewing that an exhausted allowance denies, and both give way to
 * temporary access, which the user asks for explicitly (POL-04).
 */
object PolicyEngine {
    fun evaluate(input: PolicyInput): PolicyDecision {
        val config = input.config
        if (!config.selected) return PolicyDecision.NotManaged
        if (input.surface == SurfaceKind.OTHER) return PolicyDecision.NotShortVideo
        // POL-07: with blocking off, content is allowed but usage is still measured.
        if (!config.blockShortVideos) return PolicyDecision.Allow(AllowReason.BLOCKING_OFF)

        if (!input.methodAvailable) return PolicyDecision.Unavailable(UnavailableReason.METHOD_UNAVAILABLE)

        // POL-04: temporary access bypasses blocking but usage keeps counting.
        if (input.grantRemainingMs > 0) {
            return PolicyDecision.Allow(AllowReason.TEMPORARY_ACCESS, grantRemainingMs = input.grantRemainingMs)
        }

        // S-02: the sleep schedule blocks short videos whatever time is left.
        input.sleepEndsInMs?.let { return PolicyDecision.Deny(DenyReason.SLEEP_SCHEDULE, retryInMs = it) }

        val limit = config.allowance.limitMs
        val remaining = limit?.let { (it - input.usedTodayMs).coerceAtLeast(0) }
        // POL-03: an exhausted allowance also denies shared items.
        if (remaining != null && remaining <= 0) return PolicyDecision.Deny(DenyReason.ALLOWANCE_EXHAUSTED)

        // S-02: during an interval break nothing is allowed, shared items included.
        val interval = input.interval
        if (interval is IntervalStatus.OnBreak) {
            return PolicyDecision.Deny(DenyReason.INTERVAL_BREAK, retryInMs = interval.remainingMs)
        }
        val windowLeft = (interval as? IntervalStatus.Watching)?.remainingMs

        // POL-02, FR-11: the exception applies only where the adapter supports it, and only to the
        // recognized item.
        val capabilities = Capabilities.of(config.platform, input.method)
        if (config.allowSharedVideos && capabilities.sharedItemException && input.sharedItem != null) {
            return PolicyDecision.Allow(AllowReason.SHARED_ITEM, remainingAllowanceMs = remaining, remainingIntervalMs = windowLeft)
        }

        if (remaining != null) {
            return PolicyDecision.Allow(AllowReason.ALLOWANCE, remainingAllowanceMs = remaining, remainingIntervalMs = windowLeft)
        }
        if (windowLeft != null) return PolicyDecision.Allow(AllowReason.INTERVAL, remainingIntervalMs = windowLeft)

        return PolicyDecision.Deny(DenyReason.BLOCKED)
    }
}
