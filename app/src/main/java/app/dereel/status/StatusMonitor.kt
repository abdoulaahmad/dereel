package app.dereel.status

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.webkit.WebViewCompat
import app.dereel.BuildConfig
import androidx.webkit.WebViewFeature
import app.dereel.core.model.Platform
import app.dereel.core.status.EnvironmentSnapshot
import app.dereel.core.status.ProtectionCheck
import app.dereel.core.time.DeviceClock
import app.dereel.data.SettingsRepository
import app.dereel.nativeapp.DereelAccessibilityService
import app.dereel.nativeapp.NativeServiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Re-checks operational status from the real system state (FR-04, FR-36). The checks run on
 * foreground entry, on return from Android settings, on service lifecycle changes and on platform
 * launch. Opening a settings screen is never taken to mean access was granted.
 */
class StatusMonitor(
    private val context: Context,
    private val settings: SettingsRepository,
    private val clock: DeviceClock,
    private val scope: CoroutineScope,
) {
    private val _environment = MutableStateFlow<EnvironmentSnapshot?>(null)
    val environment: StateFlow<EnvironmentSnapshot?> = _environment.asStateFlow()

    private var refreshJob: Job? = null
    private var graceJob: Job? = null
    private var alertJob: Job? = null

    fun start() {
        scope.launch { NativeServiceState.connected.collect { refresh() } }
        scope.launch { NativeServiceState.layout.drop(1).collect { refresh() } }
        scope.launch { settings.preferences.collect { refresh() } }
        scope.launch { settings.configs.drop(1).collect { refresh() } }
    }

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val snapshot = probe()
            _environment.value = snapshot
            updateAlert(snapshot)
            // Just after access is enabled, the system can take a moment to bind the service.
            // Check again shortly rather than reporting a failure that is about to resolve itself.
            if (snapshot.accessibilityEnabled && !snapshot.accessibilityConnected && graceJob?.isActive != true) {
                graceJob = scope.launch {
                    delay(SERVICE_BIND_GRACE_MS)
                    val again = probe()
                    _environment.value = again
                    updateAlert(again)
                }
            }
        }
    }

    /**
     * S-02: tells the user when app protection has stopped, if they asked for that. The state must
     * hold for [ALERT_GRACE_MS] first, so a service that is only restarting does not alert. The
     * notification is withdrawn as soon as protection works again.
     */
    private fun updateAlert(snapshot: EnvironmentSnapshot) {
        val preferences = settings.preferences.value ?: return
        if (!ProtectionCheck.protectionStopped(settings.configs.value, preferences, snapshot)) {
            alertJob?.cancel()
            alertJob = null
            ProtectionAlerts.cancel(context)
            return
        }
        if (alertJob?.isActive == true) return
        alertJob = scope.launch {
            delay(ALERT_GRACE_MS)
            val again = probe()
            _environment.value = again
            val current = settings.preferences.value ?: return@launch
            if (ProtectionCheck.protectionStopped(settings.configs.value, current, again)) ProtectionAlerts.show(context)
        }
    }

    /** A fresh check that returns its result, for "Check protection" (S-02). */
    suspend fun checkNow(): EnvironmentSnapshot {
        val snapshot = probe()
        _environment.value = snapshot
        return snapshot
    }

    private suspend fun probe(): EnvironmentSnapshot {
        val (webViewAvailable, supportsRules) = withContext(Dispatchers.Main) { webViewState() }
        if (BuildConfig.DEBUG) {
            Log.d("DereelStatus", "probe enabled=${isServiceEnabled(context)} connected=${NativeServiceState.connected.value}")
        }
        return EnvironmentSnapshot(
            accessibilityEnabled = isServiceEnabled(context),
            accessibilityConnected = NativeServiceState.connected.value,
            accessibilityEverConnected = settings.preferences.value?.nativeProtectionEverConnected == true,
            installedApps = Platform.entries.filter { isInstalled(context, it) }.toSet(),
            webViewAvailable = webViewAvailable,
            webViewSupportsRules = supportsRules,
            layout = NativeServiceState.layout.value,
            checkedAtWallMs = clock.wallMs(),
        )
    }

    private fun webViewState(): Pair<Boolean, Boolean> = try {
        val available = WebViewCompat.getCurrentWebViewPackage(context) != null
        val rules = available &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        available to rules
    } catch (e: Exception) {
        false to false
    }

    companion object {
        private const val SERVICE_BIND_GRACE_MS = 2_000L
        private const val ALERT_GRACE_MS = 8_000L

        fun isServiceEnabled(context: Context): Boolean {
            val expected = ComponentName(context, DereelAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == expected }
        }

        fun isInstalled(context: Context, platform: Platform): Boolean = try {
            platform.nativePackages.any { context.packageManager.getLaunchIntentForPackage(it) != null }
        } catch (e: Exception) {
            false
        }
    }
}
