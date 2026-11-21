package app.dereel.core.pacing

import app.dereel.core.model.IntervalSetting
import app.dereel.core.model.Platform
import app.dereel.core.time.DeviceClock
import app.dereel.core.util.SerialWriter

/** Where a platform stands in its interval timer. */
sealed interface IntervalStatus {
    /** The interval timer is off for the platform. */
    data object Off : IntervalStatus

    /** Viewing is within the current watch window. */
    data class Watching(val remainingMs: Long) : IntervalStatus

    /** The watch window is used up and the break has not ended. */
    data class OnBreak(val remainingMs: Long) : IntervalStatus
}

/** Stored interval-timer progress for one platform. It holds durations and times only. */
data class PacingState(
    val platform: Platform,
    val usedMs: Long,
    val breakUntilWallMs: Long,
    val lastViewedWallMs: Long,
)

interface PacingStore {
    suspend fun load(): List<PacingState>
    suspend fun save(state: PacingState)
    suspend fun deleteAll()
}

/**
 * Tracks interval-timer windows and breaks (scope change S-02).
 *
 * Watched time is measured with the monotonic clock while the coordinator reports viewing. When a
 * window is used up a break starts, and its end is kept as a wall-clock time so it survives a
 * restart. A break is never longer than its configured length, so a clock change cannot stretch
 * it. Like the rest of Dereel this is a voluntary aid, not a tamper-proof clock policy.
 *
 * A pause at least as long as a break starts a fresh window: the user already took the break.
 */
class PacingTracker(
    private val clock: DeviceClock,
    private val store: PacingStore? = null,
    private val writer: SerialWriter? = null,
) {
    private class Entry(
        var usedMs: Long = 0,
        var viewingSinceMonotonicMs: Long? = null,
        var breakUntilWallMs: Long = 0,
        var lastViewedWallMs: Long = 0,
    )

    private val lock = Any()
    private val entries = HashMap<Platform, Entry>()

    /** Restores stored progress. Call once before the first evaluation. */
    suspend fun load() {
        val rows = store?.load().orEmpty()
        synchronized(lock) {
            for (row in rows) {
                val entry = entries.getOrPut(row.platform) { Entry() }
                entry.usedMs = row.usedMs.coerceAtLeast(0)
                entry.breakUntilWallMs = row.breakUntilWallMs.coerceAtLeast(0)
                entry.lastViewedWallMs = row.lastViewedWallMs
            }
        }
    }

    /** The current status. Moves from watching to a break, and from a finished break to a new window. */
    fun status(platform: Platform, setting: IntervalSetting): IntervalStatus = synchronized(lock) {
        val entry = entries.getOrPut(platform) { Entry() }
        val nowWall = clock.wallMs()
        val nowMonotonic = clock.monotonicMs()
        if (!setting.enabled) {
            if (entry.usedMs != 0L || entry.breakUntilWallMs != 0L) {
                entry.usedMs = 0
                entry.breakUntilWallMs = 0
                persist(platform, entry)
            }
            if (entry.viewingSinceMonotonicMs != null) entry.viewingSinceMonotonicMs = nowMonotonic
            return IntervalStatus.Off
        }

        if (entry.breakUntilWallMs != 0L) {
            val left = entry.breakUntilWallMs - nowWall
            if (left > 0 && left <= setting.breakMs) return IntervalStatus.OnBreak(left)
            // The break is over (or the clock moved): a new window starts now.
            entry.breakUntilWallMs = 0
            entry.usedMs = 0
            if (entry.viewingSinceMonotonicMs != null) entry.viewingSinceMonotonicMs = nowMonotonic
            persist(platform, entry)
        }

        val resting = entry.viewingSinceMonotonicMs == null
        if (resting && entry.usedMs > 0 && nowWall - entry.lastViewedWallMs >= setting.breakMs) {
            entry.usedMs = 0
            persist(platform, entry)
        }

        val used = entry.usedMs + (entry.viewingSinceMonotonicMs?.let { (nowMonotonic - it).coerceAtLeast(0) } ?: 0L)
        if (used >= setting.watchMs) {
            entry.usedMs = 0
            entry.breakUntilWallMs = nowWall + setting.breakMs
            entry.lastViewedWallMs = nowWall
            if (entry.viewingSinceMonotonicMs != null) entry.viewingSinceMonotonicMs = nowMonotonic
            persist(platform, entry)
            return IntervalStatus.OnBreak(setting.breakMs)
        }
        IntervalStatus.Watching(setting.watchMs - used)
    }

    /** The coordinator reports whether counted short-video viewing is happening for the platform. */
    fun setViewing(platform: Platform, viewing: Boolean) = synchronized(lock) {
        val entry = entries.getOrPut(platform) { Entry() }
        val since = entry.viewingSinceMonotonicMs
        if (viewing) {
            if (since == null) entry.viewingSinceMonotonicMs = clock.monotonicMs()
        } else if (since != null) {
            entry.usedMs += (clock.monotonicMs() - since).coerceAtLeast(0)
            entry.viewingSinceMonotonicMs = null
            entry.lastViewedWallMs = clock.wallMs()
            persist(platform, entry)
        }
    }

    /** Forgets all progress, for "Reset Dereel" and when history is cleared. */
    fun clearAll() = synchronized(lock) {
        entries.clear()
        val store = store ?: return@synchronized
        writer?.submit { store.deleteAll() }
    }

    private fun persist(platform: Platform, entry: Entry) {
        val store = store ?: return
        val state = PacingState(platform, entry.usedMs, entry.breakUntilWallMs, entry.lastViewedWallMs)
        writer?.submit { store.save(state) }
    }
}
