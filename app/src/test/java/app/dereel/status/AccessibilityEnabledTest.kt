package app.dereel.status

import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** FR-04: native protection status comes from the real system setting, not from opening settings. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AccessibilityEnabledTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun setEnabled(value: String?) {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, value)
    }

    @Test
    fun detectsDereelAmongOtherServices() {
        setEnabled("com.example.reader/.ReaderService:app.dereel/app.dereel.nativeapp.DereelAccessibilityService")
        assertTrue(StatusMonitor.isServiceEnabled(context))
        setEnabled("app.dereel/.nativeapp.DereelAccessibilityService")
        assertTrue(StatusMonitor.isServiceEnabled(context))
    }

    @Test
    fun otherServicesOrLookalikesDoNotCount() {
        setEnabled(null)
        assertFalse(StatusMonitor.isServiceEnabled(context))
        setEnabled("com.example.reader/.ReaderService")
        assertFalse(StatusMonitor.isServiceEnabled(context))
        setEnabled("app.dereel.fake/app.dereel.nativeapp.DereelAccessibilityService")
        assertFalse(StatusMonitor.isServiceEnabled(context))
    }
}
