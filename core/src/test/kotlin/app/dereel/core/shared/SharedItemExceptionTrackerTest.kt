package app.dereel.core.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedItemExceptionTrackerTest {
    private val thread = ContextRoute.Conversation("340282366")
    private val itemA = ContextRoute.ShortVideo("ReelAAAAA1")
    private val itemB = ContextRoute.ShortVideo("ReelBBBBB2")

    private fun startedFromConversation(): SharedItemExceptionTracker {
        val tracker = SharedItemExceptionTracker()
        val change = tracker.commit(thread, itemA, userInitiated = true)
        assertTrue(change is ExceptionChange.Started)
        return tracker
    }

    @Test
    fun `opening a reel from a conversation scopes the exception to that item (FR-20)`() {
        val tracker = startedFromConversation()
        assertEquals(SharedItemMatch(ShareOrigin.Conversation("340282366"), "ReelAAAAA1"), tracker.active)
    }

    @Test
    fun `same item survives re-renders, replays and query changes (FR-21, FR-22)`() {
        val tracker = startedFromConversation()
        repeat(3) { assertEquals(ExceptionChange.None, tracker.commit(itemA, itemA, userInitiated = false)) }
        assertEquals("ReelAAAAA1", tracker.active?.itemKey)
    }

    @Test
    fun `advancing to another reel ends the exception and returns to the conversation (FR-21, AT-06)`() {
        val tracker = startedFromConversation()
        val change = tracker.commit(itemA, itemB, userInitiated = true) as ExceptionChange.Ended
        assertEquals(ExceptionEndReason.ADVANCED, change.reason)
        assertEquals(ShareOrigin.Conversation("340282366"), change.returnTo)
        assertNull(tracker.active)
        assertNull("the next reel gets no exception", tracker.preview(itemA, itemB, userInitiated = true))
    }

    @Test
    fun `entering the reels feed ends the exception`() {
        val tracker = startedFromConversation()
        val change = tracker.commit(itemA, ContextRoute.ShortVideo(null), userInitiated = true) as ExceptionChange.Ended
        assertEquals(ExceptionEndReason.ADVANCED, change.reason)
    }

    @Test
    fun `leaving the context or changing account ends the exception (FR-23)`() {
        val left = startedFromConversation().commit(itemA, thread, userInitiated = true) as ExceptionChange.Ended
        assertEquals(ExceptionEndReason.LEFT_CONTEXT, left.reason)
        assertNull(left.returnTo)

        val account = startedFromConversation().commit(itemA, ContextRoute.Account, userInitiated = false) as ExceptionChange.Ended
        assertEquals(ExceptionEndReason.ACCOUNT_CHANGED, account.reason)
    }

    @Test
    fun `reels opened from anywhere else get no exception (AT-07)`() {
        val tracker = SharedItemExceptionTracker()
        assertEquals(ExceptionChange.None, tracker.commit(ContextRoute.Other, itemA, userInitiated = true))
        assertEquals(ExceptionChange.None, tracker.commit(null, itemB, userInitiated = true))
        assertNull(tracker.active)
    }

    @Test
    fun `a non-user transition from a conversation gets no exception`() {
        val tracker = SharedItemExceptionTracker()
        assertEquals(ExceptionChange.None, tracker.commit(thread, itemA, userInitiated = false))
    }

    @Test
    fun `feed routes never start an exception even from a conversation`() {
        val tracker = SharedItemExceptionTracker()
        assertEquals(ExceptionChange.None, tracker.commit(thread, ContextRoute.ShortVideo(null), userInitiated = true))
    }

    @Test
    fun `a new tracker after process restart has no exception (FR-23, AT-12)`() {
        startedFromConversation()
        assertNull(SharedItemExceptionTracker().active)
    }

    @Test
    fun `shared link applies only to the linked item and survives sign-in`() {
        val tracker = SharedItemExceptionTracker()
        tracker.expectSharedLink("ReelAAAAA1")
        assertEquals(ExceptionChange.None, tracker.commit(null, ContextRoute.Account, userInitiated = false))
        val started = tracker.commit(ContextRoute.Account, itemA, userInitiated = false) as ExceptionChange.Started
        assertEquals(ShareOrigin.SharedLink, started.match.origin)
    }

    @Test
    fun `shared link expectation is dropped when another page opens first`() {
        val tracker = SharedItemExceptionTracker()
        tracker.expectSharedLink("ReelAAAAA1")
        tracker.commit(null, ContextRoute.Other, userInitiated = false)
        assertEquals(ExceptionChange.None, tracker.commit(ContextRoute.Other, itemA, userInitiated = true))
    }

    @Test
    fun `reset ends an active exception`() {
        val tracker = startedFromConversation()
        assertEquals(ExceptionEndReason.RESET, (tracker.reset() as ExceptionChange.Ended).reason)
        assertNull(tracker.active)
    }

    @Test
    fun `conversation identifiers are redacted from string output (FR-32)`() {
        assertTrue("340282366" !in ShareOrigin.Conversation("340282366").toString())
        assertTrue("340282366" !in thread.toString())
    }
}
