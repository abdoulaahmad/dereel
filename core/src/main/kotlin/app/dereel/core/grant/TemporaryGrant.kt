package app.dereel.core.grant

import app.dereel.core.model.Platform
import app.dereel.core.time.ClockReading
import kotlin.math.min

enum class GrantState { ACTIVE, ENDED_EARLY, REPLACED, EXPIRED }

/** User-authorized temporary access for one platform (FR-29, POL-04). */
data class TemporaryGrant(
    val id: String,
    val platform: Platform,
    val issuedWallMs: Long,
    val expiresWallMs: Long,
    val issuedMonotonicMs: Long,
    val bootId: String,
    val state: GrantState = GrantState.ACTIVE,
) {
    /**
     * Remaining time, capped at [DURATION_MS]. Within the same boot the monotonic clock decides,
     * so moving the wall clock back cannot extend a grant. Across a reboot only the wall clock is
     * available. Self-control is voluntary, so this is not tamper-proof (FR-28).
     */
    fun remainingMs(now: ClockReading, currentBootId: String): Long {
        if (state != GrantState.ACTIVE) return 0
        val byWall = expiresWallMs - now.wallMs
        val sameBoot = bootId == currentBootId && now.monotonicMs >= issuedMonotonicMs
        if (sameBoot) {
            val byMonotonic = DURATION_MS - (now.monotonicMs - issuedMonotonicMs)
            return min(byWall, byMonotonic).coerceIn(0, DURATION_MS)
        }
        if (now.wallMs < issuedWallMs) return 0
        return byWall.coerceIn(0, DURATION_MS)
    }

    fun isActive(now: ClockReading, currentBootId: String): Boolean = remainingMs(now, currentBootId) > 0

    companion object {
        const val DURATION_MS = 5 * 60_000L
    }
}
