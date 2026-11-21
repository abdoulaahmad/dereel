package app.dereel.core.nativeapp

import app.dereel.core.model.Platform

/**
 * Shipped native rule sets (FR-39), one per supported app. Change an identifier by bumping
 * [NativeRuleSet.version] and adding or updating the matching fixture in the core tests.
 *
 * VERIFICATION STATUS: these anchors are candidates for the current Instagram, YouTube, Facebook
 * Lite and Snapchat Android layouts. docs/COMPATIBILITY.md records where each one has been
 * observed and lists it as unverified until a recorded test run exists (FR-38). The
 * layout-recognition check reports "Needs attention" rather than claiming protection when no
 * anchor is ever seen.
 *
 * An app without a rule set here (the regular Facebook app) has no native protection. It is not
 * in [Platform.nativePackages] and the accessibility service receives no events from it.
 */
object NativeRules {
    val instagram = NativeRuleSet(
        platform = Platform.INSTAGRAM,
        version = "ig-native-0.1.0",
        packageName = "com.instagram.android",
        shortVideoContainerIds = setOf("clips_viewer_view_pager"),
        shortVideoTabIds = setOf("clips_tab"),
        navigationIds = setOf("tab_bar"),
        knownScreenIds = setOf("feed_tab", "search_tab", "profile_tab", "direct_tab"),
    )

    val youtube = NativeRuleSet(
        platform = Platform.YOUTUBE,
        version = "yt-native-0.1.0",
        packageName = "com.google.android.youtube",
        shortVideoContainerIds = setOf("reel_recycler", "reel_player_page_container"),
        navigationIds = setOf("pivot_bar"),
    )

    /**
     * Facebook Lite draws most of its interface itself and exposes almost no identifiers. Its
     * full-screen video player is the exception: "video_view" fills most of the window when a
     * reel plays. A video playing inside the feed is smaller than [NativeRuleSet.minContainerFraction]
     * and is not matched. Any other video opened in the full-screen player is matched too, because
     * nothing distinguishes it from a reel.
     *
     * "main_layout" is on every screen. It is listed only so that browsing without opening a
     * video is not reported as an unrecognised layout. It cannot show that "video_view" still
     * exists in a future version.
     */
    val facebookLite = NativeRuleSet(
        platform = Platform.FACEBOOK,
        version = "fb-lite-native-0.1.0",
        packageName = "com.facebook.lite",
        shortVideoContainerIds = setOf("video_view"),
        knownScreenIds = setOf("main_layout"),
    )

    /**
     * Snapchat: Spotlight is the short-video feed. Chats, the camera, Stories and the map are not
     * matched. Snapchat has one anchor per bottom tab and no single navigation bar.
     */
    val snapchat = NativeRuleSet(
        platform = Platform.SNAPCHAT,
        version = "sc-native-0.1.0",
        packageName = "com.snapchat.android",
        shortVideoContainerIds = setOf("spotlight_container"),
        navigationIds = setOf(
            "ngs_map_icon_container",
            "ngs_chat_icon_container",
            "ngs_camera_icon_container",
            "ngs_community_icon_container",
            "ngs_spotlight_icon_container",
        ),
        navigationIsSplit = true,
    )

    val all: List<NativeRuleSet> = listOf(instagram, youtube, facebookLite, snapchat)

    /** Rule sets for a platform's installed apps. Empty when Dereel has no native detector for it. */
    fun forPlatform(platform: Platform): List<NativeRuleSet> = all.filter { it.platform == platform }

    fun forPackage(packageName: CharSequence?): NativeRuleSet? {
        val name = packageName?.toString() ?: return null
        return all.firstOrNull { it.packageName == name }
    }
}
