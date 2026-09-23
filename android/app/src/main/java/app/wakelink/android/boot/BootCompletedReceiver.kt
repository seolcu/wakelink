package app.wakelink.android.boot

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.status.ArmedStatusNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val settings = SettingsRepository(context)
                ArmedStatusNotifier.update(context, settings.armed.first())

                // Self-healing guard: if the process died mid-ring with a
                // DND override still applied, restore it now rather than
                // leaving the phone stuck on a DND state it didn't choose.
                if (settings.isDndOverrideActive()) {
                    val priorFilter = settings.consumePendingDndOverride()
                    if (priorFilter != null) {
                        val nm = context.getSystemService(NotificationManager::class.java)
                        if (nm.isNotificationPolicyAccessGranted) {
                            nm.setInterruptionFilter(priorFilter)
                        }
                    }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
