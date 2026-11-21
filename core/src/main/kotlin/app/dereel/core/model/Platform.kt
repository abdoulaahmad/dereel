package app.dereel.core.model

/**
 * Platforms Dereel supports. Instagram and YouTube are the SRS first-release scope (SRS 2.1).
 * Facebook and Snapchat were added afterwards at the owner's request (docs/DECISIONS.md, S-01).
 *
 * [nativePackages] lists the installed apps Dereel can protect for the platform, in the order
 * they are preferred when opening it. An app is listed only if it has a native rule set
 * (`NativeRules`), so an app Dereel cannot recognise is never treated as "the installed app".
 * The list is empty when the platform has no native protection. [Capabilities.methods] says which
 * opening methods are offered.
 */
enum class Platform(val id: String, val nativePackages: List<String>) {
    INSTAGRAM("instagram", listOf("com.instagram.android")),
    YOUTUBE("youtube", listOf("com.google.android.youtube")),

    /** Facebook Lite only. The regular Facebook app has no rule set yet, so it is not listed. */
    FACEBOOK("facebook", listOf("com.facebook.lite")),

    /** App only: Snapchat has no mobile website that Dereel could filter. */
    SNAPCHAT("snapchat", listOf("com.snapchat.android"));

    companion object {
        fun fromId(id: String?): Platform? = entries.firstOrNull { it.id == id }

        fun forPackage(packageName: CharSequence?): Platform? {
            val name = packageName?.toString() ?: return null
            return entries.firstOrNull { name in it.nativePackages }
        }
    }
}

/** How a platform is opened: the installed social app, or the filtered browser inside Dereel. */
enum class OpeningMethod(val id: String) {
    NATIVE_APP("native"),
    IN_DEREEL("browser");

    companion object {
        fun fromId(id: String?): OpeningMethod? = entries.firstOrNull { it.id == id }
    }
}
