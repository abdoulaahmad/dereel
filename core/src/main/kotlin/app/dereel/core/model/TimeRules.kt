package app.dereel.core.model

import app.dereel.core.time.ClockReading
import java.time.Instant

/**
 * Optional interval timer for one platform (scope change S-02): short videos may be watched for
 * [watchMinutes], then they are blocked for [breakMinutes], and so on. It can be combined with the
 * daily allowance: the allowance caps the day, the interval paces it.
 *
 * The chosen lengths are kept when the timer is turned off, so it can be turned back on without
 * choosing again.
 */
data class IntervalSetting(
    val enabled: Boolean = false,
    val watchMinutes: Int = DEFAULT_WATCH_MINUTES,
    val breakMinutes: Int = DEFAULT_BREAK_MINUTES,
) {
    init {
        require(watchMinutes in MIN_MINUTES..MAX_WATCH_MINUTES) { "Watch time must be $MIN_MINUTES to $MAX_WATCH_MINUTES minutes" }
        require(breakMinutes in MIN_MINUTES..MAX_BREAK_MINUTES) { "Break must be $MIN_MINUTES to $MAX_BREAK_MINUTES minutes" }
    }

    val watchMs: Long get() = watchMinutes * MINUTE_MS
    val breakMs: Long get() = breakMinutes * MINUTE_MS

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_WATCH_MINUTES = 120
        const val MAX_BREAK_MINUTES = 720
        const val DEFAULT_WATCH_MINUTES = 10
        const val DEFAULT_BREAK_MINUTES = 30
        const val MINUTE_MS = 60_000L

        val WATCH_CHOICES = listOf(5, 10, 15, 30)
        val BREAK_CHOICES = listOf(15, 30, 60, 120)

        /** Stored values outside the allowed range fall back to the defaults (DATA-01). */
        fun sanitized(enabled: Boolean, watchMinutes: Int, breakMinutes: Int): IntervalSetting = IntervalSetting(
            enabled = enabled,
            watchMinutes = watchMinutes.takeIf { it in MIN_MINUTES..MAX_WATCH_MINUTES } ?: DEFAULT_WATCH_MINUTES,
            breakMinutes = breakMinutes.takeIf { it in MIN_MINUTES..MAX_BREAK_MINUTES } ?: DEFAULT_BREAK_MINUTES,
        )
    }
}

/**
 * Optional nightly schedule for every platform (scope change S-02): between [startMinute] and
 * [endMinute] of the local day, short videos are blocked whatever allowance is left. The range
 * may cross midnight. Equal start and end means the schedule never applies.
 */
data class SleepSchedule(
    val enabled: Boolean = false,
    val startMinute: Int = DEFAULT_START_MINUTE,
    val endMinute: Int = DEFAULT_END_MINUTE,
) {
    init {
        require(startMinute in 0 until MINUTES_PER_DAY && endMinute in 0 until MINUTES_PER_DAY) { "Minutes must be within one day" }
    }

    fun isActiveAt(minuteOfDay: Int): Boolean = enabled && when {
        startMinute == endMinute -> false
        startMinute < endMinute -> minuteOfDay in startMinute until endMinute
        else -> minuteOfDay >= startMinute || minuteOfDay < endMinute
    }

    /** Time until the schedule ends, or null when it is not in force right now. */
    fun endsInMs(reading: ClockReading): Long? {
        val msOfDay = msOfDay(reading)
        return if (isActiveAt((msOfDay / MINUTE_MS).toInt())) msUntil(endMinute, msOfDay) else null
    }

    /** Time until the schedule next starts or ends, or null when it can never apply. */
    fun msUntilNextChange(reading: ClockReading): Long? {
        if (!enabled || startMinute == endMinute) return null
        val msOfDay = msOfDay(reading)
        return minOf(msUntil(startMinute, msOfDay), msUntil(endMinute, msOfDay))
    }

    private fun msOfDay(reading: ClockReading): Long =
        Instant.ofEpochMilli(reading.wallMs).atZone(reading.zone).toLocalTime().toNanoOfDay() / 1_000_000

    private fun msUntil(minute: Int, msOfDay: Long): Long {
        val wait = (minute * MINUTE_MS - msOfDay + DAY_MS) % DAY_MS
        return if (wait == 0L) DAY_MS else wait
    }

    companion object {
        const val MINUTES_PER_DAY = 24 * 60
        const val DEFAULT_START_MINUTE = 22 * 60
        const val DEFAULT_END_MINUTE = 7 * 60
        private const val MINUTE_MS = 60_000L
        private const val DAY_MS = MINUTES_PER_DAY * MINUTE_MS

        fun sanitized(enabled: Boolean, startMinute: Int, endMinute: Int): SleepSchedule = SleepSchedule(
            enabled = enabled,
            startMinute = startMinute.takeIf { it in 0 until MINUTES_PER_DAY } ?: DEFAULT_START_MINUTE,
            endMinute = endMinute.takeIf { it in 0 until MINUTES_PER_DAY } ?: DEFAULT_END_MINUTE,
        )
    }
}
