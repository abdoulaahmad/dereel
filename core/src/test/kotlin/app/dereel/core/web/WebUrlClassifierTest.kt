package app.dereel.core.web

import app.dereel.core.model.Platform.FACEBOOK
import app.dereel.core.model.Platform.INSTAGRAM
import app.dereel.core.model.Platform.SNAPCHAT
import app.dereel.core.model.Platform.YOUTUBE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebUrlClassifierTest {
    private fun igRoute(url: String) = (WebUrlClassifier.classify(INSTAGRAM, url) as NavTarget.PlatformPage).route
    private fun ytRoute(url: String) = (WebUrlClassifier.classify(YOUTUBE, url) as NavTarget.PlatformPage).route

    @Test
    fun `instagram routes are classified`() {
        assertEquals(RouteKind.HOME, igRoute("https://www.instagram.com/").kind)
        assertEquals(RouteKind.SHORT_VIDEO_FEED, igRoute("https://www.instagram.com/reels/").kind)
        assertEquals(RouteKind.SHORT_VIDEO_ITEM, igRoute("https://www.instagram.com/reels/C1a2B3c4D5e/").kind)
        assertEquals("C1a2B3c4D5e", igRoute("https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=abc").itemKey)
        assertEquals("C1a2B3c4D5e", igRoute("https://www.instagram.com/someone/reel/C1a2B3c4D5e/").itemKey)
        assertEquals(RouteKind.POST, igRoute("https://www.instagram.com/p/C1a2B3c4D5e/").kind)
        assertEquals(RouteKind.INBOX, igRoute("https://www.instagram.com/direct/inbox/").kind)
        val thread = igRoute("https://www.instagram.com/direct/t/1234567890/")
        assertEquals(RouteKind.CONVERSATION, thread.kind)
        assertEquals("1234567890", thread.threadId)
        assertEquals(RouteKind.EXPLORE, igRoute("https://www.instagram.com/explore/").kind)
        assertEquals(RouteKind.SEARCH, igRoute("https://www.instagram.com/explore/search/").kind)
        assertEquals(RouteKind.ACCOUNT, igRoute("https://www.instagram.com/accounts/login/").kind)
        assertEquals(RouteKind.ACCOUNT, igRoute("https://www.instagram.com/auth_platform/codeentry/").kind)
        assertEquals(RouteKind.ACCOUNT, igRoute("https://www.instagram.com/challenge/action/").kind)
        assertEquals(RouteKind.PROFILE, igRoute("https://www.instagram.com/some.user_1/").kind)
        assertEquals(RouteKind.PROFILE, igRoute("https://instagram.com/some.user_1/reels/").kind)
    }

    @Test
    fun `youtube routes are classified`() {
        assertEquals(RouteKind.HOME, ytRoute("https://m.youtube.com/").kind)
        assertEquals("abcDEF12345", ytRoute("https://m.youtube.com/shorts/abcDEF12345").itemKey)
        assertEquals(RouteKind.SHORT_VIDEO_ITEM, ytRoute("https://www.youtube.com/shorts/abcDEF12345?feature=share").kind)
        assertEquals(RouteKind.SHORT_VIDEO_FEED, ytRoute("https://m.youtube.com/shorts/").kind)
        val watch = ytRoute("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10")
        assertEquals(RouteKind.REGULAR_VIDEO, watch.kind)
        assertEquals("dQw4w9WgXcQ", watch.itemKey)
        assertEquals(RouteKind.SEARCH, ytRoute("https://m.youtube.com/results?search_query=cats").kind)
        assertEquals(RouteKind.CHANNEL, ytRoute("https://m.youtube.com/@channel").kind)
        assertEquals(RouteKind.REGULAR_VIDEO, ytRoute("https://youtu.be/dQw4w9WgXcQ").kind)
    }

    @Test
    fun `auth hosts stay inside the platform flow (FR-12)`() {
        assertTrue(WebUrlClassifier.classify(YOUTUBE, "https://accounts.google.com/ServiceLogin") is NavTarget.AuthPage)
        assertTrue(WebUrlClassifier.classify(INSTAGRAM, "https://www.facebook.com/login/") is NavTarget.AuthPage)
        // Another platform's sign-in host is not part of this platform's flow.
        assertTrue(WebUrlClassifier.classify(INSTAGRAM, "https://accounts.google.com/") is NavTarget.ExternalWeb)
    }

    @Test
    fun `external links, shims and app schemes are never opened automatically (INT-02, AT-16)`() {
        val shim = WebUrlClassifier.classify(INSTAGRAM, "https://l.instagram.com/?u=https%3A%2F%2Fexample.org%2Fpage&e=AT0")
        assertEquals(NavTarget.ExternalWeb("https://example.org/page", "example.org"), shim)
        val ytShim = WebUrlClassifier.classify(YOUTUBE, "https://www.youtube.com/redirect?q=https%3A%2F%2Fexample.org")
        assertEquals("example.org", (ytShim as NavTarget.ExternalWeb).host)
        assertEquals(NavTarget.BlockedScheme("intent"), WebUrlClassifier.classify(INSTAGRAM, "intent://reels#Intent;scheme=instagram;S.browser_fallback_url=https%3A%2F%2Fevil.example;end"))
        assertEquals(NavTarget.BlockedScheme("instagram"), WebUrlClassifier.classify(INSTAGRAM, "instagram://reels"))
        assertEquals(NavTarget.BlockedScheme("vnd.youtube"), WebUrlClassifier.classify(YOUTUBE, "vnd.youtube:abc"))
        assertEquals(NavTarget.BlockedScheme("javascript"), WebUrlClassifier.classify(YOUTUBE, "javascript:alert(1)"))
        assertEquals(NavTarget.BlockedScheme("file"), WebUrlClassifier.classify(YOUTUBE, "file:///sdcard/x.html"))
        val shimToScheme = WebUrlClassifier.classify(INSTAGRAM, "https://l.instagram.com/?u=market%3A%2F%2Fdetails%3Fid%3Dx")
        assertEquals(NavTarget.BlockedScheme("market"), shimToScheme)
    }

    @Test
    fun `lookalike hosts, userinfo tricks, ports and plain http are not platform pages`() {
        assertTrue(WebUrlClassifier.classify(INSTAGRAM, "https://instagram.com.evil.example/reels/") is NavTarget.ExternalWeb)
        assertEquals(NavTarget.Invalid, WebUrlClassifier.classify(INSTAGRAM, "https://www.instagram.com@evil.example/"))
        assertTrue(WebUrlClassifier.classify(INSTAGRAM, "https://www.instagram.com:8443/") is NavTarget.ExternalWeb)
        assertTrue(WebUrlClassifier.classify(INSTAGRAM, "http://www.instagram.com/") is NavTarget.ExternalWeb)
        assertEquals(NavTarget.Invalid, WebUrlClassifier.classify(INSTAGRAM, "not a url"))
    }

    @Test
    fun `shared links are validated before selecting a platform (INT-01)`() {
        val reel = SharedLinkParser.parse("Look at this https://www.instagram.com/reel/C1a2B3c4D5e/?igsh=xyz !")!!
        assertEquals(INSTAGRAM, reel.platform)
        assertEquals("https://www.instagram.com/reel/C1a2B3c4D5e/", reel.url)
        assertEquals(RouteKind.SHORT_VIDEO_ITEM, reel.route.kind)

        val short = SharedLinkParser.parse("https://youtu.be/dQw4w9WgXcQ?si=abc")!!
        assertEquals(YOUTUBE, short.platform)
        assertEquals("https://m.youtube.com/watch?v=dQw4w9WgXcQ", short.url)

        assertNull(SharedLinkParser.parse("http://www.instagram.com/reel/C1a2B3c4D5e/"))
        assertNull(SharedLinkParser.parse("https://instagram.com.evil.example/reel/C1a2B3c4D5e/"))
        assertNull(SharedLinkParser.parse("https://www.instagram.com/accounts/login/"))
        assertNull(SharedLinkParser.parse("no link here"))
        assertNull(SharedLinkParser.parse("intent://www.instagram.com/reel/x#Intent;end"))
    }

    @Test
    fun `thread identifiers are redacted from route string output`() {
        val route = igRoute("https://www.instagram.com/direct/t/1234567890/")
        assertTrue("1234567890" !in route.toString())
    }

    private fun fbRoute(url: String) = (WebUrlClassifier.classify(FACEBOOK, url) as NavTarget.PlatformPage).route

    @Test
    fun `facebook routes are classified`() {
        assertEquals(RouteKind.HOME, fbRoute("https://m.facebook.com/").kind)
        assertEquals(RouteKind.HOME, fbRoute("https://m.facebook.com/home.php").kind)
        val reel = fbRoute("https://www.facebook.com/reel/1234567890123456/?s=single_unit")
        assertEquals(RouteKind.SHORT_VIDEO_ITEM, reel.kind)
        assertEquals("1234567890123456", reel.itemKey)
        assertEquals(RouteKind.SHORT_VIDEO_FEED, fbRoute("https://m.facebook.com/reel/").kind)
        assertEquals(RouteKind.SHORT_VIDEO_FEED, fbRoute("https://m.facebook.com/reels/?source=shortcut").kind)
        assertEquals(RouteKind.SHORT_VIDEO_FEED, fbRoute("https://m.facebook.com/reels/create/").kind)
        assertEquals(RouteKind.SHORT_VIDEO_ITEM, fbRoute("https://www.facebook.com/share/r/1AbCdEfGhI/").kind)
        // The Video tab is an endless video feed. A named video is a regular video (A-24).
        assertEquals(RouteKind.SHORT_VIDEO_FEED, fbRoute("https://m.facebook.com/watch/").kind)
        assertEquals(RouteKind.SHORT_VIDEO_FEED, fbRoute("https://m.facebook.com/watch/?ref=tab").kind)
        val video = fbRoute("https://m.facebook.com/watch/?v=987654321012345")
        assertEquals(RouteKind.REGULAR_VIDEO, video.kind)
        assertEquals("987654321012345", video.itemKey)
        assertEquals(RouteKind.SEARCH, fbRoute("https://m.facebook.com/watch/search/?q=cats").kind)
        assertEquals(RouteKind.REGULAR_VIDEO, fbRoute("https://www.facebook.com/some.page/videos/987654321012345/").kind)
        assertEquals(RouteKind.POST, fbRoute("https://www.facebook.com/share/p/1AbCdEfGhI/").kind)
        assertEquals(RouteKind.POST, fbRoute("https://m.facebook.com/story.php?story_fbid=1&id=2").kind)
        assertEquals(RouteKind.SEARCH, fbRoute("https://m.facebook.com/search/top/?q=cats").kind)
        assertEquals(RouteKind.PROFILE, fbRoute("https://m.facebook.com/some.person").kind)
        assertEquals(RouteKind.PROFILE, fbRoute("https://m.facebook.com/some.person/reels/").kind)
        assertEquals(RouteKind.PROFILE, fbRoute("https://m.facebook.com/profile.php?id=100000000000001").kind)
        assertEquals(RouteKind.OTHER, fbRoute("https://m.facebook.com/groups/1234567890/").kind)
        assertEquals(RouteKind.OTHER, fbRoute("https://m.facebook.com/stories/1234567890/").kind)
        assertEquals(RouteKind.OTHER, fbRoute("https://fb.watch/aBcDeFgHiJ/").kind)
    }

    @Test
    fun `facebook messages never become a shared-video context (FR-20)`() {
        val thread = fbRoute("https://m.facebook.com/messages/t/1234567890/")
        assertEquals(RouteKind.INBOX, thread.kind)
        assertNull(thread.threadId)
    }

    @Test
    fun `facebook sign-in pages are account routes on its own hosts (FR-18)`() {
        listOf(
            "https://m.facebook.com/login/",
            "https://m.facebook.com/unified/login_via/app/?lid=1&wtsid=rdr_0abc",
            "https://m.facebook.com/login.php?next=https%3A%2F%2Fm.facebook.com%2F",
            "https://m.facebook.com/checkpoint/1501092823525282/",
            "https://m.facebook.com/two_step_verification/two_factor/",
            "https://www.facebook.com/v19.0/dialog/oauth?client_id=1",
            "https://www.facebook.com/privacy/consent/?flow=user_cookie_choice",
        ).forEach { assertEquals(it, RouteKind.ACCOUNT, fbRoute(it).kind) }
        assertTrue(WebUrlClassifier.classify(FACEBOOK, "https://accountscenter.facebook.com/") is NavTarget.AuthPage)
    }

    @Test
    fun `facebook link redirects unwrap to the external destination (INT-02)`() {
        val target = WebUrlClassifier.classify(FACEBOOK, "https://l.facebook.com/l.php?u=https%3A%2F%2Fexample.org%2Fa&h=AT0")
        assertEquals(NavTarget.ExternalWeb("https://example.org/a", "example.org"), target)
        assertTrue(WebUrlClassifier.classify(FACEBOOK, "https://www.instagram.com/") is NavTarget.ExternalWeb)
        assertTrue(WebUrlClassifier.classify(FACEBOOK, "http://m.facebook.com/") is NavTarget.ExternalWeb)
    }

    @Test
    fun `facebook short videos are not reachable through instagram's sign-in hosts`() {
        // "Continue with Facebook" still stays inside Dereel.
        assertTrue(WebUrlClassifier.classify(INSTAGRAM, "https://www.facebook.com/login.php") is NavTarget.AuthPage)
        assertTrue(WebUrlClassifier.classify(INSTAGRAM, "https://m.facebook.com/v19.0/dialog/oauth?client_id=1") is NavTarget.AuthPage)
        // A reel or the Video tab is not a sign-in page.
        assertTrue(WebUrlClassifier.classify(INSTAGRAM, "https://www.facebook.com/reel/1234567890123456/") is NavTarget.ExternalWeb)
        assertTrue(WebUrlClassifier.classify(INSTAGRAM, "https://m.facebook.com/watch/") is NavTarget.ExternalWeb)
    }

    @Test
    fun `snapchat has no pages inside Dereel`() {
        assertTrue(WebUrlClassifier.classify(SNAPCHAT, "https://www.snapchat.com/spotlight") is NavTarget.ExternalWeb)
        assertNull(SharedLinkParser.parse("https://www.snapchat.com/spotlight/W7_EDlXWTBiXAEEniNoMPwAAY"))
    }

    @Test
    fun `shared facebook links open in the facebook browser (INT-01)`() {
        val reel = SharedLinkParser.parse("Look https://www.facebook.com/share/r/1AbCdEfGhI/?mibextid=xyz")!!
        assertEquals(FACEBOOK, reel.platform)
        assertTrue(reel.route.isShortVideo)
        val video = SharedLinkParser.parse("https://www.facebook.com/watch/?v=987654321012345")!!
        assertEquals(FACEBOOK, video.platform)
        assertEquals(RouteKind.REGULAR_VIDEO, video.route.kind)
        // Not a supported journey.
        assertNull(SharedLinkParser.parse("https://www.facebook.com/groups/1234567890/"))
    }
}
