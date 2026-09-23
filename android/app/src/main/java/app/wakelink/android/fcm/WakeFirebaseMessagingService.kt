package app.wakelink.android.fcm

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.wakelink.android.R
import app.wakelink.android.alarm.AlarmForegroundService
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
        val fromDisplayName = message.data["from_display_name"] ?: getString(R.string.unknown_sender)

        val intent = Intent(this, AlarmForegroundService::class.java).apply {
            putExtra(AlarmForegroundService.EXTRA_TRIGGER_ID, triggerId)
            putExtra(AlarmForegroundService.EXTRA_FROM_DISPLAY_NAME, fromDisplayName)
        }
        // Must use startForegroundService (this runs from a background
        // context), and AlarmForegroundService must call startForeground()
        // within the OS's short post-start window.
        ContextCompat.startForegroundService(this, intent)
    }

    override fun onNewToken(token: String) {
        val request = OneTimeWorkRequestBuilder<TokenUploadWorker>()
            .setInputData(TokenUploadWorker.inputData(token))
            .build()
        WorkManager.getInstance(applicationContext).enqueue(request)
    }
}
