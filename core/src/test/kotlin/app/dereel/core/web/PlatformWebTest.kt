package app.dereel.core.web

import app.dereel.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-19, AT-14: clearing one platform's browser data ends exactly the sign-ins the dialog names. */
class PlatformWebTest {
    private val browserPlatforms = Platform.entries.filter(PlatformWeb::hasBrowser)

    @Test
    fun `youtube data never overlaps with instagram or facebook data`() {
        val youtube = PlatformWeb.dataScope(Platform.YOUTUBE)
        for (other in listOf(Platform.INSTAGRAM, Platform.FACEBOOK)) {
            val scope = PlatformWeb.dataScope(other)
            assertTrue(scope.cookieDomains.intersect(youtube.cookieDomains.toSet()).isEmpty())
            assertTrue(scope.storageOrigins.intersect(youtube.storageOrigins.toSet()).isEmpty())
            assertTrue(scope.cookieDomains.none { "youtube" in it || "google" in it })
        }
        assertTrue(youtube.cookieDomains.none { "instagram" in it || "facebook" in it })
    }

    @Test
    fun `clearing instagram also ends the facebook sign-in, and only that one (FR-19)`() {
        // Instagram can sign in with Facebook, so its scope includes facebook.com.
        assertEquals(listOf(Platform.FACEBOOK), PlatformWeb.alsoSignedOut(Platform.INSTAGRAM))
        // Clearing Facebook leaves instagram.com alone.
        assertEquals(emptyList<Platform>(), PlatformWeb.alsoSignedOut(Platform.FACEBOOK))
        assertFalse("instagram.com" in PlatformWeb.dataScope(Platform.FACEBOOK).cookieDomains)
        assertEquals(emptyList<Platform>(), PlatformWeb.alsoSignedOut(Platform.YOUTUBE))
    }

    @Test
    fun `linked sign-in accounts are disclosed`() {
        assertEquals(listOf(LinkedAccount.FACEBOOK), PlatformWeb.dataScope(Platform.INSTAGRAM).linkedAccounts)
        assertEquals(listOf(LinkedAccount.GOOGLE), PlatformWeb.dataScope(Platform.YOUTUBE).linkedAccounts)
        assertEquals(emptyList<LinkedAccount>(), PlatformWeb.dataScope(Platform.FACEBOOK).linkedAccounts)
    }

    @Test
    fun `entry routes and bridge origins are https pages of the platform (FR-12, SEC-04)`() {
        assertEquals(listOf(Platform.INSTAGRAM, Platform.YOUTUBE, Platform.FACEBOOK), browserPlatforms)
        browserPlatforms.forEach { p ->
            assertTrue(PlatformWeb.entryUrl(p).startsWith("https://"))
            assertTrue(WebUrlClassifier.classify(p, PlatformWeb.entryUrl(p)) is NavTarget.PlatformPage)
            PlatformWeb.bridgeOrigins(p).forEach { origin ->
                assertTrue(origin.startsWith("https://"))
                // Page rules and their messages are accepted only from the platform's own pages.
                assertTrue("$origin is not a $p page", origin.removePrefix("https://") in PlatformWeb.contentHosts(p))
            }
        }
    }

    @Test
    fun `snapchat has no browser hosts, data or entry route`() {
        val p = Platform.SNAPCHAT
        assertFalse(PlatformWeb.hasBrowser(p))
        assertTrue(PlatformWeb.contentHosts(p).isEmpty())
        assertTrue(PlatformWeb.bridgeOrigins(p).isEmpty())
        assertTrue(PlatformWeb.authHosts(p).isEmpty())
        assertTrue(PlatformWeb.dataScope(p).cookieDomains.isEmpty())
        assertTrue(PlatformWeb.dataScope(p).storageOrigins.isEmpty())
        assertEquals(emptyList<Platform>(), PlatformWeb.alsoSignedOut(p))
        assertThrows(IllegalStateException::class.java) { PlatformWeb.entryUrl(p) }
    }
}
