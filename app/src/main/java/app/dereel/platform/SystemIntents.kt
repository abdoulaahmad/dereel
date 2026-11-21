package app.dereel.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import android.provider.Settings
import app.dereel.browser.BrowserActivity
import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform

/** Launches the user's saved opening method (UX-03, INT-01) and system settings screens. */
object SystemIntents {
    sealed interface LaunchResult {
        data object Launched : LaunchResult
        data object NeedsMethodChoice : LaunchResult

        /** FR-03: the native app is missing. The caller offers the browser without changing the saved method. */
        data object AppMissing : LaunchResult
    }

    fun openPlatform(context: Context, platform: Platform, method: OpeningMethod?): LaunchResult = when (method) {
        null -> LaunchResult.NeedsMethodChoice
        OpeningMethod.NATIVE_APP -> {
            // The first supported app that is installed, in the platform's preferred order.
            val intent = platform.nativePackages.firstNotNullOfOrNull(context.packageManager::getLaunchIntentForPackage)
            if (intent == null) {
                LaunchResult.AppMissing
            } else {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                LaunchResult.Launched
            }
        }
        OpeningMethod.IN_DEREEL -> {
            context.startActivity(BrowserActivity.intent(context, platform))
            LaunchResult.Launched
        }
    }

    fun openAccessibilitySettings(context: Context): Boolean =
        start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    /** Dereel's own notification settings, for when alerts are on but Android blocks them. */
    fun openNotificationSettings(context: Context): Boolean = start(
        context,
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )

    fun openWebViewUpdate(context: Context): Boolean =
        start(context, Intent(Intent.ACTION_VIEW, "market://details?id=$WEBVIEW_PACKAGE".toUri())) ||
            start(context, Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$WEBVIEW_PACKAGE".toUri()))

    /** Opens an external web link after the user explicitly chose to (INT-02). */
    fun openExternalWeb(context: Context, url: String): Boolean {
        val uri = url.toUri()
        if (uri.scheme != "https" && uri.scheme != "http") return false
        return start(context, Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE))
    }

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    private const val WEBVIEW_PACKAGE = "com.google.android.webview"
}
