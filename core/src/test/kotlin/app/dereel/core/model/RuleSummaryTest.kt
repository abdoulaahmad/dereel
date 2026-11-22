package app.dereel.core.model

import app.dereel.core.model.Platform.INSTAGRAM
import app.dereel.core.model.Platform.YOUTUBE
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The one-line rule summary shown for each app in Settings. It must describe what is switched
 * on, and never a limit that is saved but off.
 */
class RuleSummaryTest {
    @Test
    fun `a platform that is not selected is reported as not added, whatever is saved for it`() {
        val saved = PlatformConfig(INSTAGRAM, selected = false, allowance = AllowanceSetting().enable(15))
        assertEquals(RuleSummary.NotAdded, RuleSummary.of(saved))
    }

    @Test
    fun `blocking on with no limits`() {
        val config = PlatformConfig(YOUTUBE, selected = true)
        assertEquals(RuleSummary.Blocking(allowanceMinutes = null, interval = null), RuleSummary.of(config))
    }

    @Test
    fun `limits are listed only while they are switched on`() {
        val interval = IntervalSetting(enabled = true, watchMinutes = 5, breakMinutes = 15)
        val both = PlatformConfig(YOUTUBE, selected = true, allowance = AllowanceSetting().enable(30), interval = interval)
        assertEquals(RuleSummary.Blocking(allowanceMinutes = 30, interval = interval), RuleSummary.of(both))

        // The lengths stay saved when a limit is turned off, but they are not in force.
        val off = both.copy(allowance = both.allowance.disable(), interval = interval.copy(enabled = false))
        assertEquals(RuleSummary.Blocking(allowanceMinutes = null, interval = null), RuleSummary.of(off))
    }

    @Test
    fun `with blocking off no limit is reported, because none applies`() {
        val config = PlatformConfig(
            INSTAGRAM,
            selected = true,
            blockShortVideos = false,
            allowance = AllowanceSetting().enable(15),
            interval = IntervalSetting(enabled = true),
        )
        assertEquals(RuleSummary.BlockingOff, RuleSummary.of(config))
    }
}
