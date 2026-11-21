package app.dereel.core.web

import app.dereel.core.model.Platform
import app.dereel.core.shared.ContextRoute

enum class RouteKind {
    HOME,
    SHORT_VIDEO_FEED,
    SHORT_VIDEO_ITEM,
    INBOX,
    CONVERSATION,
    EXPLORE,
    SEARCH,
    PROFILE,
    POST,
    REGULAR_VIDEO,
    CHANNEL,
    ACCOUNT,
    OTHER,
}

/** A classified platform page. [threadId] is kept in memory only and redacted from [toString]. */
class WebRoute(
    val platform: Platform,
    val kind: RouteKind,
    val itemKey: String? = null,
    val threadId: String? = null,
) {
    val isShortVideo: Boolean get() = kind == RouteKind.SHORT_VIDEO_FEED || kind == RouteKind.SHORT_VIDEO_ITEM

    fun toContextRoute(): ContextRoute = when (kind) {
        RouteKind.SHORT_VIDEO_FEED, RouteKind.SHORT_VIDEO_ITEM -> ContextRoute.ShortVideo(itemKey)
        RouteKind.CONVERSATION -> threadId?.let { ContextRoute.Conversation(it) } ?: ContextRoute.Other
        RouteKind.ACCOUNT -> ContextRoute.Account
        else -> ContextRoute.Other
    }

    override fun equals(other: Any?): Boolean =
        other is WebRoute && other.platform == platform && other.kind == kind &&
            other.itemKey == itemKey && other.threadId == threadId

    override fun hashCode(): Int = listOf(platform, kind, itemKey, threadId).hashCode()

    override fun toString(): String = "WebRoute($platform, $kind, item=${itemKey ?: "-"})"
}

/**
 * Instagram web route rules, versioned as part of the browser rule set (FR-39).
 * These are paths as served by instagram.com. Coverage is documented in docs/COMPATIBILITY.md.
 */
internal object InstagramRoutes {
    private val SHORTCODE = Regex("^[A-Za-z0-9_-]{5,64}$")
    private val THREAD_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val USERNAME = Regex("^[A-Za-z0-9._]{1,30}$")
    // "auth_platform" hosts login checks such as CAPTCHA and code entry. It must not be mistaken
    // for a profile (seen during device testing).
    private val ACCOUNT_ROOTS = setOf("accounts", "challenge", "oauth", "login", "logout", "emailsignup", "auth_platform")
    private val RESERVED = setOf(
        "stories", "about", "legal", "developer", "web", "emails", "session", "privacy", "help", "api",
        "graphql", "ajax", "static", "data", "qp", "nametag", "directory", "your_activity", "notifications",
        "create", "lite", "invites", "ar", "threads",
    )

    fun parse(segments: List<String>): WebRoute {
        val p = Platform.INSTAGRAM
        if (segments.isEmpty()) return WebRoute(p, RouteKind.HOME)
        val first = segments[0].lowercase()
        val second = segments.getOrNull(1)
        return when {
            first == "reels" || first == "reel" ->
                if (second != null && second != "audio" && SHORTCODE.matches(second)) {
                    WebRoute(p, RouteKind.SHORT_VIDEO_ITEM, itemKey = second)
                } else {
                    WebRoute(p, RouteKind.SHORT_VIDEO_FEED)
                }
            first == "p" || first == "tv" -> WebRoute(p, RouteKind.POST, itemKey = second?.takeIf { SHORTCODE.matches(it) })
            first == "direct" -> {
                val thread = segments.getOrNull(2)
                if (second == "t" && thread != null && THREAD_ID.matches(thread)) {
                    WebRoute(p, RouteKind.CONVERSATION, threadId = thread)
                } else {
                    WebRoute(p, RouteKind.INBOX)
                }
            }
            first == "explore" -> when (second?.lowercase()) {
                null -> WebRoute(p, RouteKind.EXPLORE)
                "search", "tags", "locations" -> WebRoute(p, RouteKind.SEARCH)
                else -> WebRoute(p, RouteKind.EXPLORE)
            }
            first in ACCOUNT_ROOTS -> WebRoute(p, RouteKind.ACCOUNT)
            first in RESERVED -> WebRoute(p, RouteKind.OTHER)
            USERNAME.matches(segments[0]) -> {
                val third = segments.getOrNull(2)
                when {
                    second == "reel" && third != null && SHORTCODE.matches(third) ->
                        WebRoute(p, RouteKind.SHORT_VIDEO_ITEM, itemKey = third)
                    second == "p" && third != null -> WebRoute(p, RouteKind.POST, itemKey = third)
                    else -> WebRoute(p, RouteKind.PROFILE)
                }
            }
            else -> WebRoute(p, RouteKind.OTHER)
        }
    }
}

/** YouTube web route rules (FR-39). */
internal object YouTubeRoutes {
    private val VIDEO_ID = Regex("^[A-Za-z0-9_-]{6,20}$")
    private val CHANNEL_ROOTS = setOf("channel", "c", "user")
    private val ACCOUNT_ROOTS = setOf("account", "signin", "logout", "account_advanced")

    fun parse(segments: List<String>, query: Map<String, String>): WebRoute {
        val p = Platform.YOUTUBE
        if (segments.isEmpty()) return WebRoute(p, RouteKind.HOME)
        val first = segments[0]
        val second = segments.getOrNull(1)
        return when {
            first == "shorts" ->
                if (second != null && VIDEO_ID.matches(second)) {
                    WebRoute(p, RouteKind.SHORT_VIDEO_ITEM, itemKey = second)
                } else {
                    WebRoute(p, RouteKind.SHORT_VIDEO_FEED)
                }
            first == "watch" -> WebRoute(p, RouteKind.REGULAR_VIDEO, itemKey = query["v"]?.takeIf { VIDEO_ID.matches(it) })
            first == "results" -> WebRoute(p, RouteKind.SEARCH)
            first.startsWith("@") || first in CHANNEL_ROOTS -> WebRoute(p, RouteKind.CHANNEL)
            first in ACCOUNT_ROOTS -> WebRoute(p, RouteKind.ACCOUNT)
            else -> WebRoute(p, RouteKind.OTHER)
        }
    }

