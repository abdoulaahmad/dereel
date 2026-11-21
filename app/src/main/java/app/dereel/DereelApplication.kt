package app.dereel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class DereelApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)

        // Checkpoint measured time whenever Dereel leaves the foreground (FR-33).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.status.refresh()
            override fun onStop(owner: LifecycleOwner) = container.usage.checkpoint()
        })

        // FR-28: attribute time promptly when the clock, timezone or date changes.
        val timeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                container.usage.checkpoint()
                if (intent.action == Intent.ACTION_DATE_CHANGED) container.usage.applyRetention()
                container.coordinator.reevaluateAll()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, timeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }
}

val Context.container: AppContainer get() = (applicationContext as DereelApplication).container
