package app.dereel.data

import app.dereel.core.grant.GrantState
import app.dereel.core.grant.TemporaryGrant
import app.dereel.core.model.AllowanceSetting
import app.dereel.core.model.AppPreferences
import app.dereel.core.model.BrowserControl
import app.dereel.core.model.IntervalSetting
import app.dereel.core.model.SleepSchedule
import app.dereel.core.pacing.PacingState
import app.dereel.data.db.PacingStateEntity
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.model.PlatformConfig
import app.dereel.core.usage.SegmentState
import app.dereel.core.usage.SessionOutcome
import app.dereel.core.usage.UsageSegment
import app.dereel.data.db.AppPreferencesEntity
import app.dereel.data.db.PlatformConfigEntity
import app.dereel.data.db.TemporaryGrantEntity
import app.dereel.data.db.UsageSegmentEntity

/**
 * Maps between storage rows and domain models. Invalid stored values fall back to safe
 * defaults instead of crashing, so existing settings and history are preserved (DATA-01).
 */
internal fun PlatformConfigEntity.toModel(): PlatformConfig? {
    val platform = Platform.fromId(platformId) ?: return null
    val minutes = allowanceMinutes?.takeIf { it in AllowanceSetting.MIN_MINUTES..AllowanceSetting.MAX_MINUTES }
    return PlatformConfig(
        platform = platform,
        selected = selected,
        openingMethod = PlatformConfig.validMethod(platform, OpeningMethod.fromId(openingMethod)),
        blockShortVideos = blockShortVideos,
        allowSharedVideos = allowSharedVideos,
        allowance = AllowanceSetting(enabled = allowanceEnabled && minutes != null, minutes = minutes),
        interval = IntervalSetting.sanitized(intervalEnabled, intervalWatchMinutes, intervalBreakMinutes),
        browserControls = BrowserControl.decode(platform, browserControls),
        revision = revision,
    )
}

internal fun PlatformConfig.toEntity() = PlatformConfigEntity(
    platformId = platform.id,
    selected = selected,
    openingMethod = openingMethod?.id,
    blockShortVideos = blockShortVideos,
    allowSharedVideos = allowSharedVideos,
    allowanceEnabled = allowance.enabled,
    allowanceMinutes = allowance.minutes,
    browserControls = BrowserControl.encode(browserControls),
    revision = revision,
    intervalEnabled = interval.enabled,
    intervalWatchMinutes = interval.watchMinutes,
    intervalBreakMinutes = interval.breakMinutes,
)

internal fun AppPreferencesEntity.toModel() = AppPreferences(
    onboardingComplete = onboardingComplete,
    temporaryAccessEnabled = temporaryAccessEnabled,
    nativeProtectionEverConnected = nativeEverConnected,
    sleep = SleepSchedule.sanitized(sleepEnabled, sleepStartMinute, sleepEndMinute),
    protectionAlerts = protectionAlerts,
    liveTimer = liveTimer,
)

internal fun AppPreferences.toEntity() = AppPreferencesEntity(
    onboardingComplete = onboardingComplete,
    temporaryAccessEnabled = temporaryAccessEnabled,
    nativeEverConnected = nativeProtectionEverConnected,
    sleepEnabled = sleep.enabled,
    sleepStartMinute = sleep.startMinute,
    sleepEndMinute = sleep.endMinute,
    protectionAlerts = protectionAlerts,
    liveTimer = liveTimer,
)

internal fun PacingStateEntity.toModel(): PacingState? {
    val platform = Platform.fromId(platformId) ?: return null
    return PacingState(platform, usedMs, breakUntilWallMs, lastViewedWallMs)
}

internal fun PacingState.toEntity() = PacingStateEntity(platform.id, usedMs, breakUntilWallMs, lastViewedWallMs)

internal fun UsageSegmentEntity.toModel(): UsageSegment? {
    val platform = Platform.fromId(platformId) ?: return null
    val method = OpeningMethod.fromId(method) ?: return null
    val outcome = runCatching { SessionOutcome.valueOf(outcome) }.getOrNull() ?: return null
    val state = runCatching { SegmentState.valueOf(state) }.getOrNull() ?: return null
    if (elapsedMs < 0 || endWallMs < startWallMs) return null
    return UsageSegment(id, sessionId, platform, method, startWallMs, endWallMs, elapsedMs, day, outcome, state)
}

internal fun UsageSegment.toEntity() = UsageSegmentEntity(
    id = id,
    sessionId = sessionId,
    platformId = platform.id,
    method = method.id,
    startWallMs = startWallMs,
    endWallMs = endWallMs,
    elapsedMs = elapsedMs,
    day = day,
    outcome = outcome.name,
    state = state.name,
)

internal fun TemporaryGrantEntity.toModel(): TemporaryGrant? {
    val platform = Platform.fromId(platformId) ?: return null
    val state = runCatching { GrantState.valueOf(state) }.getOrNull() ?: return null
    return TemporaryGrant(id, platform, issuedWallMs, expiresWallMs, issuedMonotonicMs, bootId, state)
}

internal fun TemporaryGrant.toEntity() = TemporaryGrantEntity(
    id = id,
    platformId = platform.id,
    issuedWallMs = issuedWallMs,
    expiresWallMs = expiresWallMs,
    issuedMonotonicMs = issuedMonotonicMs,
    bootId = bootId,
    state = state.name,
)
