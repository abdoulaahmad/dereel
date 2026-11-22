package app.dereel.core.model

/**
 * What a platform's saved rules amount to, for one-line summaries such as the list of apps in
 * Settings. It describes the settings only. Whether blocking is working right now is a status
 * question (UX-08) and is answered by the status evaluator, never by this summary.
 */
sealed interface RuleSummary {
    /** The platform is not selected in Dereel. */
    data object NotAdded : RuleSummary

    /** Selected, with short-video blocking switched off. Time limits do not apply then (POL-07). */
    data object BlockingOff : RuleSummary

    /** Blocking is on. A limit is listed only while it is switched on. */
    data class Blocking(val allowanceMinutes: Int?, val interval: IntervalSetting?) : RuleSummary

    companion object {
        fun of(config: PlatformConfig): RuleSummary = when {
            !config.selected -> NotAdded
            !config.blockShortVideos -> BlockingOff
            else -> Blocking(
                allowanceMinutes = config.allowance.minutes.takeIf { config.allowance.enabled },
                interval = config.interval.takeIf { it.enabled },
            )
        }
    }
}
