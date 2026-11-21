package app.dereel.core.web

import app.dereel.core.model.Platform
import app.dereel.core.policy.DenyReason
import app.dereel.core.policy.PolicyDecision
import app.dereel.core.policy.SurfaceKind
import app.dereel.core.shared.ContextRoute
import app.dereel.core.shared.ExceptionChange
import app.dereel.core.shared.ExceptionEndReason
import app.dereel.core.shared.ShareOrigin
import app.dereel.core.shared.SharedItemExceptionTracker
import app.dereel.core.shared.SharedItemMatch

/** The browser adapter's view of the shared policy (DATA-02). */
interface BrowserPolicyPort {
    /** Evaluates without side effects. */
    fun evaluate(surface: SurfaceKind, sharedItem: SharedItemMatch?): PolicyDecision

    /** Reports the visible surface. Starts or stops usage measurement. */
    fun report(surface: SurfaceKind, sharedItem: SharedItemMatch?): PolicyDecision

    /** No short-video surface is visible in this browser. */
    fun clear()
}

/** Where the user ends up after Dereel denies a route. Used to word the message (UX-10). */
enum class BlockOutcome {
    /** The navigation was stopped before it happened. The user is still on the same page. */
    STAYED_ON_PAGE,

    /** Dereel returned to the previous valid page. */
    WENT_BACK,

    /** A shared item ended and Dereel returned to the conversation (FR-21). */
    RETURNED_TO_CONVERSATION,

    /** Nothing valid was loaded, for example a denied direct link. Offer a safe route (FR-17). */
    NOTHING_LOADED,
}

data class BlockNotice(
    val reason: DenyReason,
    val outcome: BlockOutcome,
    val sharedItemEnded: Boolean = false,
    /** How long until the denial ends by itself (a break or the sleep schedule), if it does. */
    val retryInMs: Long? = null,
)

enum class BrowserErrorKind { REDIRECT_LOOP }

sealed interface BrowserCommand {
    data object GoBack : BrowserCommand
    data class Load(val url: String) : BrowserCommand
    data class ShowBlocked(val notice: BlockNotice) : BrowserCommand
    data class OfferExternal(val url: String, val host: String) : BrowserCommand
    data object AppLinkBlocked : BrowserCommand
    data class ShowError(val kind: BrowserErrorKind) : BrowserCommand
}

/** Result for a navigation the WebView asked about. [block] cancels it. */
data class NavigationResult(val block: Boolean, val commands: List<BrowserCommand> = emptyList())

/**
 * Applies the platform policy to every way a page can change (FR-13). The WebView layer forwards:
 * - navigation requests (links, redirects, location changes), before they load;
 * - committed routes (page starts, history and in-page route updates, refresh, Back/Forward).
 *
 * Denied routes return to the last valid context or a documented safe route (FR-17). A loop
 * guard turns repeated corrections into an error state instead of a redirect loop. Allowed pages
 * are never reloaded (FR-15).
 */
