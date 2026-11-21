package app.dereel.core.model

import app.dereel.core.model.OpeningMethod.IN_DEREEL
import app.dereel.core.model.OpeningMethod.NATIVE_APP
import app.dereel.core.model.Platform.FACEBOOK
import app.dereel.core.model.Platform.INSTAGRAM
import app.dereel.core.model.Platform.SNAPCHAT
import app.dereel.core.model.Platform.YOUTUBE
import app.dereel.core.nativeapp.NativeRules
import app.dereel.core.web.PlatformWeb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which opening methods exist per platform (UX-04, UX-05). A method Dereel has no adapter for must
 * never be offered, stored or shown as protection.
 */
class CapabilitiesTest {
    @Test
    fun `each platform offers only the methods Dereel has an adapter for`() {
        assertEquals(listOf(NATIVE_APP, IN_DEREEL), Capabilities.methods(INSTAGRAM))
        assertEquals(listOf(NATIVE_APP, IN_DEREEL), Capabilities.methods(YOUTUBE))
        assertEquals(listOf(NATIVE_APP, IN_DEREEL), Capabilities.methods(FACEBOOK))
        assertEquals(listOf(NATIVE_APP), Capabilities.methods(SNAPCHAT))
    }

    @Test
    fun `offered methods match the adapters that actually exist`() {
        for (platform in Platform.entries) {
            val rules = NativeRules.forPlatform(platform)
            assertEquals("$platform native rules", rules.isNotEmpty(), Capabilities.supports(platform, NATIVE_APP))
            assertEquals("$platform browser", PlatformWeb.hasBrowser(platform), Capabilities.supports(platform, IN_DEREEL))
            assertEquals("$platform hosts", PlatformWeb.contentHosts(platform).isNotEmpty(), Capabilities.supports(platform, IN_DEREEL))
            // An app counts as the platform's installed app exactly when Dereel can recognise it.
            assertEquals("$platform packages", rules.map { it.packageName }.toSet(), platform.nativePackages.toSet())
            rules.forEach { assertEquals(it, NativeRules.forPackage(it.packageName)) }
            platform.nativePackages.forEach { assertEquals(platform, Platform.forPackage(it)) }
        }
        assertEquals(NativeRules.all.map { it.packageName }.toSet().size, NativeRules.all.size)
    }

    @Test
    fun `the regular facebook app is not treated as a protected app`() {
        assertEquals(listOf("com.facebook.lite"), FACEBOOK.nativePackages)
        assertNull(Platform.forPackage("com.facebook.katana"))
        assertNull(NativeRules.forPackage("com.facebook.katana"))
    }

    @Test
    fun `a method without an adapter has no capabilities`() {
        val unsupported = Capabilities.of(SNAPCHAT, IN_DEREEL)
        assertFalse(unsupported.shortVideoBlocking)
        assertFalse(unsupported.sharedItemException)
        assertFalse(unsupported.recommendationControls)
        assertFalse(unsupported.adFiltering)
        assertTrue(Capabilities.of(SNAPCHAT, NATIVE_APP).shortVideoBlocking)
        assertTrue(Capabilities.of(FACEBOOK, IN_DEREEL).shortVideoBlocking)
        assertTrue(Capabilities.of(FACEBOOK, NATIVE_APP).shortVideoBlocking)
        assertFalse(Capabilities.of(FACEBOOK, NATIVE_APP).sharedItemException)
    }

    @Test
    fun `shared videos and extra controls are advertised only where they exist (FR-11, FR-20)`() {
        assertEquals(listOf(INSTAGRAM), Platform.entries.filter(Capabilities::sharedItemAvailable))
        assertTrue(Capabilities.of(YOUTUBE, IN_DEREEL).recommendationControls)
        // Facebook has no "More controls" yet, so none are claimed.
        assertTrue(BrowserControl.forPlatform(FACEBOOK).isEmpty())
        assertFalse(Capabilities.of(FACEBOOK, IN_DEREEL).recommendationControls)
        assertFalse(Capabilities.of(FACEBOOK, IN_DEREEL).adFiltering)
        assertTrue(BrowserControl.forPlatform(SNAPCHAT).isEmpty())
    }

    @Test
    fun `a platform with one method needs no choice and the others start unchosen (FR-02)`() {
        assertNull(PlatformConfig.defaultFor(INSTAGRAM).openingMethod)
        assertNull(PlatformConfig.defaultFor(YOUTUBE).openingMethod)
        assertNull(PlatformConfig.defaultFor(FACEBOOK).openingMethod)
        assertEquals(NATIVE_APP, PlatformConfig.defaultFor(SNAPCHAT).openingMethod)
    }

    @Test
    fun `a stored method that is not offered falls back safely (DATA-01)`() {
        assertEquals(NATIVE_APP, PlatformConfig.validMethod(FACEBOOK, NATIVE_APP))
        assertEquals(IN_DEREEL, PlatformConfig.validMethod(FACEBOOK, IN_DEREEL))
        assertEquals(NATIVE_APP, PlatformConfig.validMethod(SNAPCHAT, IN_DEREEL))
        assertEquals(NATIVE_APP, PlatformConfig.validMethod(SNAPCHAT, null))
        assertNull(PlatformConfig.validMethod(YOUTUBE, null))
        assertEquals(IN_DEREEL, PlatformConfig.validMethod(YOUTUBE, IN_DEREEL))
        assertThrows(IllegalArgumentException::class.java) { PlatformConfig(SNAPCHAT, openingMethod = IN_DEREEL) }
    }
}
