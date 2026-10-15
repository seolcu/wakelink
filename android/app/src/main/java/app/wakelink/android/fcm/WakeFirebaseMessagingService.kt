package app.wakelink.android.fcm

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.wakelink.android.R
import android.util.Log
import app.wakelink.android.alarm.AlarmForegroundService
import app.wakelink.android.alarm.RingingActivity
import app.wakelink.android.crypto.DeviceKeys
import app.wakelink.android.crypto.SealedWake
import app.wakelink.android.data.isUserUnlocked
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Receives wake triggers. The server sends a data-only payload (no
 * "notification" key) on purpose -- this app, not the OS's default
 * notification-tray display, must decide exactly how to alert, which is
 * what lets it ring through silent mode / DND at all.
 */
class WakeFirebaseMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "wake_alarm") return
        val triggerId = message.data["trigger_id"]?.toLongOrNull() ?: return
        val intent = Intent(this, AlarmForegroundService::class.java).apply {
            putExtra(AlarmForegroundService.EXTRA_TRIGGER_ID, triggerId)
        }

        val sealed = message.data["sealed"]
        if (sealed != null) {
            // End-to-end encrypted: only this device's key opens it. Before the
            // first unlock that key is unavailable (or a stale key from before
            // a reinstall may have been used) -- ring anyway, just without the
            // sender's name and choices.
            val wake = if (isUserUnlocked(this)) SealedWake.open(sealed) { DeviceKeys.decrypt(this, it) } else null
            intent.putExtra(AlarmForegroundService.EXTRA_FROM_DISPLAY_NAME, wake?.from?.ifBlank { null } ?: getString(R.string.unknown_sender))
            if (wake != null) {
                intent.putExtra(AlarmForegroundService.EXTRA_SOUND, wake.sound)
                intent.putExtra(AlarmForegroundService.EXTRA_SOUND_KEY, wake.soundKey)
                intent.putExtra(AlarmForegroundService.EXTRA_IMAGE, wake.image)
                intent.putExtra(AlarmForegroundService.EXTRA_IMAGE_KEY, wake.imageKey)
            }
        } else {
            // From a sender whose app predates end-to-end encryption.
            intent.putExtra(AlarmForegroundService.EXTRA_FROM_DISPLAY_NAME, message.data["from_display_name"] ?: getString(R.string.unknown_sender))
            intent.putExtra(AlarmForegroundService.EXTRA_SOUND, message.data["sound"])
            intent.putExtra(AlarmForegroundService.EXTRA_IMAGE, message.data["image"])
        }
        // Must use startForegroundService (this runs from a background
        // context), and AlarmForegroundService must call startForeground()
        // within the OS's short post-start window.
        try {
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            // Android 12+ refuses background foreground-service starts unless
            // the push kept its high priority (FCM can deprioritize it, and
            // some vendor battery savers block it outright). Never fail
            // silently: show a full-screen alarm notification instead, whose
            // activity starts the service from the foreground.
            Log.w(TAG, "couldn't start alarm service from push, falling back to full-screen notification", e)
            RingingActivity.showFallbackNotification(this, intent)
        }
    }

    override fun onNewToken(token: String) {
        // Before the first unlock WorkManager (credential-protected) isn't
        // usable; the token gets uploaded on unlock instead (see
        // WakeLinkApplication.onUserUnlocked).
        if (!isUserUnlocked(this)) return
        val request = OneTimeWorkRequestBuilder<TokenUploadWorker>()
            .setInputData(TokenUploadWorker.inputData(token))
            .build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }

    private companion object {
        const val TAG = "WakeFcm"
    }
}
