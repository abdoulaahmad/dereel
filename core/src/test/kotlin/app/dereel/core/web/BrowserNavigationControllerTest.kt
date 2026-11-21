package app.dereel.core.web

import app.dereel.core.ManualTime
import app.dereel.core.model.Platform
import app.dereel.core.policy.AllowReason
import app.dereel.core.policy.DenyReason
import app.dereel.core.policy.PolicyDecision
import app.dereel.core.policy.SurfaceKind
import app.dereel.core.shared.ShareOrigin
import app.dereel.core.shared.SharedItemMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserNavigationControllerTest {
    /** Fake policy: blocks short videos unless [allowAll] or the item is a recognized shared item. */
    private class FakePolicy : BrowserPolicyPort {
        var allowAll = false
        var exhausted = false
        val reports = mutableListOf<Pair<SurfaceKind, SharedItemMatch?>>()
        var clears = 0

        private fun decide(shared: SharedItemMatch?): PolicyDecision = when {
            exhausted -> PolicyDecision.Deny(DenyReason.ALLOWANCE_EXHAUSTED)
            allowAll -> PolicyDecision.Allow(AllowReason.ALLOWANCE, 60_000)
            shared != null -> PolicyDecision.Allow(AllowReason.SHARED_ITEM)
            else -> PolicyDecision.Deny(DenyReason.BLOCKED)
        }

        override fun evaluate(surface: SurfaceKind, sharedItem: SharedItemMatch?) = decide(sharedItem)
        override fun report(surface: SurfaceKind, sharedItem: SharedItemMatch?): PolicyDecision {
            reports += surface to sharedItem
            return decide(sharedItem)
        }
        override fun clear() {
            clears++
        }
    }

    private val time = ManualTime()
    private val policy = FakePolicy()
    private val ig = BrowserNavigationController(Platform.INSTAGRAM, policy, { time.nowMs })

    private val home = "https://www.instagram.com/"
    private val thread = "https://www.instagram.com/direct/t/1234567890/"
    private val reelA = "https://www.instagram.com/reel/ReelAAAAA1/"
    private val reelB = "https://www.instagram.com/reels/ReelBBBBB2/"
    private val reelsFeed = "https://www.instagram.com/reels/"

    @Test
    fun `entry route loads the documented https home (FR-12)`() {
        assertEquals(listOf(BrowserCommand.Load(home)), ig.start(null, viaSharedLink = false))
    }

    @Test
    fun `denied direct link loads nothing and offers a safe route (FR-17, AT-05)`() {
        val commands = ig.start(reelsFeed, viaSharedLink = false)
        assertEquals(listOf(BrowserCommand.ShowBlocked(BlockNotice(DenyReason.BLOCKED, BlockOutcome.NOTHING_LOADED))), commands)
    }

    @Test
    fun `link request to a blocked route is stopped before it loads (FR-15)`() {
        ig.onRouteCommitted(home, null, inPage = false)
        val result = ig.onNavigationRequest(reelsFeed, isMainFrame = true, hasGesture = true)
        assertTrue(result.block)
        assertEquals(BlockOutcome.STAYED_ON_PAGE, (result.commands.single() as BrowserCommand.ShowBlocked).notice.outcome)
    }

    @Test
    fun `in-page route change to reels goes back once to the last valid page (FR-13, AT-05)`() {
        ig.onRouteCommitted(home, null, inPage = false)
        val commands = ig.onRouteCommitted(reelsFeed, previousEntryUrl = home, inPage = true)
        assertEquals(BrowserCommand.GoBack, commands.first())
        assertEquals(BlockOutcome.WENT_BACK, (commands[1] as BrowserCommand.ShowBlocked).notice.outcome)

        // onPageStarted and doUpdateVisitedHistory can both fire for the same URL.
        assertEquals(emptyList<BrowserCommand>(), ig.onRouteCommitted(reelsFeed, previousEntryUrl = home, inPage = true))
        assertTrue(ig.onRouteCommitted(home, null, inPage = true).isEmpty())
    }

    @Test
    fun `forward into a denied route with no valid previous entry loads the last safe page (FR-17)`() {
        ig.onRouteCommitted("https://www.instagram.com/explore/search/", null, inPage = false)
        val commands = ig.onRouteCommitted(reelA, previousEntryUrl = reelB, inPage = true)
        assertEquals(BrowserCommand.Load("https://www.instagram.com/explore/search/"), commands.first())
    }

    @Test
    fun `refreshing an allowed page never reloads it (FR-15)`() {
        policy.allowAll = true
        ig.onRouteCommitted(home, null, inPage = false)
        assertTrue(ig.onRouteCommitted(reelsFeed, home, inPage = true).isEmpty())
        repeat(3) { assertTrue(ig.onRouteCommitted(reelsFeed, home, inPage = false).isEmpty()) }
    }

    @Test
    fun `repeated corrections end in an error state instead of looping (FR-17, NFR-08)`() {
        ig.onRouteCommitted(home, null, inPage = false)
        repeat(BrowserNavigationController.MAX_CORRECTIONS_PER_WINDOW) {
            ig.onRouteCommitted(if (it % 2 == 0) reelA else reelB, home, inPage = true)
        }
        val commands = ig.onRouteCommitted(reelsFeed, home, inPage = true)
        assertEquals(listOf(BrowserCommand.ShowError(BrowserErrorKind.REDIRECT_LOOP)), commands)
        assertTrue(ig.halted)
        assertEquals(listOf(BrowserCommand.Load(home)), ig.resetLoopGuard())
        assertFalse(ig.halted)
    }

    @Test
    fun `loop guard window expires`() {
        ig.onRouteCommitted(home, null, inPage = false)
        repeat(10) {
            time.advance(BrowserNavigationController.LOOP_WINDOW_MS)
            ig.onRouteCommitted(home, null, inPage = true)
            assertEquals(BrowserCommand.GoBack, ig.onRouteCommitted(reelA, home, inPage = true).first())
        }
    }

    @Test
    fun `shared reel from a conversation plays and advancing returns to the conversation (FR-20, FR-21, AT-06)`() {
        ig.onRouteCommitted(thread, null, inPage = false)
        assertFalse(ig.onNavigationRequest(reelA, isMainFrame = true, hasGesture = true).block)
        assertTrue(ig.onRouteCommitted(reelA, thread, inPage = true).isEmpty())
        val match = policy.reports.last().second!!
        assertEquals(SharedItemMatch(ShareOrigin.Conversation("1234567890"), "ReelAAAAA1"), match)

        // Re-renders and replays of the same item keep the exception (FR-22).
        assertTrue(ig.onRouteCommitted(reelA, thread, inPage = true).isEmpty())

        val commands = ig.onRouteCommitted(reelB, previousEntryUrl = reelA, inPage = true)
        assertEquals(BrowserCommand.Load(thread), commands.first())
        val notice = (commands[1] as BrowserCommand.ShowBlocked).notice
        assertEquals(BlockOutcome.RETURNED_TO_CONVERSATION, notice.outcome)
        assertTrue(notice.sharedItemEnded)
    }

    @Test
    fun `reel opened from the home feed gets no shared exception (AT-07)`() {
        ig.onRouteCommitted(home, null, inPage = false)
        val commands = ig.onRouteCommitted(reelA, home, inPage = true)
        assertEquals(BrowserCommand.GoBack, commands.first())
    }

    @Test
    fun `exhausted allowance denies the shared item too (POL-03, AT-09)`() {
        ig.onRouteCommitted(thread, null, inPage = false)
        policy.exhausted = true
        val commands = ig.onRouteCommitted(reelA, thread, inPage = true)
        assertEquals(DenyReason.ALLOWANCE_EXHAUSTED, (commands[1] as BrowserCommand.ShowBlocked).notice.reason)
    }

    @Test
    fun `allowance running out while watching triggers a correction (FR-27)`() {
        policy.allowAll = true
        ig.onRouteCommitted(home, null, inPage = false)
        ig.onRouteCommitted(reelsFeed, home, inPage = true)
        policy.allowAll = false
        policy.exhausted = true
        val commands = ig.onPolicyChanged(previousEntryUrl = home)
        assertEquals(BrowserCommand.GoBack, commands.first())
    }

    @Test
    fun `external links need confirmation and app schemes are refused (INT-02, AT-16)`() {
        val external = ig.onNavigationRequest("https://example.org/", isMainFrame = true, hasGesture = true)
        assertTrue(external.block)
        assertEquals(BrowserCommand.OfferExternal("https://example.org/", "example.org"), external.commands.single())

        val intent = ig.onNavigationRequest("intent://x#Intent;S.browser_fallback_url=https://evil.example;end", true, false)
        assertTrue(intent.block)
        assertEquals(BrowserCommand.AppLinkBlocked, intent.commands.single())

        assertTrue(ig.onNavigationRequest("instagram://reels", isMainFrame = false, hasGesture = false).block)
        assertTrue(ig.onNavigationRequest("intent://x#Intent;end", isMainFrame = false, hasGesture = false).block)
        assertTrue(ig.onNavigationRequest("market://details?id=x", isMainFrame = false, hasGesture = false).block)
        assertFalse(ig.onNavigationRequest("https://www.instagram.com/static/frame.html", isMainFrame = false, hasGesture = false).block)
    }

    @Test
    fun `embedded frames may use browser-internal schemes needed by sign-in checks (FR-12, FR-18)`() {
        // These schemes cannot open another app, and embedded sign-in checks may rely on them.
        // Relaxed while debugging a failed Instagram login on a Pixel 9. The device log did not
        // show whether this rule was the cause.
        val frames = listOf(
            "about:blank",
            "about:srcdoc",
            "blob:https://www.instagram.com/0b0e7a52-0000-4000-8000-000000000000",
            "data:text/html;base64,PGh0bWw+PC9odG1sPg==",
            "data:text/html,<html><body>not a valid URI</body></html>",
            "https://www.fbsbx.com/captcha/recaptcha/iframe/",
            "https://www.google.com/recaptcha/api2/anchor",
        )
        frames.forEach { assertFalse("blocked $it", ig.onNavigationRequest(it, isMainFrame = false, hasGesture = false).block) }
    }

    @Test
    fun `authentication pages are allowed and count as an account change`() {
        assertFalse(ig.onNavigationRequest("https://www.facebook.com/login/", true, true).block)
        ig.onRouteCommitted(thread, null, inPage = false)
        ig.onRouteCommitted(reelA, thread, inPage = true)
        ig.onRouteCommitted("https://www.facebook.com/login/", null, inPage = false)
        assertEquals(null, ig.activeSharedItem)
    }

    @Test
    fun `backgrounding clears measurement and foregrounding reports again (INT-03)`() {
        policy.allowAll = true
        ig.onRouteCommitted(reelsFeed, null, inPage = false)
        val before = policy.clears
        ig.onBackground()
        assertEquals(before + 1, policy.clears)
        val reportsBefore = policy.reports.size
        ig.onForeground(previousEntryUrl = null)
        assertEquals(reportsBefore + 1, policy.reports.size)
    }

    @Test
    fun `closing the browser ends the shared exception (FR-23)`() {
        ig.onRouteCommitted(thread, null, inPage = false)
        ig.onRouteCommitted(reelA, thread, inPage = true)
        ig.onClosed()
        assertEquals(null, ig.activeSharedItem)
    }

    @Test
    fun `youtube shorts are blocked while regular videos are untouched (FR-14)`() {
        val yt = BrowserNavigationController(Platform.YOUTUBE, policy, { time.nowMs })
        yt.onRouteCommitted("https://m.youtube.com/", null, inPage = false)
        assertTrue(yt.onRouteCommitted("https://m.youtube.com/watch?v=dQw4w9WgXcQ", "https://m.youtube.com/", inPage = true).isEmpty())
        val commands = yt.onRouteCommitted("https://m.youtube.com/shorts/abcDEF12345", "https://m.youtube.com/watch?v=dQw4w9WgXcQ", inPage = true)
        assertEquals(BrowserCommand.GoBack, commands.first())
        // YouTube has no shared exception: even after a "conversation" there is nothing to grant.
        assertTrue(policy.reports.none { it.second != null })
    }

    @Test
    fun `facebook reels and the video tab are blocked while a named video is untouched (FR-14)`() {
        val fb = BrowserNavigationController(Platform.FACEBOOK, policy, { time.nowMs })
        val fbHome = "https://m.facebook.com/"
        val video = "https://m.facebook.com/watch/?v=987654321012345"
        val reel = "https://m.facebook.com/reel/1234567890123456/"
        assertEquals(listOf(BrowserCommand.Load(fbHome)), fb.start(null, viaSharedLink = false))
        fb.onRouteCommitted(fbHome, null, inPage = false)

        assertTrue(fb.onNavigationRequest(reel, isMainFrame = true, hasGesture = true).block)
        assertTrue(fb.onNavigationRequest("https://m.facebook.com/watch/", isMainFrame = true, hasGesture = true).block)
        assertFalse(fb.onNavigationRequest(video, isMainFrame = true, hasGesture = true).block)
        assertTrue(fb.onRouteCommitted(video, fbHome, inPage = true).isEmpty())

        // An in-page move to the Reels feed goes back once.
        val commands = fb.onRouteCommitted("https://m.facebook.com/reel/?s=tab", video, inPage = true)
        assertEquals(BrowserCommand.GoBack, commands.first())

        // A reel opened from messages gets no shared-video exception on Facebook.
        fb.onRouteCommitted("https://m.facebook.com/messages/t/1234567890/", null, inPage = false)
        assertTrue(fb.onNavigationRequest(reel, isMainFrame = true, hasGesture = true).block)
        assertEquals(null, fb.activeSharedItem)
        assertTrue(policy.reports.none { it.second != null })
    }

    @Test
    fun `a denied shared facebook reel link loads nothing (INT-01, FR-17)`() {
        val fb = BrowserNavigationController(Platform.FACEBOOK, policy, { time.nowMs })
        // The real policy never grants the exception for Facebook. Model that here.
        policy.exhausted = true
        val commands = fb.start("https://www.facebook.com/share/r/1AbCdEfGhI/", viaSharedLink = true)
        assertTrue(commands.single() is BrowserCommand.ShowBlocked)
    }
}
