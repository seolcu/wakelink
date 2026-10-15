package app.wakelink.android.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.wakelink.android.WakeLinkApplication

/**
 * Receiving the boot broadcasts is what starts the process after a
 * reboot: LOCKED_BOOT_COMPLETED (this receiver is direct-boot aware) lets
 * the application's startup self-heal run before the first unlock, and
 * BOOT_COMPLETED, which follows the unlock, is when the FCM token can be
 * re-uploaded and acks owed for alarms that rang while locked can be sent.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as WakeLinkApplication
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) app.onUserUnlocked()
        // LOCKED_BOOT_COMPLETED: creating the application was the point.
    }
}
