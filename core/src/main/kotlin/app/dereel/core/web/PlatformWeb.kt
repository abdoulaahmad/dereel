package app.dereel.core.web

import app.dereel.core.model.Capabilities
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform

/** Accounts outside Dereel's platforms that a platform's sign-in flow can depend on (FR-19). */
enum class LinkedAccount { FACEBOOK, GOOGLE }

/**
 * Data that "Sign out" or "Clear browser data" removes for one platform (FR-18, FR-19, FR-35).
 * The lists are disclosed in the confirmation before anything is cleared.
 */
data class BrowserDataScope(
    val platform: Platform,
    val cookieDomains: List<String>,
    val storageOrigins: List<String>,
    val linkedAccounts: List<LinkedAccount>,
)

/**
 * Documented entry routes, origins and authentication hosts for each platform (FR-12).
 * A platform with no "inside Dereel" method (Snapchat) has no hosts, so every URL is external to
 * it. Check [hasBrowser] before asking for its entry URL.
 */
object PlatformWeb {
    fun hasBrowser(platform: Platform): Boolean = Capabilities.supports(platform, OpeningMethod.IN_DEREEL)

    fun entryUrl(platform: Platform): String = when (platform) {
        Platform.INSTAGRAM -> "https://www.instagram.com/"
        Platform.YOUTUBE -> "https://m.youtube.com/"
        Platform.FACEBOOK -> "https://m.facebook.com/"
        Platform.SNAPCHAT -> error("Snapchat cannot be opened inside Dereel")
    }

    /** Hosts whose pages are classified as platform routes. */
    fun contentHosts(platform: Platform): Set<String> = when (platform) {
        Platform.INSTAGRAM -> setOf("instagram.com", "www.instagram.com", "m.instagram.com")
        Platform.YOUTUBE -> setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")
        Platform.FACEBOOK -> FACEBOOK_SITES + FACEBOOK_SHORT_LINK_HOST
        Platform.SNAPCHAT -> emptySet()
    }

    /** Origins allowed to run Dereel's page rules and post bridge messages (SEC-04). */
    fun bridgeOrigins(platform: Platform): Set<String> = when (platform) {
        Platform.INSTAGRAM -> setOf("https://www.instagram.com")
        Platform.YOUTUBE -> setOf("https://m.youtube.com", "https://www.youtube.com")
        Platform.FACEBOOK -> setOf(
            "https://m.facebook.com",
            "https://www.facebook.com",
            "https://web.facebook.com",
            "https://touch.facebook.com",
        )
        Platform.SNAPCHAT -> emptySet()
    }

    /**
     * Hosts of the supported sign-in flows. Navigation to them stays inside Dereel. Everything else
     * needs an explicit choice to open outside Dereel (FR-12, INT-02).
     */
    fun authHosts(platform: Platform): Set<String> = when (platform) {
        Platform.INSTAGRAM -> setOf(
            "accountscenter.instagram.com",
            "www.facebook.com",
            "m.facebook.com",
            "facebook.com",
        )
        Platform.YOUTUBE -> setOf(
            "accounts.google.com",
            "accounts.youtube.com",
            "consent.youtube.com",
            "consent.google.com",
        )
        // Facebook signs in on its own content hosts. Only the account centre is separate.
        Platform.FACEBOOK -> setOf("accountscenter.facebook.com")
        Platform.SNAPCHAT -> emptySet()
    }

    /**
     * Facebook hosts appear in Instagram's sign-in flow ("Continue with Facebook"). They are
     * allowed there for signing in, not as a way to reach Facebook's short videos.
     */
    internal fun isFacebookSite(host: String): Boolean = host in FACEBOOK_SITES

    /** Link-redirect hosts that wrap an external destination. */
    internal fun linkShim(platform: Platform, host: String, path: String): String? = when {
        platform == Platform.INSTAGRAM && host == "l.instagram.com" -> "u"
        platform == Platform.YOUTUBE && host in contentHosts(Platform.YOUTUBE) && path == "/redirect" -> "q"
        platform == Platform.FACEBOOK && host in FACEBOOK_LINK_SHIMS && path == "/l.php" -> "u"
        else -> null
    }

    fun conversationUrl(threadId: String): String = "https://www.instagram.com/direct/t/$threadId/"

    fun dataScope(platform: Platform): BrowserDataScope = when (platform) {
        Platform.INSTAGRAM -> BrowserDataScope(
            platform = platform,
            cookieDomains = listOf("instagram.com", "facebook.com"),
            storageOrigins = listOf(
                "https://www.instagram.com",
                "https://instagram.com",
                "https://accountscenter.instagram.com",
                "https://www.facebook.com",
                "https://m.facebook.com",
            ),
            linkedAccounts = listOf(LinkedAccount.FACEBOOK),
        )
        Platform.YOUTUBE -> BrowserDataScope(
            platform = platform,
            cookieDomains = listOf("youtube.com", "google.com"),
            storageOrigins = listOf(
                "https://m.youtube.com",
                "https://www.youtube.com",
                "https://accounts.google.com",
                "https://accounts.youtube.com",
                "https://consent.youtube.com",
            ),
            linkedAccounts = listOf(LinkedAccount.GOOGLE),
        )
        Platform.FACEBOOK -> BrowserDataScope(
            platform = platform,
            cookieDomains = listOf("facebook.com"),
            storageOrigins = listOf(
                "https://m.facebook.com",
                "https://www.facebook.com",
                "https://web.facebook.com",
                "https://touch.facebook.com",
                "https://accountscenter.facebook.com",
            ),
            linkedAccounts = emptyList(),
        )
        Platform.SNAPCHAT -> BrowserDataScope(platform, emptyList(), emptyList(), emptyList())
    }

    /**
     * Other platforms whose sign-in inside Dereel ends when [platform]'s browser data is cleared,
     * because their own cookie domain is part of this platform's scope. The confirmation names
     * them (FR-19). Today: clearing Instagram also clears facebook.com.
     */
    fun alsoSignedOut(platform: Platform): List<Platform> {
        val cleared = dataScope(platform).cookieDomains.toSet()
        return Platform.entries.filter { other ->
            other != platform && dataScope(other).cookieDomains.firstOrNull() in cleared
        }
    }

    private val FACEBOOK_SITES = setOf(
        "facebook.com",
        "www.facebook.com",
        "m.facebook.com",
        "web.facebook.com",
        "touch.facebook.com",
        "mbasic.facebook.com",
    )
    internal const val FACEBOOK_SHORT_LINK_HOST = "fb.watch"
    private val FACEBOOK_LINK_SHIMS = setOf("l.facebook.com", "lm.facebook.com")
}
