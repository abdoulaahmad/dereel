package app.dereel.core.bridge

import app.dereel.core.model.Platform
import app.dereel.core.web.PlatformWeb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The minimal page-to-app message set (SEC-04). The page is untrusted. Messages can only:
 * - confirm that a rule set is installed, or
 * - ask Dereel to explain that a tapped entry point is blocked.
 * No message can trigger navigation outside the platform, file access, shell execution or intents.
 */
sealed interface BridgeMessage {
    data class RulesReady(val ruleVersion: String) : BridgeMessage
    data object BlockedEntryTapped : BridgeMessage
}

object BridgeProtocol {
    const val VERSION = 1
    const val MAX_MESSAGE_CHARS = 256
    private val RULE_VERSION = Regex("^[a-z0-9.-]{1,32}$")
    private val ALLOWED_KEYS = setOf("v", "type", "rules")

    /** Origin and frame checks. Only the platform's own top-level page may talk to Dereel. */
    fun acceptsSource(platform: Platform, sourceOrigin: String?, isMainFrame: Boolean): Boolean =
        isMainFrame && sourceOrigin != null && sourceOrigin in PlatformWeb.bridgeOrigins(platform)

    /** Parses a message. Anything malformed, oversized, unknown or carrying extra fields is rejected. */
    fun parse(raw: String?): BridgeMessage? {
        if (raw == null || raw.length > MAX_MESSAGE_CHARS) return null
        val obj = try {
            Json.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return null
        if (!ALLOWED_KEYS.containsAll(obj.keys)) return null
        if ((obj["v"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != VERSION) return null
        val type = (obj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return when (type) {
            "ready" -> {
                val rules = (obj["rules"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                if (!RULE_VERSION.matches(rules)) return null
                BridgeMessage.RulesReady(rules)
            }
            "blockedEntry" -> if (obj.containsKey("rules")) null else BridgeMessage.BlockedEntryTapped
            else -> null
        }
    }
}