    /** youtu.be/<id> short links open a regular video. */
    fun parseShortLink(segments: List<String>): WebRoute {
        val id = segments.firstOrNull()?.takeIf { VIDEO_ID.matches(it) }
        return WebRoute(Platform.YOUTUBE, if (id != null) RouteKind.REGULAR_VIDEO else RouteKind.OTHER, itemKey = id)
    }
}

/**
 * Facebook web route rules (FR-39), as served by m.facebook.com and www.facebook.com.
 *
 * Short video on Facebook means Reels: reel pages, the Reels feed, reel share links, and the
 * Video tab ("/watch" without a named video), which is an endless video feed (assumption A-24).
 * A named video ("/watch?v=", "/<page>/videos/<id>") is a regular video. If Facebook redirects it
 * to a reel page, the reel route is what gets blocked.
 *
 * Messages are classified as an inbox only. Facebook has no shared-video exception, so no
 * conversation context is ever produced for it.
 */
internal object FacebookRoutes {
    private val NUMERIC_ID = Regex("^[0-9]{5,25}$")
    private val SHARE_TOKEN = Regex("^[A-Za-z0-9_-]{4,64}$")
    private val USERNAME = Regex("^[A-Za-z0-9.]{3,60}$")
    private val API_VERSION = Regex("^v[0-9]{1,2}[.][0-9]{1,2}$")
    private val ACCOUNT_ROOTS = setOf(
        "login", "login.php", "logout.php", "checkpoint", "recover", "reg", "r.php", "dialog", "oauth",
        "two_step_verification", "two_factor", "auth_platform", "privacy", "cookie", "confirmemail.php",
        "device-based", "login_alerts", "accounts_center",
        // "/unified/login_via/app/" is where m.facebook.com sends a signed-out visitor (seen on the emulator).
        "unified",
    )
    private val POST_PAGES = setOf("story.php", "permalink.php", "photo.php", "photo")
    private val RESERVED = setOf(
        "groups", "events", "marketplace", "friends", "notifications", "gaming", "pages", "saved", "settings",
        "help", "policies", "bookmarks", "menu", "composer", "hashtag", "stories", "ads", "business", "legal",
        "about", "memories", "fundraisers", "games", "live", "public", "people", "places", "directory",
        "ajax", "api", "tr", "plugins", "sharer", "sharer.php", "share.php", "l.php", "a", "nt", "zero",
    )

    fun parse(segments: List<String>, query: Map<String, String>): WebRoute {
        val p = Platform.FACEBOOK
        if (segments.isEmpty()) return WebRoute(p, RouteKind.HOME)
        val first = segments[0].lowercase()
        val second = segments.getOrNull(1)
        val third = segments.getOrNull(2)
        return when {
            first == "home.php" -> WebRoute(p, RouteKind.HOME)
            first == "reel" || first == "reels" -> {
                val id = second?.takeIf { NUMERIC_ID.matches(it) }
                if (id != null) WebRoute(p, RouteKind.SHORT_VIDEO_ITEM, itemKey = id) else WebRoute(p, RouteKind.SHORT_VIDEO_FEED)
            }
            first == "share" -> when {
                second?.lowercase() != "r" -> WebRoute(p, RouteKind.POST)
                third != null && SHARE_TOKEN.matches(third) -> WebRoute(p, RouteKind.SHORT_VIDEO_ITEM, itemKey = third)
                else -> WebRoute(p, RouteKind.SHORT_VIDEO_FEED)
            }
            first == "watch" -> {
                val video = query["v"]?.takeIf { NUMERIC_ID.matches(it) }
                when {
                    video != null -> WebRoute(p, RouteKind.REGULAR_VIDEO, itemKey = video)
                    second?.lowercase() == "search" -> WebRoute(p, RouteKind.SEARCH)
                    else -> WebRoute(p, RouteKind.SHORT_VIDEO_FEED)
                }
            }
            first == "video.php" ->
                WebRoute(p, RouteKind.REGULAR_VIDEO, itemKey = query["v"]?.takeIf { NUMERIC_ID.matches(it) })
            first == "messages" -> WebRoute(p, RouteKind.INBOX)
            first == "search" -> WebRoute(p, RouteKind.SEARCH)
            first in ACCOUNT_ROOTS -> WebRoute(p, RouteKind.ACCOUNT)
            API_VERSION.matches(first) && second == "dialog" -> WebRoute(p, RouteKind.ACCOUNT)
            first in POST_PAGES -> WebRoute(p, RouteKind.POST)
            first == "profile.php" -> WebRoute(p, RouteKind.PROFILE)
            first in RESERVED -> WebRoute(p, RouteKind.OTHER)
            USERNAME.matches(segments[0]) -> when (second?.lowercase()) {
                "videos" ->
                    WebRoute(p, RouteKind.REGULAR_VIDEO, itemKey = segments.last().takeIf { NUMERIC_ID.matches(it) })
                "posts" -> WebRoute(p, RouteKind.POST)
                else -> WebRoute(p, RouteKind.PROFILE)
            }
            else -> WebRoute(p, RouteKind.OTHER)
        }
    }

    /** fb.watch/<token> short links redirect to a video or reel page, which is classified then. */
    fun parseShortLink(): WebRoute = WebRoute(Platform.FACEBOOK, RouteKind.OTHER)
}
