package app.dereel.browser

import app.dereel.core.model.BrowserControl
import app.dereel.core.model.Platform
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-39, SEC-01, SEC-04 regression guard for the bundled page rules. The rules must:
 * - carry the version the app reports;
 * - make no network requests or read cookies or storage;
 * - post only the two documented message types;
 * - receive only boolean settings.
 */
class BrowserRulesAssetTest {
    private fun asset(name: String) = File("src/main/assets/rules/$name").readText()
    private val files = listOf("engine.js", "instagram.js", "youtube.js", "facebook.js")

    @Test
    fun ruleVersionsMatchTheApp() {
        assertTrue(asset("instagram.js").contains("version: '${BrowserScripts.ruleVersion(Platform.INSTAGRAM)}'"))
        assertTrue(asset("youtube.js").contains("version: '${BrowserScripts.ruleVersion(Platform.YOUTUBE)}'"))
        assertTrue(asset("facebook.js").contains("version: '${BrowserScripts.ruleVersion(Platform.FACEBOOK)}'"))
        // A platform that cannot open inside Dereel has no page rules.
        assertEquals(null, BrowserScripts.ruleVersion(Platform.SNAPCHAT))
        assertFalse(File("src/main/assets/rules/snapchat.js").exists())
    }

    @Test
    fun rulesDoNotUseNetworkCookiesOrStorage() {
        val forbidden = listOf("fetch(", "XMLHttpRequest", "sendBeacon", "WebSocket", "eval(", "new Function", "document.cookie", "localStorage", "sessionStorage", "indexedDB", "importScripts")
        for (file in files) {
            val text = asset(file)
            forbidden.forEach { assertFalse("$file uses $it", text.contains(it)) }
        }
    }

    @Test
    fun onlyDocumentedMessageTypesArePosted() {
        val engine = asset("engine.js")
        val posted = Regex("""post\(\{\s*v:\s*1,\s*type:\s*'(\w+)'""").findAll(engine).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("ready", "blockedEntry"), posted)
        files.filter { it != "engine.js" }.forEach { assertFalse("$it posts messages", asset(it).contains("postMessage")) }
    }

    @Test
    fun pageConfigContainsOnlyBooleans() {
        val json = PageConfig(denied = true, sharedAllowed = false, controls = BrowserControl.entries.toSet()).toJson()
        val values = Regex(""":\s*([^,{}]+)""").findAll(json).map { it.groupValues[1].trim() }.toList()
        assertTrue(values.isNotEmpty())
        values.forEach { assertTrue("unexpected value $it", it == "true" || it == "false" || it == "1") }
        assertTrue(json.contains("\"hideExplore\":true"))
    }
}