class BrowserNavigationController(
    private val platform: Platform,
    private val policy: BrowserPolicyPort,
    private val monotonicMs: () -> Long,
    private val sharedTracker: SharedItemExceptionTracker = SharedItemExceptionTracker(),
) {
    private var committed: WebRoute? = null
    private var committedUrl: String? = null
    private var lastSafeUrl: String? = null
    private var pendingGestureUrl: String? = null

    /** A denied URL already corrected. Repeat callbacks for it must not trigger another correction. */
    private var correctedUrl: String? = null
    private var foreground = true
    private val corrections = ArrayDeque<Long>()

    /** True after the loop guard has stopped automatic navigation. */
    var halted: Boolean = false
        private set

    val activeSharedItem: SharedItemMatch? get() = sharedTracker.active
    val currentRoute: WebRoute? get() = committed

    /** Decides the first page for a browser screen. [requestedUrl] must already be validated (INT-01). */
    fun start(requestedUrl: String?, viaSharedLink: Boolean): List<BrowserCommand> {
        val entry = PlatformWeb.entryUrl(platform)
        val url = requestedUrl ?: entry
        val target = WebUrlClassifier.classify(platform, url) as? NavTarget.PlatformPage
            ?: return listOf(BrowserCommand.Load(entry))
        val route = target.route
        if (viaSharedLink && route.kind == RouteKind.SHORT_VIDEO_ITEM && route.itemKey != null) {
            sharedTracker.expectSharedLink(route.itemKey)
        }
        if (route.isShortVideo) {
            val shared = sharedTracker.preview(null, route.toContextRoute(), userInitiated = viaSharedLink)
            val decision = policy.evaluate(SurfaceKind.SHORT_VIDEO, shared)
            if (!permits(decision)) {
                return listOf(BrowserCommand.ShowBlocked(notice(decision, BlockOutcome.NOTHING_LOADED)))
            }
        }
        return listOf(BrowserCommand.Load(url))
    }

    /** Called from shouldOverrideUrlLoading. */
    fun onNavigationRequest(url: String, isMainFrame: Boolean, hasGesture: Boolean): NavigationResult {
        if (!isMainFrame) {
            // Embedded frames may load web content and the browser's own internal schemes, which
            // sign-in checks such as CAPTCHA frames rely on. They never launch apps or custom
            // schemes (INT-02).
            val scheme = url.substringBefore(':', missingDelimiterValue = "").lowercase()
            return NavigationResult(block = scheme !in SUBFRAME_SCHEMES)
        }
        val target = WebUrlClassifier.classify(platform, url)
        return when (target) {
            is NavTarget.PlatformPage -> {
                if (!target.route.isShortVideo) {
                    pendingGestureUrl = if (hasGesture) url else null
                    return NavigationResult(block = false)
                }
                val shared = sharedTracker.preview(committed?.toContextRoute(), target.route.toContextRoute(), hasGesture)
                val decision = policy.evaluate(SurfaceKind.SHORT_VIDEO, shared)
                if (permits(decision)) {
                    pendingGestureUrl = if (hasGesture) url else null
                    NavigationResult(block = false)
                } else {
                    NavigationResult(
                        block = true,
                        commands = listOf(BrowserCommand.ShowBlocked(notice(decision, BlockOutcome.STAYED_ON_PAGE))),
                    )
                }
            }
            is NavTarget.AuthPage, NavTarget.Blank -> NavigationResult(block = false)
            is NavTarget.ExternalWeb -> NavigationResult(true, listOf(BrowserCommand.OfferExternal(target.url, target.host)))
            is NavTarget.BlockedScheme -> NavigationResult(true, listOf(BrowserCommand.AppLinkBlocked))
            NavTarget.Invalid -> NavigationResult(block = true)
        }
    }

    /**
     * Called when the main frame's URL changes: page start, history update, in-page route change,
     * refresh, Back or Forward. [previousEntryUrl] is the URL one step back in history, if any.
     */
    fun onRouteCommitted(url: String, previousEntryUrl: String?, inPage: Boolean): List<BrowserCommand> {
        val target = WebUrlClassifier.classify(platform, url)
        if (target !is NavTarget.PlatformPage) {
            if (target is NavTarget.AuthPage) sharedTracker.commit(committed?.toContextRoute(), ContextRoute.Account, false)
            committed = null
            committedUrl = null
            policy.clear()
            return emptyList()
        }
        if (url == committedUrl) return reportCurrent(previousEntryUrl)

        correctedUrl = null
        val route = target.route
        // In-page route changes come from the page reacting to a tap. Full loads count as
        // user-initiated only if the preceding request carried a gesture.
        val userInitiated = inPage || pendingGestureUrl == url
        pendingGestureUrl = null
        val change = sharedTracker.commit(committed?.toContextRoute(), route.toContextRoute(), userInitiated)
        committed = route
        committedUrl = url

        if (!route.isShortVideo) {
            policy.clear()
            if (route.kind != RouteKind.ACCOUNT) lastSafeUrl = url
            return emptyList()
        }
        return reportCurrent(previousEntryUrl, change)
    }

    /** Policy changed while a page is showing, for example the allowance ran out (FR-27, POL-06). */
    fun onPolicyChanged(previousEntryUrl: String?): List<BrowserCommand> = reportCurrent(previousEntryUrl)

    /** The page script intercepted a tap on a blocked entry point. */
    fun onBlockedEntryTapped(): List<BrowserCommand> {
        val decision = policy.evaluate(SurfaceKind.SHORT_VIDEO, null)
        if (permits(decision)) return emptyList()
        return listOf(BrowserCommand.ShowBlocked(notice(decision, BlockOutcome.STAYED_ON_PAGE)))
    }

    fun onBackground() {
        foreground = false
        policy.clear()
    }

    fun onForeground(previousEntryUrl: String?): List<BrowserCommand> {
        foreground = true
        return reportCurrent(previousEntryUrl)
    }

    /** A main-frame load failed. Nothing is visible, so nothing is measured (DATA-04). */
    fun onMainFrameError() {
        policy.clear()
    }

    /** The browser screen closed or the user signed out (FR-23, INT-03). */
    fun onClosed() {
        policy.clear()
        sharedTracker.reset()
    }

    /** The user chose to continue after the loop guard tripped. */
    fun resetLoopGuard(): List<BrowserCommand> {
        halted = false
        corrections.clear()
        return listOf(BrowserCommand.Load(PlatformWeb.entryUrl(platform)))
    }

    /** Whether the page script should treat short videos as denied right now. */
    fun shortVideoDeniedNow(): Boolean = !permits(policy.evaluate(SurfaceKind.SHORT_VIDEO, null))

    // --- internals -------------------------------------------------------------------------

    private fun reportCurrent(
        previousEntryUrl: String?,
        change: ExceptionChange = ExceptionChange.None,
    ): List<BrowserCommand> {
        val route = committed ?: return emptyList()
        if (!route.isShortVideo) return emptyList()
        if (committedUrl != null && committedUrl == correctedUrl) return emptyList()
        val shared = sharedTracker.active?.takeIf { it.itemKey == route.itemKey }
        val decision = if (foreground) {
            policy.report(SurfaceKind.SHORT_VIDEO, shared)
        } else {
            policy.evaluate(SurfaceKind.SHORT_VIDEO, shared)
        }
        if (permits(decision)) return emptyList()
        return correct(decision, change, previousEntryUrl)
    }

    private fun correct(
        decision: PolicyDecision,
        change: ExceptionChange,
        previousEntryUrl: String?,
    ): List<BrowserCommand> {
        policy.clear()
        correctedUrl = committedUrl
        if (halted || !loopGuardAllows()) {
            halted = true
            return listOf(BrowserCommand.ShowError(BrowserErrorKind.REDIRECT_LOOP))
        }

        val sharedEnded = change is ExceptionChange.Ended && change.reason == ExceptionEndReason.ADVANCED
        if (sharedEnded) {
            val origin = (change as ExceptionChange.Ended).returnTo
            if (origin is ShareOrigin.Conversation) {
                return listOf(
                    BrowserCommand.Load(PlatformWeb.conversationUrl(origin.threadId)),
                    BrowserCommand.ShowBlocked(notice(decision, BlockOutcome.RETURNED_TO_CONVERSATION, sharedItemEnded = true)),
                )
            }
        }

        val previous = previousEntryUrl?.let { WebUrlClassifier.classify(platform, it) as? NavTarget.PlatformPage }
        if (previous != null && !previous.route.isShortVideo) {
            return listOf(BrowserCommand.GoBack, BrowserCommand.ShowBlocked(notice(decision, BlockOutcome.WENT_BACK, sharedEnded)))
        }
        val safe = lastSafeUrl ?: PlatformWeb.entryUrl(platform)
        return listOf(BrowserCommand.Load(safe), BrowserCommand.ShowBlocked(notice(decision, BlockOutcome.WENT_BACK, sharedEnded)))
    }

    private fun loopGuardAllows(): Boolean {
        val now = monotonicMs()
        while (corrections.isNotEmpty() && now - corrections.first() > LOOP_WINDOW_MS) corrections.removeFirst()
        if (corrections.size >= MAX_CORRECTIONS_PER_WINDOW) return false
        corrections.addLast(now)
        return true
    }

    private fun notice(decision: PolicyDecision, outcome: BlockOutcome, sharedItemEnded: Boolean = false) =
        BlockNotice(denyReason(decision), outcome, sharedItemEnded, (decision as? PolicyDecision.Deny)?.retryInMs)

    private fun denyReason(decision: PolicyDecision): DenyReason =
        (decision as? PolicyDecision.Deny)?.reason ?: DenyReason.BLOCKED

    /** NotManaged means the platform is not selected, so Dereel must not interfere (FR-05). */
    private fun permits(decision: PolicyDecision): Boolean =
        decision is PolicyDecision.Allow || decision is PolicyDecision.NotManaged

    companion object {
        const val LOOP_WINDOW_MS = 5_000L
        const val MAX_CORRECTIONS_PER_WINDOW = 3

        /** Schemes an embedded frame may load. None of them can open another app. */
        private val SUBFRAME_SCHEMES = setOf("https", "http", "about", "blob", "data")
    }
}
