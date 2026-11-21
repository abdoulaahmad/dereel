package app.dereel.core.nativeapp

import app.dereel.core.FakeNode
import app.dereel.core.PHONE_WINDOW
import app.dereel.core.SearchableNode
import app.dereel.core.policy.SurfaceKind
import app.dereel.core.status.LayoutRecognition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detector fixtures (FR-39). These trees are SYNTHETIC: they model the anchors in [NativeRules].
 * They are not captures from a device. Device-captured fixtures must be added when the rules are
 * verified (docs/COMPATIBILITY.md).
 */
class NativeDetectionTest {
    private val ig = NativeSurfaceDetector(NativeRules.instagram)
    private val yt = NativeSurfaceDetector(NativeRules.youtube)
    private val sc = NativeSurfaceDetector(NativeRules.snapchat)
    private val fbLite = NativeSurfaceDetector(NativeRules.facebookLite)
    private fun fbLiteId(id: String) = "com.facebook.lite:id/$id"

    /**
     * Facebook Lite screens. The identifier names and positions were read from Facebook Lite
     * 539.0.0.7.106 on a Pixel 9 (1080 x 2424). Everything else in Facebook Lite has no identifier.
     */
    private fun fbLiteScreen(vararg content: FakeNode) = FakeNode(
        bounds = scWindow,
        children = listOf(
            FakeNode(
                fbLiteId("root_layout"), scWindow,
                children = listOf(FakeNode(fbLiteId("main_layout"), Bounds(0, 173, 1080, 2361), children = content.toList())),
            ),
        ),
    )

    private fun igId(id: String) = "com.instagram.android:id/$id"
    private fun ytId(id: String) = "com.google.android.youtube:id/$id"
    private fun scId(id: String) = "com.snapchat.android:id/$id"
    private val scWindow = Bounds(0, 0, 1080, 2424)

    /**
     * Snapchat's bottom tabs: one anchor per tab and no bar around them. The identifier names and
     * positions were read from Snapchat on a Pixel 9 (1080 x 2424). The tree itself is hand-built.
     */
    private fun scTabs() = listOf(
        FakeNode(scId("ngs_map_icon_container"), Bounds(0, 2198, 241, 2356)),
        FakeNode(scId("ngs_chat_icon_container"), Bounds(241, 2198, 440, 2356)),
        FakeNode(scId("ngs_camera_icon_container"), Bounds(440, 2198, 639, 2356)),
        FakeNode(scId("ngs_community_icon_container"), Bounds(639, 2198, 838, 2356)),
        FakeNode(scId("ngs_spotlight_icon_container"), Bounds(838, 2198, 1079, 2356)),
    )
    private val tabBar = Bounds(0, 2250, 1080, 2400)

    /** Device lookup and fallback traversal must agree on every fixture. */
    private fun detectBoth(detector: NativeSurfaceDetector, tree: FakeNode, window: Bounds): Detection {
        val traversed = detector.detect(tree, window)
        val looked = detector.detect(SearchableNode(tree), window)
        assertEquals("lookup and traversal disagree", traversed, looked)
        return traversed
    }

    private fun igScreen(content: FakeNode, reelsTabSelected: Boolean = false) = FakeNode(
        children = listOf(
            content,
            FakeNode(
                igId("tab_bar"), tabBar,
                children = listOf(
                    FakeNode(igId("feed_tab"), Bounds(0, 2250, 216, 2400)),
                    FakeNode(igId("clips_tab"), Bounds(432, 2250, 648, 2400), isSelected = reelsTabSelected),
                ),
            ),
        ),
    )

    @Test
    fun `instagram reels viewer is detected and the tab bar stays uncovered (FR-06, FR-07)`() {
        val tree = igScreen(FakeNode(igId("clips_viewer_view_pager"), Bounds(0, 0, 1080, 2400)))
        val detection = detectBoth(ig, tree, PHONE_WINDOW)
        assertEquals(SurfaceKind.SHORT_VIDEO, detection.surface)
        assertEquals(Bounds(0, 0, 1080, 2250), detection.contentRegion)
        assertEquals(tabBar, detection.navigationRegion)
        assertEquals("clips_viewer_view_pager", detection.matchedAnchor)
    }

