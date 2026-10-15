package app.wakelink.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import app.wakelink.android.alarm.AckSender
import app.wakelink.android.crypto.KeyPublisher
import app.wakelink.android.data.ArmedSync
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.isUserUnlocked
import app.wakelink.android.data.restorePendingDndOverride
import app.wakelink.android.fcm.uploadCurrentFcmToken
import app.wakelink.android.update.UpdateCheckWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class WakeLinkApplication : Application() {

    /**
     * Process-lifetime scope for work that must outlive any one component
     * -- e.g. restoring DND after AlarmForegroundService has already been
     * destroyed.
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Completes once the startup DND self-heal has run. AlarmForegroundService
     * joins this before applying a fresh override, so the startup restore can
     * never mistake a brand-new override (from the push that started this
     * process) for a stale one and undo it mid-ring.
     */
    lateinit var startupRestore: Job
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()

        // This can run before the first unlock after a reboot (a wake push
        // starting the direct-boot-aware alarm path), when only
        // device-protected storage is readable: SettingsRepository is fine,
        // TokenStore/WorkManager are not -- see onUserUnlocked().
        val settings = SettingsRepository(this)
        startupRestore = appScope.launch {
            settings.migrateLegacyIfNeeded()
            restorePendingDndOverride(this@WakeLinkApplication)
        }
        if (isUserUnlocked(this)) onUserUnlocked()
    }

    /**
     * Work that needs credential-protected storage. Runs on process start
     * when already unlocked; BootCompletedReceiver covers the unlock that
     * follows a reboot.
     */
    fun onUserUnlocked() {
        appScope.launch {
            startupRestore.join()
            uploadCurrentFcmToken(this@WakeLinkApplication)
            UpdateCheckWorker.schedule(this@WakeLinkApplication)
            KeyPublisher.publish(this@WakeLinkApplication)
            ArmedSync.syncCurrent(this@WakeLinkApplication)
            AckSender.flushPending(this@WakeLinkApplication)
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)

        // Before 0.3.1 an always-on "WakeLink is on standby" notification
        // lived here; it was noise, so drop it and its channel on upgrade.
        nm.cancel(LEGACY_ARMED_NOTIFICATION_ID)
        nm.deleteNotificationChannel(LEGACY_CHANNEL_ARMED)

        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_UPDATES,
                getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
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
        const val CHANNEL_UPDATES = "app_updates"
        private const val LEGACY_CHANNEL_ARMED = "armed_status"
        private const val LEGACY_ARMED_NOTIFICATION_ID = 1
        const val CHANNEL_ALARM = "wake_alarm"
    }
}
