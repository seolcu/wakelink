package app.wakelink.android.alarm

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.wakelink.android.R
import app.wakelink.android.WakeLinkApplication
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.TokenStore
import app.wakelink.android.network.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The core of the whole app: plays a looping alarm on STREAM_ALARM (which
 * bypasses ringer silent/vibrate with zero special permission -- the same
 * trick every alarm-clock app relies on), optionally punches through DND
 * "Total silence" too, and shows the full-screen ringing UI.
 *
 * All state is touched on the main thread only ([scope] uses
 * Dispatchers.Main), so a stop arriving mid-startup can't race playback.
 */
class AlarmForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startJob: Job? = null
    private var autoStopJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var appliedDndOverride = false
    private var priorAlarmVolume: Int? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Always satisfy the startForegroundService() -> startForeground()
            // contract, even if this call happens to create a fresh
            // instance (e.g. the alarm had already stopped by the time
            // "Stop" was tapped) -- stopRinging() removes it immediately.
            startInForeground(buildMinimalNotification())
            stopRinging()
            return START_NOT_STICKY
        }

        val triggerId = intent?.getLongExtra(EXTRA_TRIGGER_ID, -1L) ?: -1L
        val fromDisplayName = intent?.getStringExtra(EXTRA_FROM_DISPLAY_NAME)
            ?: getString(R.string.unknown_sender)

        // startForeground must be called within moments of the service
        // being started (from startForegroundService), before any
        // suspending work -- so this happens synchronously.
        startInForeground(buildRingingNotification(fromDisplayName))
        val alreadyActive = _ringingFrom.value != null
        _ringingFrom.value = fromDisplayName

        if (alreadyActive) {
            // A second wake while one is already ringing (or starting):
            // keep the same playback going rather than stacking players,
            // just show who woke us most recently and ack once it's sounding.
            scope.launch {
                startJob?.join()
                if (mediaPlayer != null) ackTrigger(triggerId)
            }
            return START_NOT_STICKY
        }

        startJob = scope.launch {
            val settings = SettingsRepository(applicationContext)
            if (!settings.armed.first()) {
                stopRinging()
                return@launch
            }

            (application as WakeLinkApplication).startupRestore.join()
            if (settings.dndBypassEnabled.first()) {
                applyDndOverrideIfNeeded(settings)
            }
            if (settings.maxVolumeOnRing.first()) {
                applyMaxVolume()
            }

            startVibration()
            // Only ack once sound is actually playing -- the sender's UI
            // treats an ack as "it rang", so a silent failure must not ack.
            if (startPlayback()) ackTrigger(triggerId)

            autoStopJob = scope.launch {
                delay(AUTO_STOP_AFTER_MS)
                stopRinging()
            }
        }

        return START_NOT_STICKY
    }

    private fun startInForeground(notification: Notification) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
        )
    }

    private fun buildRingingNotification(fromDisplayName: String): Notification {
        val fullScreenIntent = Intent(this, RingingActivity::class.java).apply {
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
            .setSmallIcon(R.drawable.ic_stat_wakelink)
            .setContentTitle(getString(R.string.alarm_notification_title, fromDisplayName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(0, getString(R.string.alarm_stop_action), stopPendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun buildMinimalNotification(): Notification =
        NotificationCompat.Builder(this, WakeLinkApplication.CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_wakelink)
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

    /**
     * Tries the user's chosen alarm sound first, then progressively more
     * generic system defaults -- a deleted custom alarm tone must not mean
     * a silent wake. Returns whether anything is actually playing.
     */
    private fun startPlayback(): Boolean {
        val candidates = listOfNotNull(
            RingtoneManager.getActualDefaultRingtoneUri(applicationContext, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            Settings.System.DEFAULT_ALARM_ALERT_URI,
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        ).distinct()

        for (uri in candidates) {
            val player = tryCreatePlayer(uri) ?: continue
            mediaPlayer = player
            return true
        }
        return false
    }

    private fun tryCreatePlayer(uri: Uri): MediaPlayer? {
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(applicationContext, uri)
            player.isLooping = true
            player.prepare()
            player.start()
            player
        } catch (_: Exception) {
            player.release()
            null
        }
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
        if (!v.hasVibrator()) return
        @Suppress("DEPRECATION") // the AudioAttributes overload is what tags this as an alarm vibration pre-API 33
        v.vibrate(
            VibrationEffect.createWaveform(VIBRATION_PATTERN, 0),
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(),
        )
        vibrator = v
    }

    private fun ackTrigger(triggerId: Long) {
        if (triggerId < 0) return // local self-test alarm, nothing to ack
        (application as WakeLinkApplication).appScope.launch {
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

    private fun releaseOutputs() {
        mediaPlayer?.apply {
            runCatching { stop() }
            release()
        }
        mediaPlayer = null
        vibrator?.cancel()
        vibrator = null

        priorAlarmVolume?.let { vol ->
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, vol, 0)
            priorAlarmVolume = null
        }

        if (appliedDndOverride) {
            appliedDndOverride = false
            // Runs on the process-wide scope: this service's own scope is
            // cancelled in onDestroy, which would otherwise race the restore.
            (application as WakeLinkApplication).appScope.launch {
                val priorFilter = SettingsRepository(applicationContext).consumePendingDndOverride()
                if (priorFilter != null) {
                    val nm = getSystemService(NotificationManager::class.java)
                    if (nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(priorFilter)
                    }
                }
            }
        }
        _ringingFrom.value = null
    }

    private fun stopRinging() {
        startJob?.cancel()
        autoStopJob?.cancel()
        releaseOutputs()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseOutputs()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "app.wakelink.android.alarm.ACTION_STOP"
        const val EXTRA_TRIGGER_ID = "trigger_id"
        const val EXTRA_FROM_DISPLAY_NAME = "from_display_name"
        private const val NOTIFICATION_ID = 42

        /** Gives up after this long so an unattended phone doesn't ring until the battery dies. */
        private const val AUTO_STOP_AFTER_MS = 5 * 60 * 1000L
        private val VIBRATION_PATTERN = longArrayOf(0, 800, 600)

        private val _ringingFrom = MutableStateFlow<String?>(null)

        /**
         * Who is currently waking this device, or null when not ringing.
         * Set synchronously on start (before the full-screen UI can
         * appear) and cleared on stop, so RingingActivity can close itself
         * however the alarm ends (notification action, auto-stop, disarmed).
         */
        val ringingFrom: StateFlow<String?> = _ringingFrom.asStateFlow()
    }
}