    @Test
    fun `selected reels tab counts even without the pager anchor`() {
        val detection = detectBoth(ig, igScreen(FakeNode(igId("unknown_container")), reelsTabSelected = true), PHONE_WINDOW)
        assertEquals(SurfaceKind.SHORT_VIDEO, detection.surface)
        assertEquals(Bounds(0, 0, 1080, 2250), detection.contentRegion)
    }

    @Test
    fun `home feed with an inline reel preview is not blocked (FR-06)`() {
        val inline = FakeNode(igId("clips_viewer_view_pager"), Bounds(0, 600, 1080, 1500))
        val detection = detectBoth(ig, igScreen(FakeNode(children = listOf(inline))), PHONE_WINDOW)
        assertEquals(SurfaceKind.OTHER, detection.surface)
        assertTrue(detection.layoutRecognized)
    }

    @Test
    fun `hidden reels fragment kept in the hierarchy is ignored`() {
        val hidden = FakeNode(igId("clips_viewer_view_pager"), Bounds(0, 0, 1080, 2400), isVisibleToUser = false)
        assertEquals(SurfaceKind.OTHER, detectBoth(ig, igScreen(hidden), PHONE_WINDOW).surface)
    }

    @Test
    fun `messages screen without anchors is untouched (FR-06)`() {
        val directThread = FakeNode(children = listOf(FakeNode("com.instagram.android:id/row_thread_composer_edittext")))
        val detection = detectBoth(ig, directThread, PHONE_WINDOW)
        assertEquals(SurfaceKind.OTHER, detection.surface)
        assertFalse(detection.layoutRecognized)
    }

    @Test
    fun `anchors from another package do not match`() {
        val foreign = FakeNode("com.example.other:id/clips_viewer_view_pager", PHONE_WINDOW)
        assertEquals(SurfaceKind.OTHER, detectBoth(ig, foreign, PHONE_WINDOW).surface)
    }

    @Test
    fun `youtube shorts player is detected and the regular player is not (FR-06, AT-03)`() {
        val shorts = FakeNode(children = listOf(FakeNode(ytId("reel_recycler"), Bounds(0, 0, 1080, 2250)), FakeNode(ytId("pivot_bar"), tabBar)))
        assertEquals(SurfaceKind.SHORT_VIDEO, detectBoth(yt, shorts, PHONE_WINDOW).surface)

        val watch = FakeNode(children = listOf(FakeNode(ytId("watch_player"), Bounds(0, 0, 1080, 610)), FakeNode(ytId("pivot_bar"), tabBar)))
        val detection = detectBoth(yt, watch, PHONE_WINDOW)
        assertEquals(SurfaceKind.OTHER, detection.surface)
        assertTrue(detection.layoutRecognized)
    }

    @Test
    fun `traversal is bounded on very large trees`() {
        var node = FakeNode(igId("clips_viewer_view_pager"), PHONE_WINDOW)
        repeat(200) { node = FakeNode(children = listOf(node)) }
        val bounded = NativeSurfaceDetector(NativeRules.instagram, maxNodes = 1_500, maxDepth = 48)
        assertEquals("anchor below the depth limit is not reached by traversal", SurfaceKind.OTHER, bounded.detect(node, PHONE_WINDOW).surface)
    }

