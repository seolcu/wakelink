package app.wakelink.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.TokenStore
import app.wakelink.android.data.restorePendingDndOverride
import app.wakelink.android.fcm.TokenUploadWorker
import app.wakelink.android.fcm.currentFcmToken
import app.wakelink.android.status.ArmedStatusNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
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

        startupRestore = appScope.launch { restorePendingDndOverride(this@WakeLinkApplication) }
        appScope.launch {
            ArmedStatusNotifier.update(this@WakeLinkApplication, SettingsRepository(this@WakeLinkApplication).armed.first())
        }
        appScope.launch { uploadCurrentFcmToken() }
    }

    /**
     * Re-sends the current FCM token on every process start: registration
     * may have happened without one (e.g. Play services were briefly
     * unavailable), and onNewToken alone doesn't cover that case.
     */
    private suspend fun uploadCurrentFcmToken() {
        if (!TokenStore(this).isRegistered) return
        val token = runCatching { currentFcmToken() }.getOrNull() ?: return
        WorkManager.getInstance(this).enqueue(
            OneTimeWorkRequestBuilder<TokenUploadWorker>()
                .setInputData(TokenUploadWorker.inputData(token))
                .build()
        )
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
