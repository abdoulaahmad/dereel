package app.dereel.core.usage

import app.dereel.core.InMemoryUsageStore
import app.dereel.core.SequentialIds
import app.dereel.core.TestClock
import app.dereel.core.model.OpeningMethod.IN_DEREEL
import app.dereel.core.model.OpeningMethod.NATIVE_APP
import app.dereel.core.model.Platform.INSTAGRAM
import app.dereel.core.model.Platform.YOUTUBE
import app.dereel.core.time.DayBuckets
import app.dereel.core.util.SerialWriter
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsageTrackerTest {
    private val store = InMemoryUsageStore()

    private fun TestScope.tracker(clock: TestClock, scope: CoroutineScope = backgroundScope) =
        UsageTracker(store, clock, scope, SerialWriter(scope), SequentialIds())

    private fun TestScope.clock(wallStart: Long = TestClock.wallAt(2026, 11, 19, 10, 0), zone: ZoneId = ZoneOffset.UTC) =
        TestClock({ testScheduler.currentTime }, wallStart, zone)

    @Test
    fun `accrues monotonic elapsed time while visible (FR-25)`() = runTest {
        val tracker = tracker(clock())
        tracker.start(INSTAGRAM, NATIVE_APP, SessionOutcome.ALLOWANCE)
        advanceTimeBy(30_000)
        tracker.stop(INSTAGRAM, NATIVE_APP)
        tracker.flush()

        assertEquals(30_000, tracker.usedTodayMs(INSTAGRAM))
        val row = store.rows.values.single()
        assertEquals(30_000, row.elapsedMs)
        assertEquals(SegmentState.CLOSED, row.state)
        assertEquals(SessionOutcome.ALLOWANCE, row.outcome)
    }

    @Test
    fun `duplicate and overlapping reports never double count (FR-26)`() = runTest {
        val tracker = tracker(clock())
        repeat(5) { tracker.start(INSTAGRAM, NATIVE_APP, SessionOutcome.ALLOWANCE) }
        advanceTimeBy(5_000)
        tracker.start(INSTAGRAM, NATIVE_APP, SessionOutcome.ALLOWANCE)
        advanceTimeBy(5_000)
        tracker.stop(INSTAGRAM, NATIVE_APP)
        tracker.stop(INSTAGRAM, NATIVE_APP)
        assertEquals(10_000, tracker.usedTodayMs(INSTAGRAM))
    }

    @Test
    fun `six minutes native plus four minutes browser totals ten minutes (AT-08)`() = runTest {
        val tracker = tracker(clock())
        tracker.start(INSTAGRAM, NATIVE_APP, SessionOutcome.ALLOWANCE)
        advanceTimeBy(6 * 60_000)
        // The browser starts before the native adapter notices the app has left: no overlap.
        tracker.start(INSTAGRAM, IN_DEREEL, SessionOutcome.ALLOWANCE)
        tracker.stop(INSTAGRAM, NATIVE_APP)
        advanceTimeBy(4 * 60_000)
        tracker.stop(INSTAGRAM, IN_DEREEL)
        tracker.flush()

        assertEquals(10 * 60_000, tracker.usedTodayMs(INSTAGRAM))
        val today = tracker.summary(clock().read().day, clock().read().day).single()
        assertEquals(6 * 60_000, today.of(INSTAGRAM, NATIVE_APP))
        assertEquals(4 * 60_000, today.of(INSTAGRAM, IN_DEREEL))
        assertEquals(10 * 60_000, today.total(INSTAGRAM))
        assertEquals(0, today.total(YOUTUBE))
        assertEquals(10 * 60_000L, store.rows.values.sumOf { it.elapsedMs })
    }

    @Test
    fun `stop from a method that does not own the session is ignored`() = runTest {
        val tracker = tracker(clock())
        tracker.start(YOUTUBE, IN_DEREEL, SessionOutcome.ALLOWANCE)
        advanceTimeBy(3_000)
        tracker.stop(YOUTUBE, NATIVE_APP)
        advanceTimeBy(3_000)
        assertEquals(IN_DEREEL, tracker.openMethod(YOUTUBE))
        assertEquals(6_000, tracker.usedTodayMs(YOUTUBE))
    }

    @Test
    fun `open sessions are checkpointed every five seconds (FR-33)`() = runTest {
        val tracker = tracker(clock())
        tracker.start(INSTAGRAM, IN_DEREEL, SessionOutcome.SHARED_ITEM)
        advanceTimeBy(12_000)
        runCurrent()
        val row = store.rows.values.single()
        assertEquals(SegmentState.OPEN, row.state)
        assertEquals(10_000, row.elapsedMs)
    }

    @Test
    fun `restart recovers at the last checkpoint without inventing time (AT-12, DATA-03)`() = runTest {
        val clock = clock()
        val process = Job()
        val processScope = CoroutineScope(backgroundScope.coroutineContext + process)
        val first = tracker(clock, processScope)
        first.start(INSTAGRAM, NATIVE_APP, SessionOutcome.ALLOWANCE)
        advanceTimeBy(12_000) // checkpoints at 5 s and 10 s, then the process "dies" at 12 s
        runCurrent()
        process.cancel()

        advanceTimeBy(60_000) // Dereel is not running for a minute
        val second = UsageTracker(store, clock, backgroundScope, SerialWriter(backgroundScope), SequentialIds("b"))
        second.initialize()

        val row = store.rows.values.single()
        assertEquals(SegmentState.RECOVERED, row.state)
        assertEquals(10_000, row.elapsedMs)
        val measured = 12_000L
        val lost = measured - second.usedTodayMs(INSTAGRAM)
        assertTrue("lost ${lost}ms", lost in 0..5_000)
    }

    @Test
    fun `session crossing midnight is split between days (FR-28, AT-11)`() = runTest {
        val clock = clock(wallStart = TestClock.wallAt(2026, 11, 19, 23, 59, 0))
        val tracker = tracker(clock)
        val firstDay = clock.read().day
        tracker.start(INSTAGRAM, NATIVE_APP, SessionOutcome.ALLOWANCE)
        advanceTimeBy(120_000)
        tracker.stop(INSTAGRAM, NATIVE_APP)
        tracker.flush()

        val byDay = store.rows.values.groupBy { it.day }.mapValues { (_, rows) -> rows.sumOf { it.elapsedMs } }
        assertEquals(60_000L, byDay[firstDay])
        assertEquals(60_000L, byDay[firstDay + 1])
        assertEquals(60_000, tracker.usedTodayMs(INSTAGRAM))
        store.rows.values.forEach { assertTrue(it.endWallMs >= it.startWallMs) }
    }

    @Test
    fun `clock moving backwards keeps earlier attribution and never goes negative (FR-28, AT-11)`() = runTest {
        val clock = clock()
        val tracker = tracker(clock)
        val day = clock.read().day
        tracker.start(YOUTUBE, NATIVE_APP, SessionOutcome.ALLOWANCE)
        advanceTimeBy(10_000)
        val beforeChange = tracker.usedTodayMs(YOUTUBE)
        clock.wallShiftMs = -2 * 24 * 60 * 60_000L
        advanceTimeBy(10_000)
        tracker.stop(YOUTUBE, NATIVE_APP)
        tracker.flush()

        val rows = store.rows.values
        rows.forEach {
            assertTrue(it.elapsedMs >= 0)
            assertTrue(it.endWallMs >= it.startWallMs)
        }
        assertEquals(20_000L, rows.sumOf { it.elapsedMs })
        val earlierDay = rows.filter { it.day == day }.sumOf { it.elapsedMs }
        assertTrue("day total reduced: $earlierDay < $beforeChange", earlierDay >= beforeChange)
    }

    @Test
    fun `timezone change preserves accrued records (FR-28, AT-11)`() = runTest {
        val clock = clock(wallStart = TestClock.wallAt(2026, 11, 19, 20, 0))
        val tracker = tracker(clock)
        val day = clock.read().day
        tracker.start(INSTAGRAM, IN_DEREEL, SessionOutcome.ALLOWANCE)
        advanceTimeBy(10_000)
        clock.zoneId = ZoneId.of("Pacific/Kiritimati") // UTC+14: local date moves to the next day
        advanceTimeBy(10_000)
        tracker.stop(INSTAGRAM, IN_DEREEL)
        tracker.flush()

        val rows = store.rows.values
        assertEquals(20_000L, rows.sumOf { it.elapsedMs })
        assertTrue(rows.filter { it.day == day }.sumOf { it.elapsedMs } >= 10_000)
        rows.forEach { assertTrue(it.elapsedMs >= 0) }
    }

    @Test
    fun `changing outcome splits the segment`() = runTest {
        val tracker = tracker(clock())
        tracker.start(INSTAGRAM, IN_DEREEL, SessionOutcome.ALLOWANCE)
        advanceTimeBy(4_000)
        tracker.start(INSTAGRAM, IN_DEREEL, SessionOutcome.TEMPORARY_ACCESS)
        advanceTimeBy(6_000)
        tracker.stop(INSTAGRAM, IN_DEREEL)
        tracker.flush()
        val outcomes = store.rows.values.associate { it.outcome to it.elapsedMs }
        assertEquals(mapOf(SessionOutcome.ALLOWANCE to 4_000L, SessionOutcome.TEMPORARY_ACCESS to 6_000L), outcomes)
        assertEquals(10_000, tracker.usedTodayMs(INSTAGRAM))
    }

    @Test
    fun `clearing history removes records and restarts today's total (FR-34)`() = runTest {
        val tracker = tracker(clock())
        tracker.start(INSTAGRAM, NATIVE_APP, SessionOutcome.ALLOWANCE)
        advanceTimeBy(30_000)
        tracker.clearHistory()
        assertEquals(0, tracker.usedTodayMs(INSTAGRAM))
        assertTrue(store.rows.isEmpty())
        advanceTimeBy(2_000)
        tracker.stop(INSTAGRAM, NATIVE_APP)
        tracker.flush()
        assertEquals(2_000, tracker.usedTodayMs(INSTAGRAM))
    }

    @Test
    fun `retention keeps a rolling thirty days (FR-34, AT-22)`() = runTest {
        val clock = clock()
        val today = clock.read().day
        fun row(id: String, day: Long) = UsageSegment(
            id, id, INSTAGRAM, NATIVE_APP, DayBuckets.startOfDayMs(day, ZoneOffset.UTC),
            DayBuckets.startOfDayMs(day, ZoneOffset.UTC) + 1_000, 1_000, day, SessionOutcome.ALLOWANCE, SegmentState.CLOSED,
        )
        store.upsert(row("today", today))
        store.upsert(row("oldest-kept", today - 29))
        store.upsert(row("expired", today - 30))
        store.upsert(row("ancient", today - 400))
        val tracker = tracker(clock)
        tracker.initialize()
        tracker.applyRetention()
        tracker.flush()

        assertEquals(setOf("today", "oldest-kept"), store.rows.keys)
        assertEquals(1_000, tracker.summary(today - 29, today - 29).single().total(INSTAGRAM))
    }

    @Test
    fun `no checkpoint loop runs when nothing is open (NFR-09)`() = runTest {
        val tracker = tracker(clock())
        tracker.start(INSTAGRAM, NATIVE_APP, SessionOutcome.ALLOWANCE)
        advanceTimeBy(1_000)
        tracker.stop(INSTAGRAM, NATIVE_APP)
        val revision = tracker.revision.value
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(revision, tracker.revision.value)
    }
}
