package app.dereel.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Clock abstraction (DATA-03). Durations use the monotonic clock; wall-clock time is used only
 * for display and for attributing time to a local day.
 */
interface DeviceClock {
    /** Monotonic milliseconds since boot. It never goes backwards within one boot. */
    fun monotonicMs(): Long

    /** Wall-clock epoch milliseconds. It can jump when the user or network changes the time. */
    fun wallMs(): Long

    fun zone(): ZoneId

    /** Identifies the current boot, so stored monotonic values are compared only within one boot. */
    fun bootId(): String

    fun read(): ClockReading = ClockReading(monotonicMs(), wallMs(), zone())
}

data class ClockReading(val monotonicMs: Long, val wallMs: Long, val zone: ZoneId) {
    val day: Long get() = DayBuckets.dayOf(wallMs, zone)
}

/** Local-date buckets expressed as epoch days. */
object DayBuckets {
    fun dayOf(wallMs: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(wallMs).atZone(zone).toLocalDate().toEpochDay()

    fun startOfDayMs(day: Long, zone: ZoneId): Long =
        LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

    fun msUntilNextMidnight(reading: ClockReading): Long =
        (startOfDayMs(reading.day + 1, reading.zone) - reading.wallMs).coerceAtLeast(1)
}
