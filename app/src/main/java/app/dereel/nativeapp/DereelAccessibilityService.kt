package app.dereel.nativeapp

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.content.ContextCompat
import app.dereel.AppContainer
import app.dereel.BuildConfig
import app.dereel.container
import app.dereel.core.coordinator.MethodKey
import app.dereel.core.coordinator.SurfaceObservation
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.nativeapp.Bounds
import app.dereel.core.nativeapp.DenyEpisodeController
import app.dereel.core.nativeapp.Detection
import app.dereel.core.nativeapp.EnforcementAction
import app.dereel.core.nativeapp.LayoutRecognitionTracker
import app.dereel.core.nativeapp.NativeRules
import app.dereel.core.nativeapp.NativeSurfaceDetector
import app.dereel.core.policy.PolicyDecision
import app.dereel.core.policy.SurfaceKind
import app.dereel.core.status.ServiceProbe
import app.dereel.ui.BlockMessages
import app.dereel.ui.TimerText
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Native protection for the apps that have a rule set: Instagram, YouTube, Facebook Lite and
 * Snapchat (FR-06 to FR-10).
 *
 * The event scope is limited to those packages in the service configuration. For
 * each relevant event, detection is throttled. It examines only the foreground application
 * window, never keyboards, system UI or Dereel's own overlays (FR-09). The result goes to the
 * shared coordinator, and a denial is enforced with at most one bounded Back action per episode,
 * then a cover.
 *
 * Nothing observed is stored or sent. Only usage durations reach storage, via the coordinator (SEC-03).
 */
class DereelAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var scope: CoroutineScope? = null
    private var appContainer: AppContainer? = null
    private var overlays: OverlayController? = null

    // One detector per app with a native rule set. The service config must list the same packages.
    private val detectors = NativeRules.all.associate { it.packageName to NativeSurfaceDetector(it) }
    private val nativePlatforms = NativeRules.all.map { it.platform }.distinct()
    private val episodes = nativePlatforms.associateWith { DenyEpisodeController() }
    private val layoutTrackers = nativePlatforms.associateWith { LayoutRecognitionTracker() }

    private var visible: Platform? = null
    private var detectionPending = false
    private var detectionDueAt = 0L
    private var pendingTriggerAt = 0L
    private var lastDetectionAt = 0L
    private var currentTriggerAt = 0L
    private var presenceScheduled = false

    private val detectRunnable = Runnable {
        detectionPending = false
        runDetection()
    }
    private val presenceRunnable = Runnable {
        presenceScheduled = false
        checkPresence()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) leaveAll()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        val container = applicationContext.container
        appContainer = container
        overlays = OverlayController(this)
        instance = WeakReference(this)
        NativeServiceState.setConnected(true)
        if (BuildConfig.DEBUG) Log.d(TAG, "service connected")
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { s ->
            s.launch { container.settings.updatePreferences { it.copy(nativeProtectionEverConnected = true) } }
            // React to re-evaluations without an accessibility event: an allowance running out,
            // a grant expiring or a settings change (FR-27, POL-06).
            s.launch {
                container.coordinator.decisions
                    .map { decisions ->
                        nativePlatforms.map { p -> decisions[MethodKey(p, OpeningMethod.NATIVE_APP)]?.let(::category) }
                    }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { if (visible != null) scheduleDetection(0) }
            }
            s.launch {
                container.settings.configs.collect { if (visible != null) scheduleDetection(0) }
            }
        }
        container.status.refresh()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        nativePlatform(event.packageName) ?: return
        // Window-state changes are checked quickly. Content changes are checked quickly too when
        // nothing has been checked recently, and otherwise wait until the minimum interval since
        // the last check has passed. This caps the work done on the target app's UI thread
        // without adding a fixed delay to the first event of a screen change (NFR-05).
        val delay = if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            FAST_DETECTION_MS
        } else {
            val sinceLast = SystemClock.uptimeMillis() - lastDetectionAt
            (MIN_DETECTION_INTERVAL_MS - sinceLast).coerceIn(FAST_DETECTION_MS, MIN_DETECTION_INTERVAL_MS)
        }
        // NFR-05 measures from when Dereel receives the event, not when the app created it.
        scheduleDetection(delay)
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    // --- detection -------------------------------------------------------------------------

    private fun scheduleDetection(delayMs: Long, triggeredAt: Long = SystemClock.uptimeMillis()) {
        val due = SystemClock.uptimeMillis() + delayMs
        if (!detectionPending) pendingTriggerAt = triggeredAt
        if (detectionPending && detectionDueAt <= due) return
        handler.removeCallbacks(detectRunnable)
        detectionPending = true
        detectionDueAt = due
        handler.postAtTime(detectRunnable, due)
    }

    private fun runDetection() {
        val container = appContainer ?: return
        currentTriggerAt = pendingTriggerAt
        lastDetectionAt = SystemClock.uptimeMillis()
        if (!screenUsable()) {
            leaveAll()
            return
        }
        val root = foregroundAppRoot()
        val detector = detectors[root?.packageName?.toString()]
        if (root == null || detector == null) {
            leaveAll()
            return
        }
        val platform = detector.rules.platform
        if (visible != platform) {
            visible?.let { leave(it) }
            visible = platform
            layoutTrackers.getValue(platform).onVisitStart(SystemClock.elapsedRealtime())
        }

        val window = Rect().also { root.getBoundsInScreen(it) }.let { Bounds(it.left, it.top, it.right, it.bottom) }
        val detectStart = SystemClock.uptimeMillis()
        val detection = detector.detect(AccessibilityNodeUiNode(root), window)
        val detectMs = SystemClock.uptimeMillis() - detectStart
        layoutTrackers.getValue(platform).onSnapshot(detection.layoutRecognized)

        // FR-11: no shared-item exception in the native app in this release.
        val decision = container.coordinator.report(platform, OpeningMethod.NATIVE_APP, SurfaceObservation(detection.surface))
        if (BuildConfig.DEBUG) {
            // Structural diagnostics only: view-ID anchor names, never screen text (SEC-03).
            Log.d(TAG, "detect $platform surface=${detection.surface} anchor=${detection.matchedAnchor} recognized=${detection.layoutRecognized} decision=${describe(decision)} took=${detectMs}ms")
        }
        enforce(platform, detection, decision, window)
        updateTimer(platform, decision)
        schedulePresenceCheck()
    }

    /** S-02: the live timer is shown only while short videos are allowed and being counted. */
    private fun updateTimer(platform: Platform, decision: PolicyDecision) {
        val container = appContainer ?: return
        val wanted = decision is PolicyDecision.Allow && container.settings.preferences.value?.liveTimer == true
        if (wanted) {
            overlays?.showTimer(TimerText.forDecision(this, decision as PolicyDecision.Allow, container.usage.usedTodayMs(platform)))
        } else {
            overlays?.removeTimer()
        }
    }

    private fun enforce(platform: Platform, detection: Detection, decision: PolicyDecision, window: Bounds) {
        val episode = episodes.getValue(platform)
        val overlays = overlays ?: return
        if (detection.surface != SurfaceKind.SHORT_VIDEO || decision !is PolicyDecision.Deny) {
            // Allowed viewing, regular content, unmanaged platforms and unavailable methods are
            // never blocked (FR-27, POL-01).
            episode.onSurfaceGone()
            overlays.removeCover()
            return
        }
        val title = BlockMessages.title(this, platform, decision.reason, decision.retryInMs)
        val body = BlockMessages.body(this, platform, decision.reason, OpeningMethod.NATIVE_APP)
        when (episode.onDenied(SystemClock.elapsedRealtime())) {
            EnforcementAction.BACK -> {
                performGlobalAction(GLOBAL_ACTION_BACK)
                logResponseTime("back")
                overlays.showBanner(title, body)
            }
            EnforcementAction.COVER -> {
                val offerGrant = appContainer?.settings?.preferences?.value?.temporaryAccessEnabled == true
                overlays.removeBanner()
                overlays.showCover(
                    region = detection.contentRegion ?: window,
                    title = title,
                    body = body,
                    onGoBack = { performGlobalAction(GLOBAL_ACTION_BACK) },
                    temporaryAccess = if (offerGrant) {
                        { appContainer?.grants?.request(platform) }
                    } else {
                        null
                    },
                )
                logResponseTime("cover")
            }
            EnforcementAction.WAIT -> Unit
            EnforcementAction.KEEP -> detection.contentRegion?.let { overlays.updateCover(it) }
        }
        // Fresh state checks after the cooldown and during confirmation, even if the app sends no
        // further events (FR-10).
        episode.freshCheckDueAt()?.let { due ->
            handler.postDelayed({ scheduleDetection(0) }, (due - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 50)
        }
    }

    // --- presence --------------------------------------------------------------------------

    /**
     * While a native session or cover is active, check every second that the supported app is
     * still in front, and re-run detection. Events from other apps are outside the service's
     * package scope, and an app can change screens without sending another event. Without the
     * re-check, a stale cover could stay (FR-08) or usage could keep accruing (FR-25). Nothing
     * runs while the screen is off (NFR-09).
     */
    private fun schedulePresenceCheck() {
        val platform = visible ?: return
        val container = appContainer ?: return
        val needed = container.usage.openMethod(platform) == OpeningMethod.NATIVE_APP || overlays?.coverShown == true
        if (!needed || presenceScheduled) return
        presenceScheduled = true
        handler.postDelayed(presenceRunnable, PRESENCE_INTERVAL_MS)
    }

    private fun checkPresence() {
        val platform = visible ?: return
        if (!screenUsable()) {
            leaveAll()
            return
        }
        val foreground = nativePlatform(foregroundAppRoot()?.packageName)
        if (foreground != platform) {
            leave(platform)
            if (foreground != null) scheduleDetection(0)
            return
        }
        // Detection reschedules this check while it is still needed.
        scheduleDetection(0)
    }

    /** The platform whose installed app this package is, if Dereel has a native detector for it. */
    private fun nativePlatform(packageName: CharSequence?): Platform? =
        detectors[packageName?.toString()]?.rules?.platform

    /** The foreground application window, ignoring keyboards, system UI and Dereel's overlays (FR-09). */
    private fun foregroundAppRoot(): AccessibilityNodeInfo? {
        val appWindows = try {
            windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        } catch (e: RuntimeException) {
            emptyList()
        }
        val window = appWindows.firstOrNull { it.isActive }
            ?: appWindows.firstOrNull { it.isFocused }
            ?: appWindows.maxByOrNull { it.layer }
        val root = try {
            window?.root
        } catch (e: RuntimeException) {
            null
        }
        return root ?: rootInActiveWindow?.takeUnless { it.packageName?.toString() == packageName }
    }

    private fun screenUsable(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    // --- lifecycle -------------------------------------------------------------------------

    /** FR-08: stop acting when the app is no longer visible. */
    private fun leave(platform: Platform) {
        appContainer?.coordinator?.clear(platform, OpeningMethod.NATIVE_APP)
        episodes.getValue(platform).onSurfaceGone()
        overlays?.removeAll()
        NativeServiceState.setLayout(platform, layoutTrackers.getValue(platform).onVisitEnd(SystemClock.elapsedRealtime()))
        if (visible == platform) visible = null
    }

    private fun leaveAll() {
        handler.removeCallbacks(presenceRunnable)
        presenceScheduled = false
        visible?.let { leave(it) }
        nativePlatforms.forEach { appContainer?.coordinator?.clear(it, OpeningMethod.NATIVE_APP) }
        overlays?.removeAll()
    }

    private fun shutdown() {
        if (BuildConfig.DEBUG) Log.d(TAG, "service shutdown (connected=${appContainer != null})")
        if (appContainer == null) return
        handler.removeCallbacksAndMessages(null)
        detectionPending = false
        leaveAll()
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: IllegalArgumentException) {
            // Not registered.
        }
        scope?.cancel()
        scope = null
        instance = null
        NativeServiceState.setConnected(false)
        appContainer?.status?.refresh()
        appContainer = null
    }

    /** Decision and reason names for the debug log: no screen content (SEC-03). */
    private fun describe(decision: PolicyDecision): String = when (decision) {
        is PolicyDecision.Allow -> "Allow(${decision.reason})"
        is PolicyDecision.Deny -> "Deny(${decision.reason})"
        else -> decision.javaClass.simpleName
    }

    private fun logResponseTime(action: String) {
        // NFR-05 instrumentation for debug builds. Timing only, never screen content.
        if (BuildConfig.DEBUG) Log.d(TAG, "deny $action ${SystemClock.uptimeMillis() - currentTriggerAt} ms after trigger")
    }

    private fun category(decision: PolicyDecision): String = when (decision) {
        is PolicyDecision.Allow -> "allow"
        is PolicyDecision.Deny -> "deny"
        else -> "other"
    }

    companion object {
        @Volatile
        private var instance: WeakReference<DereelAccessibilityService>? = null

        /**
         * S-02 "Check protection": asks the live service to prove it is responding by making a
         * real call into the system, and reports which apps it is set up to receive events from.
         * Null means no service answered.
         */
        fun probe(): ServiceProbe? {
            val service = instance?.get() ?: return null
            return try {
                service.windows
                ServiceProbe(service.serviceInfo?.packageNames?.toSet().orEmpty())
            } catch (e: RuntimeException) {
                null
            }
        }

        private const val TAG = "DereelNative"
        private const val FAST_DETECTION_MS = 40L
        /**
         * Content-change events stream continuously while a video plays. Each detection runs a few
         * view-ID searches on the target app's UI thread, so at least this long must pass between
         * two content-triggered detections. Tuned on a Pixel 9, where a flat 500 ms wait gave
         * 796 ms from event to Back.
         */
        private const val MIN_DETECTION_INTERVAL_MS = 400L
        private const val PRESENCE_INTERVAL_MS = 1_000L
    }
}
