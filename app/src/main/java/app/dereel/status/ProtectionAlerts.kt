package app.dereel.status

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.dereel.R
import app.dereel.ui.MainActivity

/**
 * The "protection has stopped" notification (scope change S-02). It is posted only when the user
 * turned alerts on, and it says nothing about what the user was doing: no app, page or time.
 *
 * It can fire only while Dereel's process is alive. If Android force-stops Dereel, nothing in the
 * app can run, so no alert is possible. Home still shows the real state the next time it opens.
 */
object ProtectionAlerts {
    /** Sent by a tap on the alert: show Home, where the status and its fix are. */
    const val ACTION_SHOW_STATUS = "app.dereel.action.SHOW_STATUS"

    private const val CHANNEL_ID = "protection_status"
    private const val NOTIFICATION_ID = 1001

    /** Whether Android will actually show Dereel's notifications. */
    fun canPost(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun show(context: Context) {
        if (!canPost(context)) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(ACTION_SHOW_STATUS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dereel)
            .setContentTitle(context.getString(R.string.alert_stopped_title))
            .setContentText(context.getString(R.string.alert_stopped_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.alert_stopped_body)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // Permission was withdrawn between the check and the post.
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.alert_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.alert_channel_description) }
        manager.createNotificationChannel(channel)
    }
}
