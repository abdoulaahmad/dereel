package app.dereel.core.web

import app.dereel.core.model.Platform
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * FR-15, FR-39: the native navigation guard and the page rules share one fixture file, so they
 * cannot drift apart. tools/rules-tests checks the JavaScript side against the same file.
 */
class RouteFixturesTest {
    private val fixtures = Json.parseToJsonElement(
        checkNotNull(javaClass.getResource("/route-fixtures.json")).readText(),
    ).jsonObject

    @Test
    fun `kotlin classifier matches shared fixtures`() {
        val hosts = mapOf(
            Platform.INSTAGRAM to "https://www.instagram.com",
            Platform.YOUTUBE to "https://m.youtube.com",
            Platform.FACEBOOK to "https://m.facebook.com",
        )
        for ((platform, origin) in hosts) {
            for (entry in fixtures.getValue(platform.id).jsonArray) {
                val path = entry.jsonObject.getValue("path").jsonPrimitive.content
                val search = entry.jsonObject["search"]?.jsonPrimitive?.content.orEmpty()
                val expected = entry.jsonObject.getValue("shortVideo").jsonPrimitive.boolean
                val target = WebUrlClassifier.classify(platform, origin + path + search) as NavTarget.PlatformPage
                assertEquals("$platform $path$search", expected, target.route.isShortVideo)
            }
        }
    }

    @Test
    fun `every platform that opens inside Dereel has fixtures`() {
        val expected = Platform.entries.filter(PlatformWeb::hasBrowser).map { it.id }.toSet()
        assertEquals(expected, fixtures.keys - "_comment")
    }
}