    @Test
    fun `one back per episode, then a cover once the surface is confirmed still present (FR-10)`() {
        val episode = DenyEpisodeController(cooldownMs = 1_000, confirmMs = 500)
        assertEquals(EnforcementAction.BACK, episode.onDenied(0))
        assertEquals(EnforcementAction.WAIT, episode.onDenied(400))
        assertEquals(1_000L, episode.freshCheckDueAt())
        assertEquals("first fresh check only starts confirmation", EnforcementAction.WAIT, episode.onDenied(1_000))
        assertEquals(1_500L, episode.freshCheckDueAt())
        assertEquals(EnforcementAction.WAIT, episode.onDenied(1_200))
        assertEquals(EnforcementAction.COVER, episode.onDenied(1_500))
        assertEquals(null, episode.freshCheckDueAt())
        assertEquals(EnforcementAction.KEEP, episode.onDenied(5_000))
        episode.onSurfaceGone()
        assertEquals(EnforcementAction.BACK, episode.onDenied(6_000))
    }

    @Test
    fun `a stale surface that clears after Back never gets a cover or a second Back (FR-10)`() {
        val episode = DenyEpisodeController(cooldownMs = 1_000, confirmMs = 500)
        assertEquals(EnforcementAction.BACK, episode.onDenied(0))
        // The app still reports the old view for a moment after leaving it.
        assertEquals(EnforcementAction.WAIT, episode.onDenied(1_100))
        episode.onSurfaceGone()
        assertEquals(null, episode.freshCheckDueAt())
    }

    @Test
    fun `lookup finds anchors below the traversal depth limit`() {
        var node = FakeNode("com.instagram.android:id/clips_viewer_view_pager", PHONE_WINDOW)
        repeat(200) { node = FakeNode(children = listOf(node)) }
        val detection = NativeSurfaceDetector(NativeRules.instagram).detect(SearchableNode(node), PHONE_WINDOW)
        assertEquals(SurfaceKind.SHORT_VIDEO, detection.surface)
    }

