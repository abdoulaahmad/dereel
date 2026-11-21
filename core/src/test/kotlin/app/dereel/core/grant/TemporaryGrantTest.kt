package app.dereel.core.grant

import app.dereel.core.InMemoryGrantStore
import app.dereel.core.ManualTime
import app.dereel.core.SequentialIds
import app.dereel.core.TestClock
import app.dereel.core.model.Platform.INSTAGRAM
import app.dereel.core.model.Platform.YOUTUBE
import app.dereel.core.util.SerialWriter
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TemporaryGrantTest {
    private val time = ManualTime()
    private val clock = TestClock({ time.nowMs })

    private fun grant(state: GrantState = GrantState.ACTIVE): TemporaryGrant {
        val now = clock.read()
        return TemporaryGrant("g", INSTAGRAM, now.wallMs, now.wallMs + TemporaryGrant.DURATION_MS, now.monotonicMs, clock.boot, state)
    }

    @Test
    fun `grant lasts exactly five minutes`() {
        val g = grant()
        assertEquals(TemporaryGrant.DURATION_MS, g.remainingMs(clock.read(), clock.boot))
        time.advance(TemporaryGrant.DURATION_MS - 1)
        assertTrue(g.isActive(clock.read(), clock.boot))
        time.advance(1)
        assertEquals(0, g.remainingMs(clock.read(), clock.boot))
    }

    @Test
    fun `moving the wall clock back does not extend a grant within one boot`() {
        val g = grant()
        time.advance(4 * 60_000)
        clock.wallShiftMs = -60 * 60_000
        assertEquals(60_000, g.remainingMs(clock.read(), clock.boot))
        time.advance(60_000)
        assertEquals(0, g.remainingMs(clock.read(), clock.boot))
    }

    @Test
    fun `after a reboot only the wall clock is used and earlier clocks expire the grant`() {
        val g = grant()
        clock.boot = "boot-2"
        clock.monotonicBaseMs = 0
        time.advance(60_000)
        assertEquals(4 * 60_000L, g.remainingMs(clock.read(), clock.boot))
        clock.wallShiftMs = -10 * 60_000
        assertEquals(0, g.remainingMs(clock.read(), clock.boot))
    }

    @Test
    fun `ended grants are inactive`() {
        assertEquals(0, grant(GrantState.ENDED_EARLY).remainingMs(clock.read(), clock.boot))
    }

    @Test
    fun `load keeps active grants and expires stale ones`() = runTest {
        val store = InMemoryGrantStore()
        val now = clock.read()
        store.save(TemporaryGrant("old", YOUTUBE, now.wallMs - 10 * 60_000, now.wallMs - 5 * 60_000, now.monotonicMs - 10 * 60_000, clock.boot))
        store.save(TemporaryGrant("live", INSTAGRAM, now.wallMs, now.wallMs + TemporaryGrant.DURATION_MS, now.monotonicMs, clock.boot))
        val writer = SerialWriter(backgroundScope)
        val manager = GrantManager(store, clock, writer, SequentialIds())
        manager.load()
        writer.flush()

        assertEquals("live", manager.activeGrant(INSTAGRAM)?.id)
        assertNull(manager.activeGrant(YOUTUBE))
        assertEquals(GrantState.EXPIRED, store.rows.getValue("old").state)
    }

    @Test
    fun `end records early termination`() = runTest {
        val store = InMemoryGrantStore()
        val writer = SerialWriter(backgroundScope)
        val manager = GrantManager(store, clock, writer, SequentialIds())
        val issued = manager.request(INSTAGRAM)
        assertTrue(manager.end(INSTAGRAM))
        writer.flush()
        assertEquals(GrantState.ENDED_EARLY, store.rows.getValue(issued.grant.id).state)
        assertNull(manager.activeGrant(INSTAGRAM))
    }
}
