package app.wakelink.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class WakeLinkApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ARMED,
                getString(R.string.armed_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
        )

        // Audio is played manually by AlarmForegroundService on
        // STREAM_ALARM (not via the channel's own sound), so we get exact
        // control over looping/volume/DND-bypass timing -- the channel's
        // sound is left unset, but importance stays HIGH so the heads-up +
        // full-screen-intent behavior still fires.
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                getString(R.string.alarm_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    companion object {
        const val CHANNEL_ARMED = "armed_status"
        const val CHANNEL_ALARM = "wake_alarm"
    }
}
