package app.dereel.browser

import android.content.Context
import app.dereel.core.model.BrowserControl
import app.dereel.core.model.Platform

/** Settings passed to the page rules. Only booleans: nothing about the user or account. */
data class PageConfig(
    val denied: Boolean,
    val sharedAllowed: Boolean,
    val controls: Set<BrowserControl>,
) {
    fun toJson(): String {
        fun has(control: BrowserControl) = control in controls
        return buildString {
            append("{\"v\":1")
            append(",\"denied\":").append(denied)
            append(",\"sharedAllowed\":").append(sharedAllowed)
            append(",\"controls\":{")
            append("\"hideExplore\":").append(has(BrowserControl.INSTAGRAM_HIDE_EXPLORE))
            append(",\"hideSuggested\":").append(has(BrowserControl.INSTAGRAM_HIDE_SUGGESTED))
            append(",\"filterSponsored\":").append(has(BrowserControl.INSTAGRAM_FILTER_SPONSORED))
            append(",\"hideHomeFeed\":").append(has(BrowserControl.YOUTUBE_HIDE_HOME_FEED))
            append(",\"hideWatchNext\":").append(has(BrowserControl.YOUTUBE_HIDE_WATCH_NEXT))
            append(",\"filterPromoted\":").append(has(BrowserControl.YOUTUBE_FILTER_PROMOTED))
            append("}}")
        }
    }
}

/** Builds the bundled, versioned page rules (FR-39). */
class BrowserScripts(private val context: Context) {
    private val cache = HashMap<String, String>()

    private fun asset(name: String): String = cache.getOrPut(name) {
        context.assets.open("rules/$name").bufferedReader().use { it.readText() }
    }

    fun documentScript(platform: Platform, config: PageConfig): String {
        val rules = when (platform) {
            Platform.INSTAGRAM -> asset("instagram.js")
            Platform.YOUTUBE -> asset("youtube.js")
            Platform.FACEBOOK -> asset("facebook.js")
            Platform.SNAPCHAT -> error("Snapchat has no page rules")
        }
        return "(function(){'use strict';\nvar DEREEL_CONFIG=${config.toJson()};\n$rules\n${asset("engine.js")}\n})();"
    }

    fun applyScript(config: PageConfig): String = "window.__dereel&&window.__dereel.apply(${config.toJson()});"

    companion object {
        /** The bundled page-rule version, or null for a platform that cannot open inside Dereel. */
        fun ruleVersion(platform: Platform): String? = when (platform) {
            Platform.INSTAGRAM -> "ig-web-0.1.0"
            Platform.YOUTUBE -> "yt-web-0.1.0"
            Platform.FACEBOOK -> "fb-web-0.1.0"
            Platform.SNAPCHAT -> null
        }
    }
}
