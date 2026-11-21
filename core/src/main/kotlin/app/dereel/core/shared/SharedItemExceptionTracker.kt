package app.dereel.core.shared

/** Where a shared item was deliberately opened from (FR-20). */
sealed interface ShareOrigin {
    /**
     * A supported conversation. The thread identifier is held in memory only, so Dereel can
     * return to the conversation. It is never persisted or logged (FR-32).
     */
    class Conversation(val threadId: String) : ShareOrigin {
        override fun equals(other: Any?): Boolean = other is Conversation && other.threadId == threadId
        override fun hashCode(): Int = threadId.hashCode()
        override fun toString(): String = "Conversation(redacted)"
    }

    /** A link the user shared to Dereel from another app. */
    data object SharedLink : ShareOrigin
}

/** A recognized exception for exactly one item in one context. */
data class SharedItemMatch(val origin: ShareOrigin, val itemKey: String)

/** The parts of a route that matter for exception scoping. */
sealed interface ContextRoute {
    class Conversation(val threadId: String) : ContextRoute {
        override fun equals(other: Any?): Boolean = other is Conversation && other.threadId == threadId
        override fun hashCode(): Int = threadId.hashCode()
        override fun toString(): String = "Conversation(redacted)"
    }

    /** A short-video surface. [itemKey] is null for a feed rather than a single item. */
    data class ShortVideo(val itemKey: String?) : ContextRoute
    data object Account : ContextRoute
    data object Other : ContextRoute
}

enum class ExceptionEndReason { ADVANCED, LEFT_CONTEXT, ACCOUNT_CHANGED, RESET }

sealed interface ExceptionChange {
    data object None : ExceptionChange
    data class Started(val match: SharedItemMatch) : ExceptionChange
    data class Ended(val match: SharedItemMatch, val reason: ExceptionEndReason) : ExceptionChange {
        /** FR-21: advancing to another short video returns to the originating context. */
        val returnTo: ShareOrigin? get() = if (reason == ExceptionEndReason.ADVANCED) match.origin else null
    }
}

/**
 * Scopes a shared-video exception to one recognized item and its originating context
 * (FR-20 to FR-23).
 *
 * State lives only in memory, so an exception never survives a process restart (FR-23). Identity
 * is the platform's stable item key from the route (for example a reel shortcode), not a media
 * URL. Quality changes, re-renders and suggestions loading do not change it (FR-22).
 */
class SharedItemExceptionTracker {
    var active: SharedItemMatch? = null
        private set

    private var pendingLinkItem: String? = null

    /** Dereel is about to open [itemKey] from a link shared to it. */
    fun expectSharedLink(itemKey: String) {
        pendingLinkItem = itemKey
    }

    /** The exception that committing [next] would produce, without changing state. */
    fun preview(previous: ContextRoute?, next: ContextRoute, userInitiated: Boolean): SharedItemMatch? {
        val key = (next as? ContextRoute.ShortVideo)?.itemKey ?: return null
        active?.let { return if (it.itemKey == key) it else null }
        if (pendingLinkItem == key) return SharedItemMatch(ShareOrigin.SharedLink, key)
        if (userInitiated && previous is ContextRoute.Conversation) {
            return SharedItemMatch(ShareOrigin.Conversation(previous.threadId), key)
        }
        return null
    }

    /** Records that [next] is now the visible route. */
    fun commit(previous: ContextRoute?, next: ContextRoute, userInitiated: Boolean): ExceptionChange {
        val current = active
        if (current != null) {
            if (next is ContextRoute.ShortVideo && next.itemKey == current.itemKey) return ExceptionChange.None
            active = null
            pendingLinkItem = null
            val reason = when (next) {
                is ContextRoute.ShortVideo -> ExceptionEndReason.ADVANCED
                ContextRoute.Account -> ExceptionEndReason.ACCOUNT_CHANGED
                else -> ExceptionEndReason.LEFT_CONTEXT
            }
            return ExceptionChange.Ended(current, reason)
        }
        val match = preview(previous, next, userInitiated)
        // A pending shared link survives sign-in pages only. Any other route ends it.
        if (next !is ContextRoute.Account) pendingLinkItem = null
        if (match != null) {
            active = match
            return ExceptionChange.Started(match)
        }
        return ExceptionChange.None
    }

    /** Ends any exception, for example on sign-out, closing the browser or an account change. */
    fun reset(): ExceptionChange {
        pendingLinkItem = null
        val current = active ?: return ExceptionChange.None
        active = null
        return ExceptionChange.Ended(current, ExceptionEndReason.RESET)
    }
}
