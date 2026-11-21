package app.dereel.core.usage

import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.time.ClockReading
import app.dereel.core.time.DayBuckets
import app.dereel.core.time.DeviceClock
import app.dereel.core.util.SerialWriter
import java.time.ZoneId
import java.util.EnumMap
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The single usage ledger shared by native and browser adapters (DATA-02).
 *
 * - At most one open session per platform, so one elapsed second counts at most once. Overlapping
 *   or duplicate reports and method switches cannot double-count (FR-26).
 * - Elapsed time comes from the monotonic clock. Wall time is used only for day attribution and
 *   display (DATA-03).
 * - Open sessions are checkpointed every [checkpointIntervalMs] (FR-33).
 * - A session crossing local midnight is split between days. A clock or timezone jump never
 *   creates a negative duration and never reduces time already attributed to a day (FR-28).
 */
class UsageTracker(
    private val store: UsageStore,
    private val clock: DeviceClock,
    private val scope: CoroutineScope,
    private val writer: SerialWriter,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val checkpointIntervalMs: Long = CHECKPOINT_INTERVAL_MS,
) {
    private class OpenSegment(
        val sessionId: String,
        val platform: Platform,
        val method: OpeningMethod,
        val outcome: SessionOutcome,
        var segmentId: String,
        var startWallMs: Long,
        var day: Long,
        var accruedMs: Long,
        var lastMonotonicMs: Long,
        var lastWallMs: Long,
        var lastZone: ZoneId,
        var persisted: Boolean = false,
    )

    private val lock = Any()
    private val open = EnumMap<Platform, OpenSegment>(Platform::class.java)
    private val closedTotals = HashMap<Long, EnumMap<Platform, EnumMap<OpeningMethod, Long>>>()
    private var tickJob: Job? = null
    private val _revision = MutableStateFlow(0L)

    /** Changes whenever recorded or in-progress usage changes. Observers re-read summaries. */
    val revision: StateFlow<Long> = _revision.asStateFlow()

    /**
     * Closes segments left open by a previous process at their last checkpoint, then loads recent
     * totals. Time the app could not observe is not invented (DATA-03, FR-33).
     */
    suspend fun initialize() {
        val stale = store.openSegments()
        for (segment in stale) store.upsert(segment.copy(state = SegmentState.RECOVERED))
        val today = clock.read().day
        val recent = store.segmentsFromDay(today - RETENTION_DAYS)
        synchronized(lock) {
            closedTotals.clear()
            for (segment in recent) addClosedLocked(segment.day, segment.platform, segment.method, segment.elapsedMs)
            bumpLocked()
        }
    }

    /** Short-video viewing became visible and permitted. Repeated calls with the same arguments do nothing. */
    fun start(platform: Platform, method: OpeningMethod, outcome: SessionOutcome) = synchronized(lock) {
        val now = clock.read()
        val current = open[platform]
        if (current != null) {
            if (current.method == method && current.outcome == outcome) return@synchronized
            // A method switch or outcome change closes the old segment first, so the same second
            // is never counted twice.
            closeLocked(current, now)
        }
        open[platform] = OpenSegment(
            sessionId = newId(),
            platform = platform,
            method = method,
            outcome = outcome,
            segmentId = newId(),
            startWallMs = now.wallMs,
            day = now.day,
            accruedMs = 0,
            lastMonotonicMs = now.monotonicMs,
            lastWallMs = now.wallMs,
            lastZone = now.zone,
        )
        ensureTickerLocked()
        bumpLocked()
    }

    /** Viewing through [method] stopped. It does nothing if that method does not own the session. */
    fun stop(platform: Platform, method: OpeningMethod) = synchronized(lock) {
        val current = open[platform] ?: return@synchronized
        if (current.method != method) return@synchronized
        closeLocked(current, clock.read())
        stopTickerIfIdleLocked()
        bumpLocked()
    }

    fun stopAll() = synchronized(lock) {
        val now = clock.read()
        open.values.toList().forEach { closeLocked(it, now) }
        stopTickerIfIdleLocked()
        bumpLocked()
    }

    fun openMethod(platform: Platform): OpeningMethod? = synchronized(lock) { open[platform]?.method }

    /** Today's short-video time for [platform], combining both methods and any open session. */
    fun usedTodayMs(platform: Platform): Long = synchronized(lock) {
        val now = clock.read()
        open[platform]?.let { advanceLocked(it, now) }
        val closed = closedTotals[now.day]?.get(platform)?.values?.sum() ?: 0L
        val inFlight = open[platform]?.takeIf { it.day == now.day }?.accruedMs ?: 0L
        closed + inFlight
    }

    /** Usage for each day from [fromDay] to [toDay] inclusive, including open sessions. */
    fun summary(fromDay: Long, toDay: Long): List<DayUsage> = synchronized(lock) {
        val now = clock.read()
        open.values.toList().forEach { advanceLocked(it, now) }
        (fromDay..toDay).map { day ->
            val byPlatform = Platform.entries.associateWith { platform ->
                OpeningMethod.entries.associateWith { method ->
                    val closed = closedTotals[day]?.get(platform)?.get(method) ?: 0L
                    val inFlight = open[platform]?.takeIf { it.day == day && it.method == method }?.accruedMs ?: 0L
                    closed + inFlight
                }
            }
            DayUsage(day, byPlatform)
        }
    }

    /** Persists open sessions. Runs on a timer while any session is open, and before process exit. */
    fun checkpoint() = synchronized(lock) {
        val now = clock.read()
        for (segment in open.values.toList()) {
            advanceLocked(segment, now)
            persistOpenLocked(segment)
        }
        bumpLocked()
    }

    /**
     * Removes all history (FR-34). Today's allowance consequently restarts from zero. Open sessions
     * continue, measured from now.
     */
    suspend fun clearHistory() {
        synchronized(lock) {
            closedTotals.clear()
            val now = clock.read()
            for (segment in open.values) {
                rollLocked(segment, now.day, now.wallMs, 0)
                segment.lastMonotonicMs = now.monotonicMs
                segment.lastWallMs = now.wallMs
                segment.lastZone = now.zone
            }
            writer.submit { store.deleteAll() }
            bumpLocked()
        }
        writer.flush()
    }

    /** Keeps a rolling [RETENTION_DAYS] including today and removes older records (FR-34). */
    fun applyRetention() = synchronized(lock) {
        val cutoff = clock.read().day - (RETENTION_DAYS - 1)
        closedTotals.keys.removeAll { it < cutoff }
        writer.submit { store.deleteBeforeDay(cutoff) }
        bumpLocked()
    }

    suspend fun flush() = writer.flush()

    // --- internals -------------------------------------------------------------------------

    private fun advanceLocked(segment: OpenSegment, now: ClockReading) {
        val deltaMono = (now.monotonicMs - segment.lastMonotonicMs).coerceAtLeast(0)
        val deltaWall = now.wallMs - segment.lastWallMs
        val nowDay = now.day
        val sameZone = now.zone == segment.lastZone
        when {
            nowDay == segment.day -> segment.accruedMs += deltaMono

            // A real midnight crossing: same zone, and wall and monotonic time agree. Split at midnight.
            nowDay > segment.day && sameZone && abs(deltaWall - deltaMono) <= CLOCK_JUMP_TOLERANCE_MS -> {
                val midnight = DayBuckets.startOfDayMs(nowDay, now.zone)
                val afterMidnight = (now.wallMs - midnight).coerceIn(0, deltaMono)
                segment.accruedMs += deltaMono - afterMidnight
                finishSegmentLocked(segment, SegmentState.CLOSED)
                rollLocked(segment, nowDay, midnight, afterMidnight)
            }

            // The clock or timezone changed. Time observed so far stays with the day it already
            // belonged to, and measurement continues under the new local day.
            else -> {
                segment.accruedMs += deltaMono
                finishSegmentLocked(segment, SegmentState.CLOSED)
                rollLocked(segment, nowDay, now.wallMs, 0)
            }
        }
        segment.lastMonotonicMs = now.monotonicMs
        segment.lastWallMs = now.wallMs
        segment.lastZone = now.zone
    }

    private fun closeLocked(segment: OpenSegment, now: ClockReading) {
        advanceLocked(segment, now)
        finishSegmentLocked(segment, SegmentState.CLOSED)
        open.remove(segment.platform)
    }

    private fun finishSegmentLocked(segment: OpenSegment, state: SegmentState) {
        addClosedLocked(segment.day, segment.platform, segment.method, segment.accruedMs)
        if (segment.accruedMs > 0 || segment.persisted) {
            val record = segment.toRecord(state)
            writer.submit { store.upsert(record) }
        }
    }

    private fun rollLocked(segment: OpenSegment, day: Long, startWallMs: Long, accruedMs: Long) {
        segment.segmentId = newId()
        segment.day = day
        segment.startWallMs = startWallMs
        segment.accruedMs = accruedMs
        segment.persisted = false
    }

    private fun persistOpenLocked(segment: OpenSegment) {
        if (segment.accruedMs <= 0 && !segment.persisted) return
        val record = segment.toRecord(SegmentState.OPEN)
        segment.persisted = true
        writer.submit { store.upsert(record) }
    }

    private fun addClosedLocked(day: Long, platform: Platform, method: OpeningMethod, ms: Long) {
        if (ms <= 0) return
        val perPlatform = closedTotals.getOrPut(day) { EnumMap(Platform::class.java) }
        val perMethod = perPlatform.getOrPut(platform) { EnumMap(OpeningMethod::class.java) }
        perMethod[method] = (perMethod[method] ?: 0L) + ms
    }

    private fun OpenSegment.toRecord(state: SegmentState) = UsageSegment(
        id = segmentId,
        sessionId = sessionId,
        platform = platform,
        method = method,
        startWallMs = startWallMs,
        endWallMs = startWallMs + accruedMs,
        elapsedMs = accruedMs,
        day = day,
        outcome = outcome,
        state = state,
    )

    private fun ensureTickerLocked() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (isActive) {
                delay(checkpointIntervalMs)
                checkpoint()
                if (synchronized(lock) { open.isEmpty() }) break
            }
        }
    }

    private fun stopTickerIfIdleLocked() {
        if (open.isEmpty()) {
            tickJob?.cancel()
            tickJob = null
        }
    }

    private fun bumpLocked() {
        _revision.value = _revision.value + 1
    }

    companion object {
        const val CHECKPOINT_INTERVAL_MS = 5_000L
        const val RETENTION_DAYS = 30
        /** A wall/monotonic disagreement larger than this is treated as a clock change, not elapsed time. */
        const val CLOCK_JUMP_TOLERANCE_MS = 2_000L
    }
}
