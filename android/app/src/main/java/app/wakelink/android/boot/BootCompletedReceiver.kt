package app.wakelink.android.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.wakelink.android.data.SettingsRepository
import app.wakelink.android.data.restorePendingDndOverride
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
                ArmedStatusNotifier.update(context, SettingsRepository(context).armed.first())
                restorePendingDndOverride(context)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
