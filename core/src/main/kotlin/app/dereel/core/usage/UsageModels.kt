package app.dereel.core.usage

import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.policy.AllowReason

/** Why viewing was permitted while the time accrued (FR-32 "enforcement outcome"). */
enum class SessionOutcome {
    ALLOWANCE, SHARED_ITEM, TEMPORARY_ACCESS, BLOCKING_OFF, INTERVAL;

    companion object {
        fun from(reason: AllowReason): SessionOutcome = when (reason) {
            AllowReason.ALLOWANCE -> ALLOWANCE
            AllowReason.SHARED_ITEM -> SHARED_ITEM
            AllowReason.TEMPORARY_ACCESS -> TEMPORARY_ACCESS
            AllowReason.BLOCKING_OFF -> BLOCKING_OFF
            AllowReason.INTERVAL -> INTERVAL
        }
    }
}

enum class SegmentState {
    /** Still being measured. Elapsed time is as of the last checkpoint. */
    OPEN,
    CLOSED,

    /** Left open by a process that ended unexpectedly, closed at its last checkpoint. */
    RECOVERED,
}

/**
 * One local-day portion of a viewing session (FR-32, Section 7.1). A session that crosses midnight
 * produces one segment per day (FR-28).
 *
 * The record never contains message content, page text, URLs or account identifiers.
 */
data class UsageSegment(
    val id: String,
    val sessionId: String,
    val platform: Platform,
    val method: OpeningMethod,
    val startWallMs: Long,
    val endWallMs: Long,
    /** Monotonic elapsed time. This, not the wall timestamps, is the measured usage. */
    val elapsedMs: Long,
    /** Local date bucket as an epoch day. */
    val day: Long,
    val outcome: SessionOutcome,
    val state: SegmentState,
) {
    init {
        require(elapsedMs >= 0) { "Negative duration" }
        require(endWallMs >= startWallMs) { "End before start" }
    }
}

interface UsageStore {
    suspend fun upsert(segment: UsageSegment)
    suspend fun openSegments(): List<UsageSegment>
    suspend fun segmentsFromDay(fromDay: Long): List<UsageSegment>
    suspend fun deleteBeforeDay(day: Long): Int
    suspend fun deleteAll()
}

/** Usage for one local day, split by platform and opening method (FR-31). */
data class DayUsage(val day: Long, val byPlatform: Map<Platform, Map<OpeningMethod, Long>>) {
    fun total(platform: Platform): Long = byPlatform[platform]?.values?.sum() ?: 0L
    fun of(platform: Platform, method: OpeningMethod): Long = byPlatform[platform]?.get(method) ?: 0L
}
