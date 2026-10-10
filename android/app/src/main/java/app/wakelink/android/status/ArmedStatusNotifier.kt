package app.wakelink.android.status

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import app.wakelink.android.R
import app.wakelink.android.WakeLinkApplication

/**
 * Shows the persistent low-priority "armed" / "disabled" notification.
 * This is WakeLink's transparency mechanism: whether the app is currently
 * able to ring this device is always visible, never hidden, and flips the
 * instant the master switch is toggled (see settings.SettingsScreen) or on
 * every boot (see boot.BootCompletedReceiver).
 */
object ArmedStatusNotifier {
    private const val NOTIFICATION_ID = 1

    fun update(context: Context, armed: Boolean) {
        val notification = NotificationCompat.Builder(context, WakeLinkApplication.CHANNEL_ARMED)
            .setSmallIcon(R.drawable.ic_stat_wakelink)
            .setContentTitle(
                context.getString(
                    if (armed) R.string.armed_notification_title_on else R.string.armed_notification_title_off
                )
            )
            .setOngoing(armed)
            .setSilent(true)
            .build()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }
}
