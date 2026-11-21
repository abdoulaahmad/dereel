package app.dereel.nativeapp

import app.dereel.core.model.OpeningMethod
import app.dereel.core.model.Platform
import app.dereel.core.model.PlatformConfig
import app.dereel.core.nativeapp.NativeRules
import app.dereel.data.db.PlatformConfigEntity
import app.dereel.data.toModel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FR-06, SEC-03: the accessibility service's documented package scope must be exactly the apps
 * that have a native rule set. A package added to one place but not the other would either widen
 * what Dereel can observe or leave an app silently unprotected.
 */
class NativeScopeTest {
    private val serviceConfig = File("src/main/res/xml/accessibility_service_config.xml").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun serviceReceivesEventsOnlyFromAppsWithARuleSet() {
        val declared = checkNotNull(Regex("""android:packageNames="([^"]+)"""").find(serviceConfig))
            .groupValues[1].split(',').map(String::trim).toSet()
        assertEquals(NativeRules.all.map { it.packageName }.toSet(), declared)
        // Only Facebook Lite has a detector. The regular Facebook app is not observed.
        assertTrue("com.facebook.lite" in declared)
        assertFalse("com.facebook.katana" in declared)
    }

    @Test
    fun everyProtectedAppCanBeCheckedForInstallation() {
        NativeRules.all.forEach { rules ->
            assertTrue("${rules.packageName} missing from <queries>", manifest.contains("<package android:name=\"${rules.packageName}\" />"))
        }
    }

    @Test
    fun storedMethodThatIsNotOfferedIsRepairedOnRead() {
        fun stored(platform: Platform, method: String?) = PlatformConfigEntity(
            platformId = platform.id,
            selected = true,
            openingMethod = method,
            blockShortVideos = true,
            allowSharedVideos = true,
            allowanceEnabled = false,
            allowanceMinutes = null,
            browserControls = "",
            revision = 1,
        ).toModel()

        assertEquals(OpeningMethod.NATIVE_APP, stored(Platform.FACEBOOK, "native")?.openingMethod)
        assertEquals(OpeningMethod.IN_DEREEL, stored(Platform.FACEBOOK, "browser")?.openingMethod)
        assertEquals(OpeningMethod.NATIVE_APP, stored(Platform.SNAPCHAT, "browser")?.openingMethod)
        assertEquals(OpeningMethod.NATIVE_APP, stored(Platform.SNAPCHAT, null)?.openingMethod)
        assertEquals(null, stored(Platform.INSTAGRAM, null)?.openingMethod)
        assertEquals(OpeningMethod.IN_DEREEL, stored(Platform.INSTAGRAM, "browser")?.openingMethod)
        assertEquals(PlatformConfig.defaultFor(Platform.FACEBOOK).openingMethod, stored(Platform.FACEBOOK, "unknown")?.openingMethod)
    }
}