    @Test
    fun `an app that keeps reopening reels cannot cause a back loop (FR-10, NFR-08)`() {
        val episode = DenyEpisodeController(cooldownMs = 1_000, maxBacksPerWindow = 3, windowMs = 30_000)
        val actions = (0 until 6).map { i ->
            val action = episode.onDenied(i * 2_000L)
            episode.onSurfaceGone()
            action
        }
        assertEquals(List(3) { EnforcementAction.BACK } + List(3) { EnforcementAction.COVER }, actions)
        assertEquals(EnforcementAction.BACK, episode.onDenied(60_000))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cooldown below one second is rejected (FR-10)`() {
        DenyEpisodeController(cooldownMs = 500)
    }

    @Test
    fun `layout is flagged only after repeated long unrecognized visits (FR-37)`() {
        val tracker = LayoutRecognitionTracker(minVisitMs = 15_000, minSnapshots = 5, visitsToFlag = 2)
        fun visit(start: Long, recognized: Boolean, length: Long = 20_000) {
            tracker.onVisitStart(start)
            repeat(6) { tracker.onSnapshot(recognized) }
            tracker.onVisitEnd(start + length)
        }
        visit(0, recognized = false, length = 5_000)
        assertEquals(LayoutRecognition.UNKNOWN, tracker.state)
        visit(10_000, recognized = false)
        assertEquals(LayoutRecognition.UNKNOWN, tracker.state)
        visit(40_000, recognized = false)
        assertEquals(LayoutRecognition.NOT_RECOGNIZED, tracker.state)
        visit(80_000, recognized = true)
        assertEquals(LayoutRecognition.RECOGNIZED, tracker.state)
    }

    @Test
    fun `snapchat spotlight is detected and the tabs stay uncovered (FR-06, FR-07)`() {
        val tree = FakeNode(bounds = scWindow, children = listOf(FakeNode(scId("spotlight_container"), scWindow)) + scTabs())
        val detection = detectBoth(sc, tree, scWindow)
        assertEquals(SurfaceKind.SHORT_VIDEO, detection.surface)
        assertEquals("spotlight_container", detection.matchedAnchor)
        // The navigation region is the rectangle around all five tab anchors.
        assertEquals(Bounds(0, 2198, 1079, 2356), detection.navigationRegion)
        assertEquals(Bounds(0, 0, 1080, 2198), detection.contentRegion)
    }

    @Test
    fun `snapchat chats and its general media viewer are not blocked (FR-06)`() {
        val chat = FakeNode(bounds = scWindow, children = listOf(FakeNode(scId("synthetic_chat_list"), Bounds(0, 200, 1080, 2198))) + scTabs())
        val chatDetection = detectBoth(sc, chat, scWindow)
        assertEquals(SurfaceKind.OTHER, chatDetection.surface)
        assertTrue(chatDetection.layoutRecognized)
        assertEquals(null, chatDetection.contentRegion)

        // "opera_viewer" is Snapchat's viewer for Snaps and Stories as well, so it is not an anchor.
        val story = FakeNode(bounds = scWindow, children = listOf(FakeNode(scId("opera_viewer"), scWindow)))
        assertEquals(SurfaceKind.OTHER, detectBoth(sc, story, scWindow).surface)
    }

    @Test
    fun `a single navigation bar is never widened by stray matches`() {
        // Instagram and YouTube have one bar anchor. A second, smaller match must not stretch it.
        val stray = FakeNode(igId("tab_bar"), Bounds(0, 0, 400, 100))
        val tree = FakeNode(children = listOf(FakeNode(igId("clips_viewer_view_pager"), PHONE_WINDOW), FakeNode(igId("tab_bar"), tabBar), stray))
        assertEquals(tabBar, detectBoth(ig, tree, PHONE_WINDOW).navigationRegion)
    }

    @Test
    fun `every shipped rule set has a version, a package and a short-video anchor (FR-39)`() {
        assertEquals(4, NativeRules.all.size)
        NativeRules.all.forEach { rules ->
            assertTrue(rules.version.isNotBlank())
            assertTrue(rules.packageName.isNotBlank())
            assertTrue(rules.shortVideoContainerIds.isNotEmpty() || rules.shortVideoTabIds.isNotEmpty())
        }
        assertEquals(NativeRules.all.size, NativeRules.all.map { it.version }.toSet().size)
        assertEquals(listOf(NativeRules.facebookLite), NativeRules.forPlatform(app.dereel.core.model.Platform.FACEBOOK))
    }

    @Test
    fun `facebook lite full-screen video player is detected (FR-06, FR-07)`() {
        val player = FakeNode(fbLiteId("video_view"), Bounds(0, 307, 1080, 2227))
        val detection = detectBoth(fbLite, fbLiteScreen(player), scWindow)
        assertEquals(SurfaceKind.SHORT_VIDEO, detection.surface)
        assertEquals("video_view", detection.matchedAnchor)
        // The cover matches the player, so the bars above and below it stay reachable.
        assertEquals(Bounds(0, 307, 1080, 2227), detection.contentRegion)
    }

    @Test
    fun `facebook lite feed and a video playing inside it are not blocked (FR-06)`() {
        val feed = detectBoth(fbLite, fbLiteScreen(FakeNode(bounds = Bounds(0, 445, 1080, 2361))), scWindow)
        assertEquals(SurfaceKind.OTHER, feed.surface)
        // Browsing without opening a video still counts as a recognised layout.
        assertTrue(feed.layoutRecognized)

        // A video inside a post is far smaller than the window. This size is synthetic.
        val inline = FakeNode(fbLiteId("video_view"), Bounds(0, 900, 1080, 1508))
        assertEquals(SurfaceKind.OTHER, detectBoth(fbLite, fbLiteScreen(inline), scWindow).surface)
    }

    @Test
    fun `the regular facebook app never matches the facebook lite rules`() {
        val foreign = FakeNode("com.facebook.katana:id/video_view", Bounds(0, 307, 1080, 2227))
        assertEquals(SurfaceKind.OTHER, detectBoth(fbLite, FakeNode(bounds = scWindow, children = listOf(foreign)), scWindow).surface)
    }
}
