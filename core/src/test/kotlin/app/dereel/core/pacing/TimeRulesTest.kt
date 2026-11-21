package app.dereel.core.pacing

import app.dereel.core.ManualTime
import app.dereel.core.TestClock
import app.dereel.core.model.IntervalSetting
import app.dereel.core.model.Platform.INSTAGRAM
import app.dereel.core.model.Platform.YOUTUBE
import app.dereel.core.model.SleepSchedule
import app.dereel.core.time.ClockReading
import app.dereel.core.util.SerialWriter
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scope change S-02: the sleep schedule and the interval timer. */
@OptIn(ExperimentalCoroutinesApi::class)
class TimeRulesTest {
    private val minute = 60_000L
    private fun at(hour: Int, minuteOfHour: Int, zone: ZoneId = ZoneOffset.UTC) =
        ClockReading(0, TestClock.wallAt(2026, 11, 20, hour, minuteOfHour, zone = zone), zone)

    // --- sleep schedule ---------------------------------------------------------------------

    @Test
    fun `a schedule that crosses midnight covers late evening and early morning`() {
        val night = SleepSchedule(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60)
        assertTrue(night.isActiveAt(22 * 60))
        assertTrue(night.isActiveAt(23 * 60 + 59))
        assertTrue(night.isActiveAt(0))
        assertTrue(night.isActiveAt(6 * 60 + 59))
        assertFalse(night.isActiveAt(7 * 60))
        assertFalse(night.isActiveAt(12 * 60))
        assertFalse(night.isActiveAt(21 * 60 + 59))
    }

    @Test
    fun `a same-day schedule and its edge cases`() {
        val afternoon = SleepSchedule(enabled = true, startMinute = 13 * 60, endMinute = 15 * 60)
        assertTrue(afternoon.isActiveAt(13 * 60))
        assertFalse(afternoon.isActiveAt(15 * 60))
        assertFalse(afternoon.copy(enabled = false).isActiveAt(14 * 60))
        // Equal start and end never applies, rather than blocking the whole day by accident.
        assertFalse(SleepSchedule(enabled = true, startMinute = 600, endMinute = 600).isActiveAt(600))
    }

    @Test
    fun `time until the schedule ends and until it next changes`() {
        val night = SleepSchedule(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60)
        assertEquals(8 * 60 * minute, night.endsInMs(at(23, 0)))
        assertEquals(30 * minute, night.endsInMs(at(6, 30)))
        assertNull(night.endsInMs(at(12, 0)))
        assertEquals(10 * 60 * minute, night.msUntilNextChange(at(12, 0)))
        assertEquals(30 * minute, night.msUntilNextChange(at(6, 30)))
        assertNull(night.copy(enabled = false).msUntilNextChange(at(12, 0)))
    }

    @Test
    fun `the schedule follows the local time zone`() {
        val night = SleepSchedule(enabled = true, startMinute = 22 * 60, endMinute = 7 * 60)
        val lagos = ZoneId.of("Africa/Lagos")
        // 21:30 UTC is 22:30 in Lagos.
        val reading = ClockReading(0, TestClock.wallAt(2026, 11, 20, 21, 30), lagos)
        assertEquals((8 * 60 + 30) * minute, night.endsInMs(reading))
        assertNull(night.endsInMs(ClockReading(0, TestClock.wallAt(2026, 11, 20, 21, 30), ZoneOffset.UTC)))
    }

    @Test
    fun `stored values outside the allowed range fall back to defaults (DATA-01)`() {
        assertEquals(SleepSchedule(enabled = true), SleepSchedule.sanitized(true, -5, 5_000))
        assertEquals(IntervalSetting(enabled = true), IntervalSetting.sanitized(true, 0, 100_000))
        assertEquals(IntervalSetting(true, 5, 15), IntervalSetting.sanitized(true, 5, 15))
    }

    // --- interval timer ---------------------------------------------------------------------

    private val time = ManualTime()
    private val clock = TestClock({ time.nowMs })
    private val setting = IntervalSetting(enabled = true, watchMinutes = 5, breakMinutes = 15)

