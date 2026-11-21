package app.dereel.core.bridge

import app.dereel.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolTest {
    @Test
    fun `valid messages parse`() {
        assertEquals(BridgeMessage.RulesReady("ig-web-0.1.0"), BridgeProtocol.parse("""{"v":1,"type":"ready","rules":"ig-web-0.1.0"}"""))
        assertEquals(BridgeMessage.BlockedEntryTapped, BridgeProtocol.parse("""{"v":1,"type":"blockedEntry"}"""))
    }

    @Test
    fun `malformed, unknown and oversized messages are rejected (SEC-04, AT-16)`() {
        val rejected = listOf(
            null,
            "",
            "not json",
            "[]",
            """{"v":1}""",
            """{"v":"1","type":"blockedEntry"}""",
            """{"v":2,"type":"blockedEntry"}""",
            """{"v":1,"type":"openIntent","url":"intent://x"}""",
            """{"v":1,"type":"blockedEntry","url":"https://evil.example"}""",
            """{"v":1,"type":"ready","rules":"../../etc/passwd"}""",
            """{"v":1,"type":"ready","rules":5}""",
            """{"v":1,"type":"ready"}""",
            """{"v":1,"type":"blockedEntry","rules":"x"}""",
            """{"v":1,"type":"ready","rules":"${"a".repeat(300)}"}""",
        )
        rejected.forEach { assertNull("accepted: $it", BridgeProtocol.parse(it)) }
    }

    @Test
    fun `only the platform's own top-level origin may send messages (SEC-04)`() {
        assertTrue(BridgeProtocol.acceptsSource(Platform.INSTAGRAM, "https://www.instagram.com", isMainFrame = true))
        assertFalse(BridgeProtocol.acceptsSource(Platform.INSTAGRAM, "https://www.instagram.com", isMainFrame = false))
        assertFalse(BridgeProtocol.acceptsSource(Platform.INSTAGRAM, "https://evil.example", isMainFrame = true))
        assertFalse(BridgeProtocol.acceptsSource(Platform.INSTAGRAM, "https://m.youtube.com", isMainFrame = true))
        assertFalse(BridgeProtocol.acceptsSource(Platform.INSTAGRAM, "http://www.instagram.com", isMainFrame = true))
        assertFalse(BridgeProtocol.acceptsSource(Platform.YOUTUBE, null, isMainFrame = true))
    }
}
