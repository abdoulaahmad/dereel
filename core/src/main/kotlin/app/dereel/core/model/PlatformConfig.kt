package app.dereel.core.model

/**
 * Optional daily short-video allowance (FR-24). The limit is kept when the allowance is
 * disabled so it can be re-enabled without choosing again (POL-07, FR-30).
 */
data class AllowanceSetting(
    val enabled: Boolean = false,
    val minutes: Int? = null,
) {
    init {
        require(minutes == null || minutes in MIN_MINUTES..MAX_MINUTES) {
            "Allowance must be between $MIN_MINUTES and $MAX_MINUTES minutes"
        }
        require(!enabled || minutes != null) { "An enabled allowance needs a limit" }
    }

    val limitMs: Long? get() = if (enabled && minutes != null) minutes * MINUTE_MS else null

    fun enable(newMinutes: Int): AllowanceSetting = AllowanceSetting(enabled = true, minutes = newMinutes)

    fun disable(): AllowanceSetting = copy(enabled = false)

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 180
        const val MINUTE_MS = 60_000L

        /** Proposed suggestions, subject to Decision D-04. */
        val SUGGESTED_MINUTES = listOf(5, 15, 30)

        fun parseCustomMinutes(input: String): Int? =
            input.trim().toIntOrNull()?.takeIf { it in MIN_MINUTES..MAX_MINUTES }
    }
}

/** Browser-only controls shown under "More controls" (UX-05, FR-16). */
enum class BrowserControl(val key: String, val platform: Platform, val bestEffort: Boolean = false) {
    INSTAGRAM_HIDE_EXPLORE("ig_hide_explore", Platform.INSTAGRAM),
    INSTAGRAM_HIDE_SUGGESTED("ig_hide_suggested", Platform.INSTAGRAM),
    INSTAGRAM_FILTER_SPONSORED("ig_filter_sponsored", Platform.INSTAGRAM, bestEffort = true),
    YOUTUBE_HIDE_HOME_FEED("yt_hide_home_feed", Platform.YOUTUBE),
    YOUTUBE_HIDE_WATCH_NEXT("yt_hide_watch_next", Platform.YOUTUBE),
    YOUTUBE_FILTER_PROMOTED("yt_filter_promoted", Platform.YOUTUBE, bestEffort = true);

    companion object {
        fun forPlatform(platform: Platform): List<BrowserControl> = entries.filter { it.platform == platform }

        fun defaultsFor(platform: Platform): Set<BrowserControl> = forPlatform(platform).toSet()

        fun encode(controls: Set<BrowserControl>): String =
            controls.sortedBy { it.ordinal }.joinToString(",") { it.key }

        fun decode(platform: Platform, raw: String): Set<BrowserControl> =
            raw.split(',')
                .mapNotNull { key -> entries.firstOrNull { it.key == key.trim() } }
                .filter { it.platform == platform }
                .toSet()
    }
}

/**
 * Per-platform rules and opening method, shared by both opening methods (FR-02, DATA-02).
 * [openingMethod] is null until the user chooses, except for a platform with a single supported
 * method, where there is nothing to choose.
 */
data class PlatformConfig(
    val platform: Platform,
    val selected: Boolean = false,
    val openingMethod: OpeningMethod? = Capabilities.onlyMethod(platform),
    val blockShortVideos: Boolean = true,
    val allowSharedVideos: Boolean = true,
    val allowance: AllowanceSetting = AllowanceSetting(),
    /** Optional watch-then-break pacing (S-02). */
    val interval: IntervalSetting = IntervalSetting(),
    val browserControls: Set<BrowserControl> = BrowserControl.defaultsFor(platform),
    val revision: Long = 0,
) {
    init {
        require(browserControls.all { it.platform == platform }) { "Browser control belongs to another platform" }
        require(openingMethod == null || Capabilities.supports(platform, openingMethod)) {
            "$openingMethod is not offered for $platform"
        }
    }

    companion object {
        fun defaultFor(platform: Platform): PlatformConfig = PlatformConfig(platform)

        /**
         * A stored method that is no longer offered (or was never chosen) becomes the platform's
         * only method, or "not chosen" when there are two to choose from.
         */
        fun validMethod(platform: Platform, stored: OpeningMethod?): OpeningMethod? =
            stored?.takeIf { Capabilities.supports(platform, it) } ?: Capabilities.onlyMethod(platform)
    }
}

/** App-wide preferences. */
data class AppPreferences(
    val onboardingComplete: Boolean = false,
    /** UX-10, FR-29: temporary access is offered only when the user turns it on. */
    val temporaryAccessEnabled: Boolean = false,
    /** Lets status distinguish "never set up" from "access was turned off" (AT-13). */
    val nativeProtectionEverConnected: Boolean = false,
    /** Optional nightly block for every platform (S-02). */
    val sleep: SleepSchedule = SleepSchedule(),
    /** Post a notification when app protection stops working (S-02). Off until the user turns it on. */
    val protectionAlerts: Boolean = false,
    /** Show remaining time on screen while short videos are allowed (S-02). */
    val liveTimer: Boolean = true,
)
