package app.wakelink.android.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.wakelink.android.WakeLinkApplication
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.status.ArmedStatusNotifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * LOCKED_BOOT_COMPLETED arrives right after a reboot, before the user
 * unlocks (this receiver is direct-boot aware): show the armed status and
 * let the application's startup self-heal run. BOOT_COMPLETED follows once
 * the user unlocks, which is when the FCM token can be re-uploaded and acks
 * owed for alarms that rang while locked can be sent.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as WakeLinkApplication
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                val pendingResult = goAsync()
                app.appScope.launch {
                    try {
                        app.startupRestore.join()
                        ArmedStatusNotifier.update(context, SettingsRepository(context).armed.first())
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
            Intent.ACTION_BOOT_COMPLETED -> app.onUserUnlocked()
        }
    }
}