    @Test
    fun `a watch window is followed by a break and then a fresh window`() {
        val tracker = PacingTracker(clock)
        assertEquals(IntervalStatus.Watching(5 * minute), tracker.status(INSTAGRAM, setting))

        tracker.setViewing(INSTAGRAM, true)
        time.advance(3 * minute)
        assertEquals(IntervalStatus.Watching(2 * minute), tracker.status(INSTAGRAM, setting))
        time.advance(2 * minute)
        assertEquals(IntervalStatus.OnBreak(15 * minute), tracker.status(INSTAGRAM, setting))
        tracker.setViewing(INSTAGRAM, false)

        time.advance(10 * minute)
        assertEquals(IntervalStatus.OnBreak(5 * minute), tracker.status(INSTAGRAM, setting))
        time.advance(5 * minute)
        assertEquals(IntervalStatus.Watching(5 * minute), tracker.status(INSTAGRAM, setting))
    }

    @Test
    fun `watch time adds up across visits and only while viewing`() {
        val tracker = PacingTracker(clock)
        tracker.setViewing(INSTAGRAM, true)
        time.advance(2 * minute)
        tracker.setViewing(INSTAGRAM, false)
        time.advance(4 * minute) // not viewing: nothing is used
        assertEquals(IntervalStatus.Watching(3 * minute), tracker.status(INSTAGRAM, setting))
        tracker.setViewing(INSTAGRAM, true)
        time.advance(1 * minute)
        assertEquals(IntervalStatus.Watching(2 * minute), tracker.status(INSTAGRAM, setting))
        // Platforms are paced separately.
        assertEquals(IntervalStatus.Watching(5 * minute), tracker.status(YOUTUBE, setting))
    }

    @Test
    fun `a pause as long as a break starts a fresh window`() {
        val tracker = PacingTracker(clock)
        tracker.setViewing(INSTAGRAM, true)
        time.advance(4 * minute)
        tracker.setViewing(INSTAGRAM, false)
        time.advance(14 * minute)
        assertEquals(IntervalStatus.Watching(1 * minute), tracker.status(INSTAGRAM, setting))
        time.advance(1 * minute)
        assertEquals(IntervalStatus.Watching(5 * minute), tracker.status(INSTAGRAM, setting))
    }

    @Test
    fun `a clock change cannot stretch a break`() {
        val tracker = PacingTracker(clock)
        tracker.setViewing(INSTAGRAM, true)
        time.advance(5 * minute)
        assertEquals(IntervalStatus.OnBreak(15 * minute), tracker.status(INSTAGRAM, setting))
        tracker.setViewing(INSTAGRAM, false)
        // The wall clock is set back a day: the break would otherwise last a day longer.
        clock.wallShiftMs = -24 * 60 * minute
        assertEquals(IntervalStatus.Watching(5 * minute), tracker.status(INSTAGRAM, setting))
    }

    @Test
    fun `turning the timer off forgets its progress`() {
        val tracker = PacingTracker(clock)
        tracker.setViewing(INSTAGRAM, true)
        time.advance(5 * minute)
        assertTrue(tracker.status(INSTAGRAM, setting) is IntervalStatus.OnBreak)
        assertEquals(IntervalStatus.Off, tracker.status(INSTAGRAM, setting.copy(enabled = false)))
        assertEquals(IntervalStatus.Watching(5 * minute), tracker.status(INSTAGRAM, setting))
    }

    private class MemoryStore : PacingStore {
        val rows = LinkedHashMap<app.dereel.core.model.Platform, PacingState>()
        override suspend fun load(): List<PacingState> = rows.values.toList()
        override suspend fun save(state: PacingState) {
            rows[state.platform] = state
        }
        override suspend fun deleteAll() = rows.clear()
    }

    @Test
    fun `a break survives a restart and clearing removes it`() = runTest {
        val store = MemoryStore()
        val writer = SerialWriter(backgroundScope)
        val first = PacingTracker(clock, store, writer)
        first.setViewing(INSTAGRAM, true)
        time.advance(5 * minute)
        assertTrue(first.status(INSTAGRAM, setting) is IntervalStatus.OnBreak)
        first.setViewing(INSTAGRAM, false)
        runCurrent()
        writer.flush()

        // A new process: nothing in memory, the break is read back.
        time.advance(6 * minute)
        val second = PacingTracker(clock, store, writer)
        second.load()
        assertEquals(IntervalStatus.OnBreak(9 * minute), second.status(INSTAGRAM, setting))

        second.clearAll()
        runCurrent()
        writer.flush()
        assertTrue(store.rows.isEmpty())
        assertEquals(IntervalStatus.Watching(5 * minute), second.status(INSTAGRAM, setting))
    }
}
