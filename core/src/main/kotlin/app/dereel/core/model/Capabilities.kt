package app.dereel.core.model

/** What an opening method can do for a platform in this release (SRS 2.3, UX-05). */
data class MethodCapabilities(
    val shortVideoBlocking: Boolean,
    val sharedItemException: Boolean,
    val recommendationControls: Boolean,
    val adFiltering: Boolean,
)

object Capabilities {
    private val NONE = MethodCapabilities(
        shortVideoBlocking = false,
        sharedItemException = false,
        recommendationControls = false,
        adFiltering = false,
    )

    /**
     * Opening methods Dereel offers for a platform, in display order. A method is listed only
     * when Dereel has an adapter for it, so an unsupported method is never shown as protection.
     */
    fun methods(platform: Platform): List<OpeningMethod> = when (platform) {
        Platform.INSTAGRAM, Platform.YOUTUBE, Platform.FACEBOOK -> listOf(OpeningMethod.NATIVE_APP, OpeningMethod.IN_DEREEL)
        Platform.SNAPCHAT -> listOf(OpeningMethod.NATIVE_APP)
    }

    fun supports(platform: Platform, method: OpeningMethod): Boolean = method in methods(platform)

    /** The platform's only opening method, when there is nothing for the user to choose. */
    fun onlyMethod(platform: Platform): OpeningMethod? = methods(platform).singleOrNull()

    fun of(platform: Platform, method: OpeningMethod): MethodCapabilities = when {
        !supports(platform, method) -> NONE
        // FR-11 and D-05: native shared-item exceptions stay unavailable until an adapter is
        // verified against tested app versions, so they are never advertised.
        method == OpeningMethod.NATIVE_APP -> MethodCapabilities(
            shortVideoBlocking = true,
            sharedItemException = false,
            recommendationControls = false,
            adFiltering = false,
        )
        // FR-20 covers the Instagram shared-reel journey only. YouTube and Facebook have no
        // browser exception. Recommendation controls exist only where a control is defined.
        else -> MethodCapabilities(
            shortVideoBlocking = true,
            sharedItemException = platform == Platform.INSTAGRAM,
            recommendationControls = BrowserControl.forPlatform(platform).isNotEmpty(),
            adFiltering = BrowserControl.forPlatform(platform).any { it.bestEffort },
        )
    }

    fun sharedItemAvailable(platform: Platform): Boolean =
        OpeningMethod.entries.any { of(platform, it).sharedItemException }
}
