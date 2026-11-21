package app.dereel.core.web

import app.dereel.core.model.Platform
import java.net.URI
import java.net.URLDecoder

sealed interface NavTarget {
    data class PlatformPage(val route: WebRoute, val url: String) : NavTarget
    data class AuthPage(val host: String) : NavTarget

    /** A web page outside the platform. Opening it needs an explicit user action (INT-02). */
    data class ExternalWeb(val url: String, val host: String) : NavTarget

    /** A non-web scheme such as intent:, market: or an app scheme. Never executed (INT-02). */
    data class BlockedScheme(val scheme: String) : NavTarget
    data object Blank : NavTarget
    data object Invalid : NavTarget
}

/** Classifies URLs for a platform's browser (FR-12, FR-13, INT-01, INT-02). */
object WebUrlClassifier {
    fun classify(platform: Platform, rawUrl: String): NavTarget {
        if (rawUrl == "about:blank") return NavTarget.Blank
        val uri = parse(rawUrl) ?: return NavTarget.Invalid
        val scheme = uri.scheme?.lowercase() ?: return NavTarget.Invalid
        when (scheme) {
            "https" -> Unit
            // Plain HTTP is never loaded inside Dereel. The user may choose to open it elsewhere.
            "http" -> return uri.host?.let { NavTarget.ExternalWeb(rawUrl, it.lowercase()) } ?: NavTarget.Invalid
            else -> return NavTarget.BlockedScheme(scheme)
        }
        if (uri.rawUserInfo != null) return NavTarget.Invalid
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return NavTarget.Invalid
        val path = uri.rawPath.orEmpty()

        PlatformWeb.linkShim(platform, host, path)?.let { param ->
            return unwrapShim(uri, param) ?: NavTarget.ExternalWeb(rawUrl, host)
        }

        if (uri.port != -1 && uri.port != 443) return NavTarget.ExternalWeb(rawUrl, host)

        if (host in PlatformWeb.contentHosts(platform)) {
            return NavTarget.PlatformPage(routeOf(platform, host, uri), rawUrl)
        }
        if (host in PlatformWeb.authHosts(platform)) {
            // A sign-in host is allowed for signing in. It is not a way to reach another
            // platform's short videos from this browser.
            if (PlatformWeb.isFacebookSite(host) && routeOf(Platform.FACEBOOK, host, uri).isShortVideo) {
                return NavTarget.ExternalWeb(rawUrl, host)
            }
            return NavTarget.AuthPage(host)
        }
        return NavTarget.ExternalWeb(rawUrl, host)
    }

    fun routeOf(platform: Platform, host: String, uri: URI): WebRoute {
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::decode)
        return when (platform) {
            Platform.INSTAGRAM -> InstagramRoutes.parse(segments)
            Platform.YOUTUBE ->
                if (host == "youtu.be") YouTubeRoutes.parseShortLink(segments)
                else YouTubeRoutes.parse(segments, queryOf(uri))
            Platform.FACEBOOK ->
                if (host == PlatformWeb.FACEBOOK_SHORT_LINK_HOST) FacebookRoutes.parseShortLink()
                else FacebookRoutes.parse(segments, queryOf(uri))
            // No Snapchat page is ever classified: it has no content hosts.
            Platform.SNAPCHAT -> WebRoute(platform, RouteKind.OTHER)
        }
    }

    private fun unwrapShim(uri: URI, param: String): NavTarget? {
        val target = queryOf(uri)[param] ?: return null
        val inner = parse(target) ?: return null
        val scheme = inner.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return scheme?.let { NavTarget.BlockedScheme(it) }
        val host = inner.host?.lowercase() ?: return null
        return NavTarget.ExternalWeb(target, host)
    }

    internal fun parse(raw: String): URI? = try {
        URI(raw.trim())
    } catch (e: Exception) {
        null
    }

    internal fun queryOf(uri: URI): Map<String, String> {
        val raw = uri.rawQuery ?: return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val idx = pair.indexOf('=')
            if (idx <= 0) null else decode(pair.substring(0, idx)) to decode(pair.substring(idx + 1))
        }.toMap()
    }

    // The (String, String) overload is used because the Charset overload needs Android API 33.
    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (e: IllegalArgumentException) {
        value
    }
}

/** A validated link shared to Dereel from another app (INT-01). */
data class SharedLink(val platform: Platform, val url: String, val route: WebRoute)

object SharedLinkParser {
    private val URL_PATTERN = Regex("""https?://[^\s<>"']+""")

    private val SUPPORTED_KINDS = mapOf(
        Platform.INSTAGRAM to setOf(RouteKind.SHORT_VIDEO_ITEM, RouteKind.POST, RouteKind.PROFILE),
        Platform.YOUTUBE to setOf(RouteKind.SHORT_VIDEO_ITEM, RouteKind.REGULAR_VIDEO, RouteKind.CHANNEL),
        Platform.FACEBOOK to setOf(RouteKind.SHORT_VIDEO_ITEM, RouteKind.REGULAR_VIDEO, RouteKind.POST, RouteKind.PROFILE),
        Platform.SNAPCHAT to emptySet(),
    )

    /**
     * Finds the first URL in shared text and accepts it only when the scheme is HTTPS, the host
     * belongs to a supported platform and the path is a supported journey.
     */
    fun parse(text: String?): SharedLink? {
        if (text == null || text.length > MAX_SHARED_TEXT) return null
        val candidate = URL_PATTERN.find(text)?.value?.trimEnd('.', ',', ')', '!', '?', ';') ?: return null
        for (platform in Platform.entries) {
            val target = WebUrlClassifier.classify(platform, candidate) as? NavTarget.PlatformPage ?: continue
            val route = target.route
            if (route.kind !in SUPPORTED_KINDS.getValue(platform)) return null
            return SharedLink(platform, normalize(platform, route, candidate), route)
        }
        return null
    }

    private fun normalize(platform: Platform, route: WebRoute, url: String): String = when {
        platform == Platform.YOUTUBE && route.kind == RouteKind.REGULAR_VIDEO && route.itemKey != null ->
            "https://m.youtube.com/watch?v=${route.itemKey}"
        platform == Platform.YOUTUBE && route.kind == RouteKind.SHORT_VIDEO_ITEM ->
            "https://m.youtube.com/shorts/${route.itemKey}"
        platform == Platform.INSTAGRAM && route.kind == RouteKind.SHORT_VIDEO_ITEM ->
            "https://www.instagram.com/reel/${route.itemKey}/"
        platform == Platform.INSTAGRAM && route.kind == RouteKind.POST && route.itemKey != null ->
            "https://www.instagram.com/p/${route.itemKey}/"
        else -> url.replaceFirst("://instagram.com", "://www.instagram.com")
    }

    private const val MAX_SHARED_TEXT = 4_096
}
