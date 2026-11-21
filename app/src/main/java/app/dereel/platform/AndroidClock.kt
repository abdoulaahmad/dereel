package app.dereel.platform

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import app.dereel.core.time.DeviceClock
import java.time.ZoneId

/** Monotonic elapsed time from elapsedRealtime. Wall time and zone come from the system (DATA-03). */
class AndroidClock(private val context: Context) : DeviceClock {
    private val boot: String by lazy {
        val count = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (e: Exception) {
            -1
        }
        "boot-$count"
    }

    override fun monotonicMs(): Long = SystemClock.elapsedRealtime()
    override fun wallMs(): Long = System.currentTimeMillis()
    override fun zone(): ZoneId = ZoneId.systemDefault()
    override fun bootId(): String = boot
}
