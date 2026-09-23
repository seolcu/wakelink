package app.wakelink.android.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.wakelink.android.R
import app.wakelink.android.WakeLinkApplication
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.TokenStore
import app.wakelink.android.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The core of the whole app: plays a looping alarm on STREAM_ALARM (which
 * bypasses ringer silent/vibrate with zero special permission -- the same
 * trick every alarm-clock app relies on), optionally punches through DND
 * "Total silence" too, and shows the full-screen ringing UI.
 */
class AlarmForegroundService : Service() {

    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var mediaPlayer: MediaPlayer? = null
    private var appliedDndOverride = false
    private var priorAlarmVolume: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Always satisfy the startForegroundService() -> startForeground()
            // contract, even if this call happens to create a fresh
            // instance (e.g. the alarm had already stopped by the time
            // "Stop" was tapped) -- stopRinging() removes it immediately.
            startForeground(NOTIFICATION_ID, buildMinimalNotification())
            stopRinging()
            return START_NOT_STICKY
        }

        val triggerId = intent?.getLongExtra(EXTRA_TRIGGER_ID, -1L) ?: -1L
        val fromDisplayName = intent?.getStringExtra(EXTRA_FROM_DISPLAY_NAME)
            ?: getString(R.string.unknown_sender)

        // startForeground must be called within moments of the service
        // being started (from startForegroundService), before any
        // suspending work -- so this happens synchronously.
        startForeground(NOTIFICATION_ID, buildRingingNotification(fromDisplayName))

        scope.launch {
            val settings = SettingsRepository(applicationContext)
            if (!settings.armed.first()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }

            if (settings.dndBypassEnabled.first()) {
                applyDndOverrideIfNeeded(settings)
            }
            if (settings.maxVolumeOnRing.first()) {
                applyMaxVolume()
            }

            startPlayback()
            ackTrigger(triggerId)
        }

        return START_NOT_STICKY
    }

    private fun buildRingingNotification(fromDisplayName: String): Notification {
        val fullScreenIntent = Intent(this, RingingActivity::class.java).apply {
            putExtra(RingingActivity.EXTRA_FROM_DISPLAY_NAME, fromDisplayName)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this, 0, fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, StopAlarmReceiver::class.java)
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, WakeLinkApplication.CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.alarm_notification_title, fromDisplayName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(0, getString(R.string.alarm_stop_action), stopPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun buildMinimalNotification(): Notification =
        NotificationCompat.Builder(this, WakeLinkApplication.CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(getString(R.string.alarm_stop_action))
            .setSilent(true)
            .build()

    /**
     * DND "Total silence" (INTERRUPTION_FILTER_NONE) is the one DND mode
     * that also mutes STREAM_ALARM -- every other mode (priority, alarms
     * only, or no DND) already lets the alarm stream through on its own,
     * so this only touches DND when it's actually blocking.
     */
    private suspend fun applyDndOverrideIfNeeded(settings: SettingsRepository) {
        val nm = getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return
        if (nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_NONE) return

        settings.savePendingDndOverride(nm.currentInterruptionFilter)
        nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS)
        appliedDndOverride = true
    }

    private fun applyMaxVolume() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        priorAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
        am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
    }

    private fun startPlayback() {
        val alarmUri = RingtoneManager.getActualDefaultRingtoneUri(applicationContext, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            setDataSource(applicationContext, alarmUri)
            isLooping = true
            prepare()
            start()
        }
    }

    private fun ackTrigger(triggerId: Long) {
        if (triggerId < 0) return // local self-test alarm, nothing to ack
        scope.launch {
            try {
                val tokenStore = TokenStore(applicationContext)
                if (tokenStore.isRegistered) {
                    ApiClient.create(tokenStore).ackTrigger(triggerId)
                }
            } catch (_: Exception) {
                // Best-effort: a failed ack doesn't stop the alarm from
                // ringing, it only leaves the sender's UI uncertain.
            }
        }
    }

    private fun stopRinging() {
        mediaPlayer?.apply {
            stop()
            release()
        }
        mediaPlayer = null

        priorAlarmVolume?.let { vol ->
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, vol, 0)
            priorAlarmVolume = null
        }

        if (appliedDndOverride) {
            scope.launch {
                val priorFilter = SettingsRepository(applicationContext).consumePendingDndOverride()
                if (priorFilter != null) {
                    val nm = getSystemService(NotificationManager::class.java)
                    if (nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(priorFilter)
                    }
                }
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        mediaPlayer?.release()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.wakelink.android.alarm.ACTION_STOP"
        const val EXTRA_TRIGGER_ID = "trigger_id"
        const val EXTRA_FROM_DISPLAY_NAME = "from_display_name"
        private const val NOTIFICATION_ID = 42
    }
}
