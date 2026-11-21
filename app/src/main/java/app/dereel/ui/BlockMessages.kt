package app.dereel.ui

import android.content.Context
import app.dereel.R
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.policy.AllowReason
import app.dereel.core.policy.DenyReason
import app.dereel.core.policy.PolicyDecision
import app.dereel.ui.common.formatClock
import app.dereel.ui.common.shortVideoNameRes
import java.util.Locale

/**
 * Specific, calm blocking messages (UX-10, UX-11). They state the reason and what still works,
 * without guilt or invented time-saved claims.
 */
object BlockMessages {
    /** [retryInMs] is how long until a break or the sleep schedule ends, when the denial has an end. */
    fun title(context: Context, platform: Platform, reason: DenyReason, retryInMs: Long? = null): String {
        val videos = context.getString(shortVideoNameRes(platform))
        val until = retryInMs?.let { formatClock(context, System.currentTimeMillis() + it) }
        return when (reason) {
            DenyReason.BLOCKED -> context.getString(
                when (platform) {
                    Platform.INSTAGRAM, Platform.FACEBOOK -> R.string.block_title_reels
                    Platform.YOUTUBE -> R.string.block_title_shorts
                    Platform.SNAPCHAT -> R.string.block_title_spotlight
                },
            )
            DenyReason.ALLOWANCE_EXHAUSTED -> context.getString(
                when (platform) {
                    Platform.INSTAGRAM, Platform.FACEBOOK -> R.string.block_title_reels_allowance
                    Platform.YOUTUBE -> R.string.block_title_shorts_allowance
                    Platform.SNAPCHAT -> R.string.block_title_spotlight_allowance
                },
            )
            DenyReason.SLEEP_SCHEDULE ->
                if (until != null) context.getString(R.string.block_title_sleep, videos, until)
                else context.getString(R.string.block_title_sleep_plain, videos)
            DenyReason.INTERVAL_BREAK ->
                if (until != null) context.getString(R.string.block_title_break, videos, until)
                else context.getString(R.string.block_title_break_plain, videos)
        }
    }

    fun body(context: Context, platform: Platform, reason: DenyReason, method: OpeningMethod): String {
        val stillWorks = context.getString(
            when (platform) {
                Platform.INSTAGRAM -> R.string.block_still_works_instagram
                Platform.YOUTUBE -> R.string.block_still_works_youtube
                Platform.FACEBOOK -> R.string.block_still_works_facebook
                Platform.SNAPCHAT -> R.string.block_still_works_snapchat
            },
        )
        val parts = mutableListOf<String>()
        when (reason) {
            DenyReason.ALLOWANCE_EXHAUSTED -> parts += context.getString(R.string.block_allowance_resets)
            DenyReason.SLEEP_SCHEDULE -> parts += context.getString(R.string.block_sleep_body)
            DenyReason.INTERVAL_BREAK -> parts += context.getString(R.string.block_break_body)
            DenyReason.BLOCKED -> Unit
        }
        parts += stillWorks
        // FR-11: name the deliberate route through Dereel for shared reels.
        if (platform == Platform.INSTAGRAM && method == OpeningMethod.NATIVE_APP && reason == DenyReason.BLOCKED) {
            parts += context.getString(R.string.block_shared_route_hint)
        }
        return parts.joinToString(" ")
    }
}

/**
 * Text for the live timer shown while short videos are allowed (scope change S-02). It states
 * time only: what runs out soonest, or the time used today when nothing runs out.
 */
object TimerText {
    fun forDecision(context: Context, decision: PolicyDecision.Allow, usedTodayMs: Long): String {
        val allowance = decision.remainingAllowanceMs
        val interval = decision.remainingIntervalMs
        return when {
            decision.reason == AllowReason.TEMPORARY_ACCESS ->
                context.getString(R.string.timer_temporary, countdown(decision.grantRemainingMs ?: 0))
            interval != null && (allowance == null || interval <= allowance) ->
                context.getString(R.string.timer_until_break, countdown(interval))
            allowance != null -> context.getString(R.string.timer_left_today, countdown(allowance))
            else -> context.getString(R.string.timer_today, countdown(usedTodayMs))
        }
    }

    /** "4:05" or "1:02:03". */
    fun countdown(ms: Long): String {
        val total = (ms / 1_000).coerceAtLeast(0)
        val hours = total / 3_600
        val minutes = (total % 3_600) / 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}
